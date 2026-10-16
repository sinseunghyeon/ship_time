#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# Ship Time 에뮬레이터 자동 테스트 (macOS / Linux)
#
#   tool/emulator_test.sh                # 기본: API 30, 33, 35
#   tool/emulator_test.sh 28 31 34 36    # 원하는 API 레벨 지정
#
# 환경변수
#   SKIP_BUILD=1      APK 빌드 생략 (이미 build/app/outputs/flutter-apk/app-debug.apk 가 있을 때)
#   KEEP_EMULATOR=1   테스트 후 에뮬레이터를 끄지 않음 (위젯을 직접 눌러보고 싶을 때)
#   WIPE=1            AVD 데이터 초기화 후 부팅
#   USE_SERIAL=emulator-5554   이미 켜져 있는 에뮬레이터/기기에서 바로 테스트 (이미지·AVD 단계 생략)
#   AVD_NAME=Pixel_8_API_35    Android Studio 에서 만든 기존 AVD 사용 (이미지 설치 생략)
#
# 하는 일 (API 레벨마다)
#   1. system image 설치 + AVD 생성 (shiptime_api<N>)
#   2. 에뮬레이터 부팅
#   3. Ship Time(debug) 설치
#   4. 앱이 시스템 시간대를 바꿀 수 있는 상태로 만든다
#      (앱의 ADB 연결 화면에서 직접 연결 — 이 스크립트는 그 과정을 자동화하지 않는다)
#   5. 위젯 버튼과 같은 경로로 ±1h / 오프셋 지정 시나리오 실행
#      → 매 단계 persist.sys.timezone 이 기대값인지, 기기 UTC 가 틀어지지 않았는지 검사
#   6. 결과 요약 + 로그 저장 (build/emulator-test/)
# ─────────────────────────────────────────────────────────────────────────────
# macOS 기본 bash 3.2 호환 (빈 배열은 ${a[@]+"${a[@]}"} 로 전개)
set -uo pipefail

APIS=("$@")
[ ${#APIS[@]} -eq 0 ] && APIS=(30 33 35)

PKG=com.edencrew.shiptime
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/build/emulator-test"
APK="$ROOT/build/app/outputs/flutter-apk/app-debug.apk"
mkdir -p "$OUT"

# ── 색/출력 ──────────────────────────────────────────────────────────────────
c_b=$'\033[1;34m'; c_g=$'\033[1;32m'; c_r=$'\033[1;31m'; c_y=$'\033[1;33m'; c_0=$'\033[0m'
step() { printf '\n%s▶ %s%s\n' "$c_b" "$*" "$c_0"; }
info() { printf '  %s\n' "$*"; }
warn() { printf '  %s! %s%s\n' "$c_y" "$*" "$c_0"; }
die()  { printf '%s✗ %s%s\n' "$c_r" "$*" "$c_0"; exit 1; }

# ── SDK 위치 ────────────────────────────────────────────────────────────────
if [ -z "${ANDROID_HOME:-}" ]; then
  for d in "${ANDROID_SDK_ROOT:-}" "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
    [ -n "$d" ] && [ -d "$d" ] && ANDROID_HOME="$d" && break
  done
fi
[ -n "${ANDROID_HOME:-}" ] || die "Android SDK 를 찾지 못했습니다. ANDROID_HOME 을 지정하세요."
export ANDROID_HOME ANDROID_SDK_ROOT="$ANDROID_HOME"

ADB="$ANDROID_HOME/platform-tools/adb"
EMU="$ANDROID_HOME/emulator/emulator"
SDKM=""
for c in "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "$ANDROID_HOME"/cmdline-tools/*/bin/sdkmanager; do
  [ -x "$c" ] && SDKM="$c" && break
done
AVDM="${SDKM%sdkmanager}avdmanager"

# sdkmanager 는 Java 필요 → 없으면 Android Studio 내장 JBR 사용
if ! java -version >/dev/null 2>&1; then
  for j in "/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
           "/Applications/Android Studio.app/Contents/jre/Contents/Home"; do
    [ -d "$j" ] && export JAVA_HOME="$j" && export PATH="$j/bin:$PATH" && break
  done
fi

[ -x "$ADB" ] || die "adb 없음: Android Studio > SDK Manager > SDK Tools > Platform-Tools 설치"
[ -x "$EMU" ] || die "emulator 없음: SDK Tools > Android Emulator 설치"
[ -n "$SDKM" ] || die "sdkmanager 없음: SDK Tools > Android SDK Command-line Tools (latest) 설치"
command -v flutter >/dev/null || [ "${SKIP_BUILD:-0}" = 1 ] || die "flutter 가 PATH 에 없습니다."

case "$(uname -m)" in
  arm64|aarch64) ABI=arm64-v8a ;;
  *) ABI=x86_64 ;;
esac

# ── 공통 함수 ───────────────────────────────────────────────────────────────
SERIAL=""
A()  { "$ADB" -s "$SERIAL" "$@"; }
S()  { "$ADB" -s "$SERIAL" shell "$@" 2>/dev/null | tr -d '\r'; }

zone_of() { # 오프셋 → Etc/GMT 존 ID (부호 반대)
  local o=$1
  if [ "$o" -eq 0 ]; then echo "Etc/GMT"
  elif [ "$o" -gt 0 ]; then echo "Etc/GMT-$o"
  else echo "Etc/GMT+$(( -o ))"; fi
}

field() { # "key=value" 형식의 RESULT 줄에서 값 추출
  printf '%s' "$2" | sed -n "s/.* $1=\([^ ]*\).*/\1/p"
}

CMD_ID=0
LAST=""
run_cmd() { # run_cmd <cmd> [offset]  → LAST 에 RESULT 줄 저장
  CMD_ID=$((CMD_ID + 1))
  local id="t$CMD_ID" extra=()
  [ $# -ge 2 ] && extra=(--ei offset "$2")
  A logcat -c >/dev/null 2>&1
  A shell am broadcast -f 32 -n "$PKG/.DebugCommandReceiver" -a "$PKG.debug.CMD" \
      --es cmd "$1" --es id "$id" ${extra[@]+"${extra[@]}"} >/dev/null 2>&1
  LAST=""
  for _ in $(seq 1 50); do
    LAST=$(A logcat -d -s ShipTime:I ShipTime:E 2>/dev/null | tr -d '\r' | grep "RESULT id=$id " | tail -1)
    [ -n "$LAST" ] && break
    sleep 0.5
  done
  # 이번 명령의 상세 로그(전략별 시도 내역)도 저장
  A logcat -d -s ShipTime:I ShipTime:E 2>/dev/null | tr -d '\r' >>"$LOGFILE"
  [ -n "$LAST" ]
}

PASS=0; FAIL=0

expect() { # expect <설명> <cmd> [offset] <기대 오프셋>
  local desc=$1 cmd=$2 arg="" want
  if [ $# -eq 4 ]; then arg=$3; want=$4; else want=$3; fi
  local wantZone; wantZone=$(zone_of "$want")
  if [ -n "$arg" ]; then run_cmd "$cmd" "$arg"; else run_cmd "$cmd"; fi
  local rc=$?
  local sys offset msg ok
  sys=$(field sys "$LAST"); offset=$(field offset "$LAST"); ok=$(field ok "$LAST")
  msg=$(printf '%s' "$LAST" | sed -n 's/.* msg=\(.*\)$/\1/p')

  # 기기 UTC 가 호스트 UTC 와 같은지 (시간대만 바뀌고 시계는 그대로여야 함)
  local dev host drift
  dev=$(S date -u +%s); host=$(date -u +%s)
  drift=$(( dev > host ? dev - host : host - dev ))

  local reason=""
  [ $rc -ne 0 ] && reason="응답 없음"
  [ -z "$reason" ] && [ "$offset" != "$want" ] && reason="저장된 오프셋 $offset ≠ $want"
  # 이미 같은 오프셋이면 컨트롤러가 변경을 생략하므로 존 이름이 다를 수 있음 → msg 로 판별
  if [ -z "$reason" ] && [ "$sys" != "$wantZone" ] && [[ "$msg" != 이미* ]]; then
    reason="시스템 시간대 $sys ≠ $wantZone"
  fi
  [ -z "$reason" ] && [ "$drift" -gt 5 ] && reason="기기 UTC 가 ${drift}초 어긋남"

  if [ -z "$reason" ]; then
    PASS=$((PASS + 1))
    printf '  %s✓%s %-28s sys=%-12s utcΔ=%ss  %s\n' "$c_g" "$c_0" "$desc" "$sys" "$drift" "$msg"
  else
    FAIL=$((FAIL + 1))
    printf '  %s✗%s %-28s %s  (ok=%s msg=%s)\n' "$c_r" "$c_0" "$desc" "$reason" "$ok" "$msg"
  fi
}

# ── 1. APK 빌드 ─────────────────────────────────────────────────────────────
if [ "${SKIP_BUILD:-0}" != 1 ]; then
  step "debug APK 빌드"
  (
    cd "$ROOT" || exit 1
    if [ ! -f android/gradlew ] || [ ! -f android/local.properties ]; then
      flutter create --platforms=android --org com.edencrew . >/dev/null || exit 1
    fi
    flutter pub get >/dev/null && flutter build apk --debug
  ) || die "빌드 실패"
fi
[ -f "$APK" ] || die "APK 없음: $APK"

# ── 3. 연결된 에뮬레이터 정리 안내 ─────────────────────────────────────────
"$ADB" start-server >/dev/null 2>&1
yes | "$SDKM" --licenses >"$OUT/sdkmanager-licenses.log" 2>&1 || true
if ! "$SDKM" --version >"$OUT/sdkmanager-version.log" 2>&1; then
  warn "sdkmanager 실행 실패 ($SDKM):"
  tail -n 10 "$OUT/sdkmanager-version.log" | sed 's/^/      /'
fi

SUMMARY=()
# 기존 AVD 하나만 지정했으면 그 AVD 로 한 번만 실행
[ -n "${AVD_NAME:-}" ] && [ $# -eq 0 ] && APIS=(0)
if [ -n "${USE_SERIAL:-}" ]; then
  APIS=("$("$ADB" -s "$USE_SERIAL" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')")
  [ -n "${APIS[0]}" ] || die "$USE_SERIAL 에 연결할 수 없습니다 (adb devices 확인)"
fi

for API in "${APIS[@]}"; do
  NAME="shiptime_api$API"
  IMG="system-images;android-$API;google_apis;$ABI"
  PORT=$((5560 + (API % 20) * 2))
  SERIAL="emulator-$PORT"
  [ -n "${USE_SERIAL:-}" ] && SERIAL="$USE_SERIAL"
  [ -n "${AVD_NAME:-}" ] && NAME="$AVD_NAME"
  LOGFILE="$OUT/api$API.log"
  LABEL="API $API"; [ -n "${AVD_NAME:-}" ] && LABEL="$AVD_NAME"
  [ -n "${USE_SERIAL:-}" ] && LABEL="$USE_SERIAL (SDK $API)"
  : >"$LOGFILE"
  PASS=0; FAIL=0

  step "API $API ($ABI) — $NAME"

  # system image + AVD (기존 AVD/실행 중인 기기를 쓰면 생략)
  if [ -z "${USE_SERIAL:-}" ] && [ -z "${AVD_NAME:-}" ] && [ ! -d "$ANDROID_HOME/system-images/android-$API/google_apis/$ABI" ]; then
    info "system image 설치: $IMG (수 GB, 처음 한 번만)"
    # 종료 코드 대신 실제 설치 여부로 판단 (경고만 있어도 비정상 종료하는 경우가 있음)
    yes | "$SDKM" --install "$IMG" >"$OUT/sdkmanager-api$API.log" 2>&1
    if [ ! -d "$ANDROID_HOME/system-images/android-$API/google_apis/$ABI" ]; then
      warn "이미지 설치 실패 — 건너뜀 (sdkmanager 출력 마지막 부분:)"
      tail -n 15 "$OUT/sdkmanager-api$API.log" | sed 's/^/      /'
      SUMMARY+=("$LABEL: 이미지 설치 실패 → $OUT/sdkmanager-api$API.log")
      continue
    fi
  fi
  if [ -z "${USE_SERIAL:-}" ] && ! "$AVDM" list avd -c 2>/dev/null | grep -qx "$NAME" \
     && ! "$EMU" -list-avds 2>/dev/null | grep -qx "$NAME"; then
    echo no | "$AVDM" create avd -n "$NAME" -k "$IMG" -d pixel_6 >/dev/null 2>&1 \
      || echo no | "$AVDM" create avd -n "$NAME" -k "$IMG" >/dev/null \
      || { warn "AVD 생성 실패 — 건너뜀"; SUMMARY+=("$LABEL: AVD 생성 실패"); continue; }
  fi

  # 부팅
  if [ -z "${USE_SERIAL:-}" ] && ! "$ADB" devices | grep -q "^$SERIAL"; then
    wipe=(); [ "${WIPE:-0}" = 1 ] && wipe=(-wipe-data)
    "$EMU" -avd "$NAME" -port "$PORT" -no-snapshot -no-boot-anim -no-audio ${wipe[@]+"${wipe[@]}"} \
      >"$OUT/emulator-api$API.log" 2>&1 &
    info "부팅 대기…"
  fi
  booted=0
  for _ in $(seq 1 180); do
    [ "$(S getprop sys.boot_completed)" = "1" ] && booted=1 && break
    sleep 2
  done
  [ $booted = 1 ] || { warn "부팅 실패 (로그: $OUT/emulator-api$API.log)"; SUMMARY+=("$LABEL: 부팅 실패"); continue; }
  info "부팅 완료: Android $(S getprop ro.build.version.release) / SDK $(S getprop ro.build.version.sdk)"
  A shell settings put global window_animation_scale 0 >/dev/null 2>&1

  # 설치
  A install -r -g "$APK" >/dev/null || { warn "Ship Time 설치 실패"; SUMMARY+=("$LABEL: 설치 실패"); continue; }

  # 기준 상태: 자동 시간대 ON 으로 시작 → 앱이 끄는지 검사
  A shell settings put global auto_time_zone 1 >/dev/null 2>&1

  # ── 앱이 시스템 시간대를 바꿀 수 있는 상태로 만들기 ────────────────────────
  # 이 스크립트는 앱의 ADB 연결(무선 디버깅 페어링)을 자동화하지 않는다.
  # 에뮬레이터 화면에서 직접 연결한 뒤 Enter 를 누르면 시나리오를 검증한다.
  A shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1
  sleep 3
  run_cmd state
  printf '\n  %s에뮬레이터 화면의 Ship Time 앱에서 시스템 시간대를 바꿀 수 있는 상태로 만든 뒤 Enter%s\n' "$c_y" "$c_0"
  printf '  (연결이 안 돼 있으면 아래 시나리오는 "표시 전용"으로만 통과/실패가 판정됩니다)\n'
  printf '  건너뛰려면 그냥 Enter — SKIP_CONNECT=1 로 실행하면 이 질문을 생략합니다\n'
  [ "${SKIP_CONNECT:-0}" = 1 ] || read -r _

  # 실제로 시스템 시간대를 바꿀 수 있는지 1회 탐지 (판정이 아니라 안내용)
  run_cmd set 9
  if [ "$(S getprop persist.sys.timezone)" = "Etc/GMT-9" ]; then
    info "시스템 시간대 변경 경로 확인됨 → 전체 시나리오 검증"
  else
    warn "시스템 시간대가 바뀌지 않음 → 앱이 아직 연결되지 않았을 수 있습니다 (표시 전용만 유효)"
  fi

  # ── 시나리오 ──────────────────────────────────────────────────────────────
  run_cmd sync_on
  expect "UTC+9 지정"            set 9    9
  autotz=$(S settings get global auto_time_zone)
  if [ "$autotz" = "0" ]; then PASS=$((PASS + 1)); printf '  %s✓%s 자동 시간대 꺼짐\n' "$c_g" "$c_0"
  else FAIL=$((FAIL + 1)); printf '  %s✗%s 자동 시간대가 여전히 %s\n' "$c_r" "$c_0" "$autotz"; fi
  expect "+1h → UTC+10"          plus     10
  expect "+1h → UTC+11"          plus     11
  expect "-1h → UTC+10"          minus    10
  expect "-1h → UTC+9"           minus    9
  expect "UTC+0 지정"            set 0    0
  expect "-1h → UTC-1"           minus    -1
  expect "UTC-12 지정"           set -12  -12
  expect "-1h (하한 유지)"       minus    -12
  expect "UTC+14 지정"           set 14   14
  expect "+1h (상한 유지)"       plus     14
  expect "다시 적용 (UTC+14)"    reapply  14
  expect "UTC+9 복귀"            set 9    9

  # 표시 전용 모드: 오프셋만 바뀌고 시스템은 그대로여야 함
  run_cmd sync_off
  before=$(S getprop persist.sys.timezone)
  run_cmd plus
  after=$(field sys "$LAST")
  if [ "$(field offset "$LAST")" = "10" ] && [ "$after" = "$before" ]; then
    PASS=$((PASS + 1)); printf '  %s✓%s 표시 전용: 오프셋만 +1, 시스템 %s 유지\n' "$c_g" "$c_0" "$before"
  else
    FAIL=$((FAIL + 1)); printf '  %s✗%s 표시 전용 모드 이상 (offset=%s, %s → %s)\n' "$c_r" "$c_0" "$(field offset "$LAST")" "$before" "$after"
  fi
  run_cmd set 9
  run_cmd sync_on

  # 상세 로그(앱 내부 진단 로그) 저장
  A shell run-as "$PKG" cat shared_prefs/ship_time.xml >"$OUT/api$API.prefs.xml" 2>/dev/null

  SUMMARY+=("$LABEL: 통과 $PASS / 실패 $FAIL")
  if [ "${KEEP_EMULATOR:-0}" != 1 ] && [ -z "${USE_SERIAL:-}" ]; then
    A emu kill >/dev/null 2>&1
    sleep 3
  else
    info "에뮬레이터 유지 ($SERIAL) — 홈 화면을 길게 눌러 Ship Time 위젯을 추가해 직접 눌러보세요"
  fi
done

# ── 요약 ────────────────────────────────────────────────────────────────────
step "결과"
for s in ${SUMMARY[@]+"${SUMMARY[@]}"}; do info "$s"; done
info "로그: $OUT/api<N>.log (전략별 시도 내역), api<N>.prefs.xml (앱 진단 로그)"
