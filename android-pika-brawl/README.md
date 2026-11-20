# 피카츄 브롤 배구 (Android APK)

`com.jihun.pikabrawl` · versionCode **1** · versionName **"1.0"** · 이름 `피카츄 브롤 배구`
minSdk 26 / targetSdk 34 / compileSdk 34 · 권한 `INTERNET`(온라인 대전용) + `ACCESS_NETWORK_STATE`

## 구조
- **WebView + 앱 내 번들**: `app/src/main/assets/www/` 에 웹 버전(`omok-game/pika-brawl/`)의
  `index.html`, `style.css`, `brawlers.js`, `game.js`, `net.js`, `paho-mqtt.js` 를 그대로 복사해 넣는다.
  인터넷이 없어도 바로 실행되고, 폰의 크롬 캐시와 무관하게 항상 같은 버전이 뜬다.
- **가상 호스팅**: `https://appassets.androidplatform.net/assets/www/index.html`.
  `WebViewClient.shouldInterceptRequest` 가 이 주소를 `assets/www/...` 로 매핑한다.
  (`file://` 대신 https 오리진을 쓰면 `localStorage`(트로피/승리/연승 저장)와 `wss://` WebSocket이
  브라우저와 똑같이 동작한다.) 실패하면 `file:///android_asset/www/index.html` 로 자동 대체.
- 몰입형 전체화면(상태바·내비바 숨김), 화면 회전은 `configChanges` 로 WebView 재시작 없이 처리,
  뒤로가기 **두 번** = 종료, 화면 꺼짐 방지.

## 빌드
```sh
. /workspace/.toolchain/env.sh
cd android-pika-brawl
./gradlew :app:assembleDebug :app:lintDebug --offline
# 산출물: app/build/outputs/apk/debug/app-debug.apk
apksigner verify app/build/outputs/apk/debug/app-debug.apk
```
배포본은 `pika-brawl/pika-brawl.apk` (https://1jhzn.github.io/-/pika-brawl/pika-brawl.apk) 와
`downloads/피카츄브롤배구-v1.0.apk` 에 같은 파일로 둔다.
