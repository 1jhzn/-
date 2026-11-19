# 전파 감지기 (Android, Kotlin)

폰 주변의 **블루투스(BLE) · 와이파이 · 기지국** 전파를 페어링 없이 그냥 읽어서,
**평소에 없던 새 신호가 나타나면 제조사·기기 종류·대략 거리로 알려주는** 앱입니다.

- 설치형 앱이라 브라우저처럼 `chrome://flags`를 켜거나 기기 선택(페어링) 창을 거칠 필요가 없습니다.
- 읽은 신호는 폰 밖으로 보내지 않습니다. 인터넷 권한 자체를 쓰지 않습니다.
- 기준 저장(`기준 저장`)을 한 번 눌러 두면, 그다음부터 **처음 잡히는 신호**와 **세기가 확 오른 신호**만 따로 표시합니다.

## 지금 바로 APK 받기

1. GitHub에서 이 폴더(`android-signal-app`) 상위 저장소로 들어갑니다.
2. `.github/workflows/build-apk.yml` 파일을 추가합니다. (내용은 `GITHUB_WORKFLOW_build-apk.yml` 그대로 복사)
3. **Actions → Build Android APK → Run workflow → Run** 을 누르고, 끝나면 Artifact `signal-detector-debug-apk`를 내려받습니다.

> 저장소에 `main` 브랜치로 푸시가 들어오면 `android-signal-app/**` 경로 변경 시 자동으로도 빌드됩니다.

## 로컬 / Android Studio에서 빌드

```bash
cd android-signal-app
./gradlew assembleDebug          # 결과: app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # 화면 실행 스모크 테스트 + 제조사/거리 계산 테스트
```

Android Studio(2023.1 이상)에서는 이 폴더를 그대로 열고 Run 하면 됩니다. (JDK 17, Android SDK 34)

## 앱 사용법

1. 앱을 열고 **권한 허용** (`근처 기기`, `위치`) — 안드로이드가 주변 신호를 앱에 넘겨줄 때 필요한 권한입니다.
2. **▶ 스캔 시작** — 주변 신호가 목록에 쌓입니다.
3. **기준 저장** — 지금 잡힌 블루투스·와이파이 목록을 "평소 상태"로 저장합니다.
4. 이후 처음 잡히는 신호는 목록 맨 위에 `🚨 새 신호` 로, 상단에 빨간 배너로 뜨고 짧게 진동합니다.
5. 항목을 누르면 제조사·주소·처음 잡힌 시각·대략 거리가 나오고, `이 신호는 무시` 로 기준에 넣을 수 있습니다.

## 무엇을 알려주나

| 구분 | 읽는 것 | 알려주는 것 |
|---|---|---|
| 블루투스 | 광고 패킷의 MAC·이름·RSSI·제조사 ID·서비스 UUID | 제조사(Bluetooth SIG 등록명), 기기 추정(에어태그·에어팟·갤럭시·타일·ESP32 등), 대략 거리 |
| 와이파이 | SSID·BSSID·주파수·보안방식·RSSI | BSSID 앞 3바이트(IEEE OUI)로 제조사, 공유기 종류(ipTIME·KT·SKB·LGU+·TP-Link), 2.4/5/6GHz·채널, 보안 방식, 대략 거리 |
| 기지국 | `allCellInfo` 의 LTE/5G NR/3G/2G | 통신사, PCI·셀 ID·밴드, 신호 세기 |

기지국은 이동할 때마다 바뀌므로 **새 신호 알림에서는 제외**했습니다(계속 울리는 것을 막기 위해). 목록에는 표시됩니다.

## 데이터 출처

- `app/src/main/assets/oui.csv` — IEEE MA-L OUI 등록부 (MAC 제조사)
- `app/src/main/assets/ble_companies.csv` — Bluetooth SIG Company Identifiers

## 구조

```
android-signal-app/
├── app/src/main/java/com/jihun/signalradar/
│   ├── MainActivity.kt          화면·권한·새 신호 알림
│   ├── SignalRepository.kt      실시간 신호 + 기준(baseline) 저장
│   ├── SignalAdapter.kt         목록 표시
│   ├── SignalApp.kt             시작 시 제조사 표 로딩
│   ├── identify/VendorDb.kt     OUI / Bluetooth SIG 표
│   ├── identify/Classifier.kt   기기 추정·대역·거리 계산
│   ├── scan/BleScanner.kt       BluetoothLeScanner 스캔
│   ├── scan/WifiScanner.kt      WifiManager 스캔 결과
│   └── scan/CellScanner.kt      기지국 정보
└── GITHUB_WORKFLOW_build-apk.yml  → 저장소 `.github/workflows/build-apk.yml` 로 복사
```

## 한계

- 신호의 **방향**은 알려줄 수 없습니다(폰 한 대의 안테나로는 불가). 제조사·종류·거리 추정으로 알려줍니다.
- 거리는 RSSI 기반 추정값이라 벽·몸 위치에 따라 달라집니다.
- Wi-Fi 스캔은 안드로이드가 2분에 몇 번으로 제한해서, 목록 갱신이 몇 초 늦을 수 있습니다.
- 앱이 화면에 떠 있는 동안만 스캔합니다(백그라운드 상시 감지는 배터리 때문에 넣지 않았습니다).
