# Ship Time — 선박 시간 위젯

> 기기에 설치되는 앱 이름은 **Helm**입니다. (패키지 `com.edencrew.shiptime`)

홈 화면 위젯의 **[−1h] [+1h]** 버튼 한 번으로 선박 시간을 조정합니다.
**무선 디버깅(ADB)**이 연결되어 있으면 **기기 시스템 시간대도 같이** 바꿉니다.
루트·Device Owner 없이, 구글 계정이 있는 기기에서도 동작합니다.

- 기기 시계(UTC)는 건드리지 않고 **시간대만** `Etc/GMT±N`으로 바꿉니다. 그래서 UTC 기준이 틀어지지 않습니다.
- 처음 한 번만 **알림에서 6자리 페어링 코드**를 입력하면 됩니다. 이후에는 재부팅해도 앱이 무선 디버깅을 **자동으로 켜고 다시 연결**합니다.
- 권한이 없거나 Wi-Fi가 없어도 위젯은 "표시 전용"으로 동작합니다. 위젯의 TextClock이 선박 시간을 표시합니다.

## 구조

```
lib/
  main.dart             진입점 + 크래시 로깅
  app.dart              MaterialApp / 테마
  root_shell.dart       하단 탭 셸 (시간 · 설정 · 기록)
  ship_controller.dart  앱 전역 상태 (폴링, busy, 토스트)
  native.dart           MethodChannel 래퍼 (shiptime/native) + ShipState
  pages/                home_page(시계·±1h) / settings_page(ADB 연결·페어링) / log_page(진단 로그)
  cat.dart, ui.dart, app_colors.dart   마스코트·포맷 유틸·색상
android/app/src/main/kotlin/com/edencrew/shiptime/
  Core.kt                Zones(오프셋↔zoneId), Prefs, Hidden(숨김 API), SysTz
  Priv.kt                Priv 인터페이스 / RootPriv (선택 폴백)
  AdbPriv.kt             ★ 무선 디버깅(ADB)으로 셸 명령 실행
  ShipAdbManager.kt      libadb-android 연결·키 관리
  ShipTimeController.kt  ★ 시간대 변경 로직 (전략 순차 시도 + 검증 + 로그)
  ShipTimeWidget.kt      AppWidgetProvider + RemoteViews 갱신 + DeviceAdminReceiver
  PairingService.kt      알림으로 페어링 (포트 mDNS 탐색 + 알림 입력칸으로 코드 수신)
  WirelessAdb.kt         무선 디버깅 자동 켜기 + BootReceiver / AdbReconnectJob (부팅 후 재연결)
  MainActivity.kt        MethodChannel 핸들러
android/app/src/debug/.../DebugCommandReceiver.kt   debug 빌드 전용 테스트 훅 (아래 "에뮬레이터 테스트")
android/app/src/main/res/layout/widget_ship_time(_large).xml   위젯 레이아웃 (기본 / 큰 고양이)
test/widget_test.dart   Dart 단위 테스트 (ShipState 파싱, 포맷)
tool/emulator_test.sh   에뮬레이터 시나리오 테스트
```

위젯 버튼은 앱이 꺼져 있어도 동작해야 하므로 실제 로직은 Kotlin(`ShipTimeController`)에 있습니다.
Flutter 앱도 같은 함수를 MethodChannel로 호출합니다.

## 시스템 시간대 변경 전략 (버전 호환)

순서대로 시도하고, 각 단계가 끝날 때마다 `persist.sys.timezone`을 읽어 **실제로 바뀌었는지 검증**합니다.

| # | 방법 | 주체 | 비고 |
|---|------|------|------|
| 0 | `DevicePolicyManager.setTimeZone` | Device Owner | 공식 API (Android 9+), 선택 사항 |
| 1 | `cmd time_zone_detector suggest_manual_time_zone --zone_id` | **ADB** / Root | Android 12+ |
| 2 | `service call alarm <code> s16 <zone>` | **ADB** / Root | 트랜잭션 코드는 런타임 조회 |
| 3 | `setprop persist.sys.timezone` + 브로드캐스트 | Root | 최후 수단 |

적용하기 전에 자동 시간대를 끕니다 (`cmd time_zone_detector set_auto_detection_enabled false`, `settings put global auto_time_zone 0`).
자동 시간대가 켜져 있으면 네트워크/GPS가 다시 덮어쓰기 때문입니다.

어느 단계에서 실패했는지는 앱의 **진단 로그**에 남습니다.

## 친구 폰 설정 (무선 디버깅)

1. 설정 > 휴대전화 정보 > 소프트웨어 정보 > **빌드번호 7번 탭** → 개발자 옵션 활성화
2. **Wi-Fi에 연결한 상태에서** 개발자 옵션 > **무선 디버깅** 켜기 → **"이 네트워크에서 항상 허용"** 체크
3. Ship Time 앱 > 설정 탭 > **페어링** → 앱이 무선 디버깅 화면을 엽니다
4. **"페어링 코드로 기기 페어링"** 을 누르고, 화면에 뜬 6자리 코드를 **알림을 내려 "코드 입력"** 에 넣습니다
   (설정 화면을 벗어나지 않아도 됩니다. 포트는 앱이 자동으로 찾습니다)
5. 홈 화면 길게 누르기 > 위젯 > Ship Time 추가

알림 권한을 거부했다면 "코드 직접 입력"으로 포트와 코드를 앱에 입력할 수 있습니다.

### 재부팅 후 자동 복구

첫 연결에 성공하면 앱이 그 ADB 권한으로 자기 자신에게 `WRITE_SECURE_SETTINGS`를 부여합니다(PC 불필요).
그 뒤로는 다음과 같이 동작합니다.

- 부팅 후 Wi-Fi가 잡히면 앱이 **무선 디버깅을 직접 켜고 다시 연결**합니다 (`AdbReconnectJob`).
- 위젯을 눌렀을 때 무선 디버깅이 꺼져 있으면 앱이 켜고 바로 적용합니다.
- 설정 탭의 **"무선 디버깅 자동 켜기"** 로 끌 수 있습니다.

조건: "항상 허용"으로 신뢰한 **같은 Wi-Fi**여야 합니다. 다른 Wi-Fi나 Wi-Fi가 없는 곳에서는 시스템이 무선 디버깅을 켜지 않으며, 그때 위젯은 "표시 전용"으로 동작합니다.
새 Wi-Fi에서는 무선 디버깅을 한 번 직접 켜고 "항상 허용"을 체크하면 됩니다 (페어링은 다시 할 필요 없음).

> 자동 켜기를 쓰면 신뢰한 Wi-Fi에서 무선 디버깅이 켜진 상태로 유지됩니다. 페어링된 키가 있어야 접속할 수 있지만, 원하지 않으면 스위치로 끄세요.

### (선택) Device Owner — 공장초기화된 기기 / 계정이 없는 기기 전용

```bash
adb shell dpm set-device-owner com.edencrew.shiptime/.ShipAdminReceiver
```

구글 계정이 있으면 등록할 수 없습니다. 이 앱의 기본 경로는 **무선 디버깅**입니다.

## 빌드

```bash
cd ship_time
flutter create --platforms=android --org com.edencrew .   # 빠진 파일만 생성
flutter pub get
flutter build apk --release
# → build/app/outputs/flutter-apk/app-release.apk
```

- release 빌드는 debug 키로 서명돼 있어서 바로 설치할 수 있습니다.
- `libadb-android`는 JitPack에서 받습니다 (`android/build.gradle.kts`에 jitpack 저장소).

## 에뮬레이터 테스트

```bash
tool/emulator_test.sh              # 기본: API 33, 35
KEEP_EMULATOR=1 tool/emulator_test.sh 35
```

`google_apis` 이미지 에뮬레이터에서는 스크립트가 `adb root`를 실행합니다. 그러면 adbd가 `:5555`에서 인증 없이(`ro.adb.secure=0`) 대기하므로,
앱의 ADB 경로가 페어링 없이 자동으로 연결되어 **실제 ADB 경로**로 검증합니다.
Play Store 이미지나 실기기에서는 앱에서 직접 ADB를 연결한 뒤 Enter를 누르세요 (`SKIP_CONNECT=1`이면 이 단계를 건너뜁니다).
API 30 에뮬레이터는 adbd가 TCP 포트를 열지 않아 자동 연결이 되지 않습니다. 그래서 기본 대상에서 뺐습니다 (Android 11은 실기기에서 확인하세요).

스크립트는 시간대 변경 시나리오만 검증합니다. 알림 페어링과 자동 복구는 아래 debug 명령으로 수동 확인합니다
(API 35 에뮬레이터에서 확인 완료. 실제 재부팅·제조사별 알림 UI는 실기기에서 확인 필요).

```bash
# debug 훅: 위젯 버튼과 같은 경로로 실행하고 결과를 logcat 한 줄(RESULT ...)로 남김
CMD() { adb shell am broadcast -f 268435488 -n com.edencrew.shiptime/.DebugCommandReceiver \
          -a com.edencrew.shiptime.debug.CMD --es id 1 "$@"; }
CMD --es cmd plus                         # +1h  (minus, reapply, state, sync_on, sync_off)
CMD --es cmd set --ei offset 9            # UTC+9 지정
CMD --es cmd pair_start                   # 알림 페어링 시작 (앱이 화면에 떠 있을 때)
CMD --es cmd pair_code --es code 123456   # 알림 입력칸과 같은 경로로 코드 제출
CMD --es cmd boot                         # 부팅 후 재연결 작업 예약 (Wi-Fi 연결 시 바로 실행)
adb logcat -s ShipTime
```
