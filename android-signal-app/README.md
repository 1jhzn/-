# 전파 감지기 (Android, Kotlin)

폰 주변의 **블루투스(BLE) · 와이파이 · 기지국** 전파를 페어링 없이 그냥 읽어서,
**평소에 없던 새 신호가 나타나면 제조사·기기 종류·대략 거리로 알려주는** 앱입니다.

- 설치형 앱이라 브라우저처럼 `chrome://flags`를 켜거나 기기 선택(페어링) 창을 거칠 필요가 없습니다.
- 읽은 신호는 폰 밖으로 보내지 않습니다. 인터넷 권한 자체를 쓰지 않습니다.
- 기준 저장(`기준 저장`)을 한 번 눌러 두면, 그다음부터 **처음 잡히는 신호**와 **세기가 확 오른 신호**만 따로 표시합니다.

## v1.2 (2026-11-19)

- **방향이 엉뚱한 곳을 가리키던 문제 수정**: 레이더의 열 지도 호(arc)가 `Canvas.drawArc` 기준(3시 방향 0°)으로 그려지면서 실제 측정 방향보다 **90° 돌아가게** 표시되던 버그를 고쳤습니다. 또 섹터 인덱스는 왼쪽 경계 기준, 호는 중심 기준으로 계산해 **15°씩 어긋나던** 것도 섹터를 0°/30°/60° 중심으로 통일해 맞췄습니다.
- **측정 진행 표시**: `측정 진행: 8/12 방향`처럼 몇 개 방향을 실제로 쟀는지 보여 줍니다. **4곳 이상** 측정되기 전에는 바늘을 그리지 않고, 그 전까지는 신호 세기 변화만 보여 줍니다.
- **부드러운 피크 보간**: 각 섹터를 양옆 섹터와 0.25/0.5/0.25로 섞어 비교하고, 이웃이 없는 외톨이 섹터는 6dB 깎아 평가합니다. 벽에 반사된 한 점의 강한 값이 바늘을 잡아당기지 못하고, 실제로는 위상차 보간(포물선)과 섹터 내 가중 평균으로 30° 격자보다 세밀하게 방위를 계산합니다.
- **와이파이 팁**: 와이파이일 때만 “한 방향마다 2~3초 멈춰 재면 더 정확해요”를 안내합니다(스캔 주기가 느린 특성).
- **내 폰 연결·등록 기기 표시**: `BluetoothAdapter.bondedDevices`와 `getConnectedDevices(GATT, A2DP, HEADSET)`를 15초마다 확인해 목록·상세에 `🔗 내 폰 연결됨` / `📌 내 등록 기기` 배지를 붙입니다. 권한·예외는 모두 감싸서 블루투스 스택이 튀어도 스캔은 계속됩니다.
- **기기가 내뿜는 이름 강조**: `ScanRecord.deviceName`(방송명)과 `BluetoothDevice.name`(폰이 기억한 이름)을 모두 읽어, 방송명이면 `📢 방송명: 지훈의 AirPods`로, 폰이 기억한 이름이면 `📌 폰에 저장된 이름`으로 구분해 보여 줍니다. 이름이 없으면 기존처럼 `(이름 없음)` + 제조사 표시.
- **`🔍 기기 이름 더 읽어오기` 버튼**: 상세 화면에서만, 사용자가 누를 때만 해당 BLE 기기에 8초 한도로 GATT 연결해 `Generic Access(0x1800) → Device Name(0x2A00)`과 `Device Information(0x180A) → Manufacturer Name(0x2A29)`을 읽습니다. 에어팟·최신 폰·추적기는 연결을 거부하는 경우가 많아 실패하면 “이 기기는 보안상 상세 이름 공개를 막아 뒀어요”라고 솔직하게 안내하고, 성공·실패·시간 초과·취소 어느 경우에도 `disconnect()` + `close()`로 연결을 닫습니다.
- 상세 화면은 AlertDialog 대신 전용 카드(`dialog_detail.xml`)로 바뀌어 방송명·배지·GATT 결과·방향 찾기 버튼이 한 화면에 들어갑니다.

## v1.1 (2026-11-19)

- **목록에 예상 거리 표시**: 각 행의 오른쪽 위에 `약 2.4m`처럼 거리가 크게 나오고, 그 아래에 `-58 dBm`과 세기 막대가 붙습니다. 기지국은 거리를 계산하지 않고 `기지국`으로 표시합니다.
- **거리 계산 정확도**: 기기별 RSSI를 EMA(α=0.28)로 평활화하고, BLE는 광고 패킷의 TxPower(1m 기준 세기)를 우선 사용하며, 와이파이는 2.4/5/6GHz 대역별 1m 기준 세기(-40/-46/-49 dBm)와 실내 경로 손실 지수 2.6을 적용합니다.
- **방향 찾기 모드**: 신호를 눌러 `🧭 방향 찾기`를 열면 Canvas로 그린 레이더 나침반에서 폰을 돌릴 때 방향별 최대 세기를 열 지도(Heat Arc)로 보여 주고, 가장 센 방향을 바늘로 가리킵니다. 피크 방향 ±15° 안에 들어오면 진동과 `🎯 이 방향이 가장 강해요!` 표시가 나옵니다.
- 거리는 어디까지나 신호 세기로 계산한 어림값입니다. 벽·가구·사람에 따라 달라집니다.

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
