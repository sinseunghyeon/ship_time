# Ship Time — 선박 시간 위젯

홈 화면 위젯의 **[−1h] [+1h]** 버튼 한 번으로 선박 시간을 조정합니다.
**무선 디버깅(ADB)**이 연결되어 있으면 **기기 시스템 시간대도 같이** 바꿉니다.
Shizuku·루트·Device Owner 없이, 구글 계정이 있는 기기에서도 동작합니다.

- 기기 시계(UTC)는 건드리지 않고 **시간대만** `Etc/GMT±N`으로 바꿉니다. 그래서 UTC 기준이 틀어지지 않습니다.
- 권한이 없어도 위젯은 "표시 전용"으로 동작합니다. 위젯의 TextClock이 선박 시간을 표시합니다.

## 구조

```
lib/
  main.dart     Flutter UI (시계, ±1h, 모드 스위치, ADB 페어링, 진단 로그)
  native.dart   MethodChannel 래퍼 (shiptime/native)
android/app/src/main/kotlin/com/edencrew/shiptime/
  Core.kt                Zones(오프셋↔zoneId), Prefs, Hidden(숨김 API), SysTz
  Priv.kt                ShizukuPriv / RootPriv (선택 폴백)
  AdbPriv.kt             ★ 무선 디버깅(ADB)으로 셸 명령 실행
  ShipAdbManager.kt      libadb-android 연결·키 관리
  ShipTimeController.kt  ★ 시간대 변경 로직 (전략 순차 시도 + 검증 + 로그)
  ShipTimeWidget.kt      AppWidgetProvider + RemoteViews 갱신 + DeviceAdminReceiver
  MainActivity.kt        MethodChannel 핸들러
android/app/src/main/res/layout/widget_ship_time.xml   위젯 레이아웃
```

위젯 버튼은 앱이 꺼져 있어도 동작해야 하므로 실제 로직은 Kotlin(`ShipTimeController`)에 있습니다.
Flutter 앱도 같은 함수를 MethodChannel로 호출합니다.

## 시스템 시간대 변경 전략 (버전 호환)

순서대로 시도하고, 각 단계가 끝날 때마다 `persist.sys.timezone`을 읽어 **실제로 바뀌었는지 검증**합니다.

| # | 방법 | 주체 | 비고 |
|---|------|------|------|
| 0 | `DevicePolicyManager.setTimeZone` | Device Owner | 공식 API (Android 9+), 선택 사항 |
| 1 | `cmd time_zone_detector suggest_manual_time_zone --zone_id` | **ADB** / Shizuku / Root | Android 12+ |
| 2 | `service call alarm <code> s16 <zone>` | **ADB** / Shizuku / Root | 트랜잭션 코드는 런타임 조회 |
| 3 | `IAlarmManager.setTimeZone` 바인더 | Shizuku | 선택 폴백 |
| 4 | `setprop persist.sys.timezone` + 브로드캐스트 | Root | 최후 수단 |

적용하기 전에 자동 시간대를 끕니다 (`cmd time_zone_detector set_auto_detection_enabled false`, `settings put global auto_time_zone 0`).
자동 시간대가 켜져 있으면 네트워크/GPS가 다시 덮어쓰기 때문입니다.

어느 단계에서 실패했는지는 앱의 **진단 로그**에 남습니다.

## 친구 폰 설정 (무선 디버깅 — Shizuku 불필요)

1. 설정 > 휴대전화 정보 > 소프트웨어 정보 > **빌드번호 7번 탭** → 개발자 옵션 활성화
2. **Wi-Fi에 연결한 상태에서** 개발자 옵션 > **무선 디버깅** 켜기
3. Ship Time 앱 > **ADB 연결**
4. 처음이면 **페어링**: 무선 디버깅 > "기기와 페어링" → 6자리 코드·포트를 앱에 입력
5. 홈 화면 길게 누르기 > 위젯 > Ship Time 추가

⚠️ **재부팅하면 무선 디버깅을 다시 켜고** 앱에서 "ADB 연결"을 한 번 더 해야 합니다. Wi-Fi가 필요합니다.
페어링은 보통 한 번이면 되고, 이후에는 연결만 다시 하면 됩니다.

### (선택) 자동 시간대 끄기 권한 영구 부여

PC에서 한 번만:

```bash
adb shell pm grant com.edencrew.shiptime android.permission.WRITE_SECURE_SETTINGS
```

앱이 ADB로 연결되어 있으면 이 grant 없이도 `settings put` 명령으로 자동 시간대를 끕니다.

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
tool/emulator_test.sh              # 기본: API 30, 33, 35
KEEP_EMULATOR=1 tool/emulator_test.sh 35
```

에뮬레이터 스크립트는 아직 Shizuku 경로를 기준으로 합니다. ADB 무선 디버깅 경로를 실기기에서 확인하는 것을 권장합니다.

```bash
# debug 훅으로 컨트롤러 경로 실행
adb shell am broadcast -f 32 -n com.edencrew.shiptime/.DebugCommandReceiver \
  -a com.edencrew.shiptime.debug.CMD --es cmd plus --es id 1
adb logcat -s ShipTime
```
