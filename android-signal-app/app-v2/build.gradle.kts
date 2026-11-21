import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * 전파 감지기 v2 — one separately installable APK, `com.jihun.signalradar.v2`.
 *
 * It is a *new* app, not an update: the package differs from both 전파 감지기
 * (`com.jihun.signalradar`) and 전파 감지기 (내 주변) (`com.jihun.signalradar.nearby`), so the
 * phone keeps all three and nothing the user already has is replaced. The version starts at
 * 1 / "2.0" because this line of the app is new.
 *
 * v2.2 adds the 기준 tab's list: several named baselines, one in use, each renameable and
 * deletable, plus `🧹 기준 초기화` (see [com.jihun.signalradar.v2.V2BaselineStore]).
 *
 * v2.3 answers two more requests from 지훈 ("와이파이랑 기지국 안보게해주고 가까워질수록소리크게해줘",
 * then "진동만 크게해줘"): the screen is Bluetooth only — Wi-Fi and base stations are neither
 * scanned nor drawn anywhere — and the finder buzzes harder and faster the closer the signal
 * is, with its own 진동 switch, instead of playing a sound.
 *
 * v2.6 answers "방향다시 위치와 기기상세위치 바꿔줘": the finder's bottom row now reads
 * 방향 다시 — 기기 상세 — 📏 예상 거리 (the first two swapped), with nothing else changed.
 *
 * v2.7 does two things 지훈 asked for:
 *
 *  - "오픈소스라이선스로 해줄수있어?": a `📄 오픈소스 라이선스` card (the header's ⓘ on any tab,
 *    and a named button at the bottom of the 기준 tab) with the app version, the copyright,
 *    MIT and what the APK carries ([V2Licenses], `dialog_license_v2.xml`).
 *  - "스캔시작을 하고 … 중지를 하고 방향을 찾고 다시 나가면 스캔이 시작되어 있어서 좀 불편해":
 *    opening the finder no longer starts the scan behind 지훈's back, and 닫기 leaves the scan
 *    switch exactly as he left it. The finder has its own ▶ 스캔 / ■ 중지 pill instead.
 *
 * v2.10 answers 지훈's report that ordinary devices were being called near: "내 근처 50cm 안에
 * 없는데 있다고 나와". v2.9's -85 dBm at 1 m reference made every usual -59…-67 dBm device
 * 1–3 m away read under half a metre. v2.10 moves the reference to -74 dBm at 1 m (n = 3.0, an
 * advertised 1 m value 15 dB below its own number), so `50cm 이내` needs about -64.9 dBm or
 * stronger, -68 dBm is `가까움 · 약 1.6m`, and -86 dBm is `보통 · 약 2.5m`. The finder's taps
 * moved with it (`-99 / -92 / -83 / -74 / -65` dBm), so 5/5 still means truly beside the phone.
 * The numbers live in [com.jihun.signalradar.v2.V2Calibration] /
 * [com.jihun.signalradar.identify.Classifier.INDOOR_BLE]; nothing to tune by hand.
 *
 * v2.9 answers 지훈's own measurement ("책상 위 탭이 1m인데 -86 dBm · 약 4.1m"): the reference
 * moves to -85 dBm at 1 m with n = 3.0, so -86 dBm is `가까움 · 약 1.1m` on every screen
 * (list rows, top card, finder, detail card, 10 m radar) and an advertised 1 m value keeps the
 * same offset. The numbers live in [com.jihun.signalradar.v2.V2Calibration] /
 * [com.jihun.signalradar.identify.Classifier.INDOOR_BLE]; nothing to tune by hand.
 *
 * v2.8 answers "전파 2.7 거리가 너무 안 맞아" → "멀어": the Bluetooth estimate now uses the
 * tuned indoor model in [com.jihun.signalradar.v2.V2Calibration] — -67 dBm at 1 m, n = 3.1, and
 * an advertised 1 m value believed only in -78…-48 dBm — on every screen (list rows, top card,
 * finder, detail card, 10 m radar). There is no setting to turn: it is simply the default.
 *
 * v2.5 answers "앱이름을 전파로 바꿔줘": the launcher label and the header title are both just
 * `전파` now (`app_name_v2`), while the package, the icon and every screen stay the same, so it
 * installs over v2.4 without uninstalling it.
 *
 * v2.4 answers "거리도 버튼을 누르면 예상을 볼수있게 해줘": a `📏 예상 거리` switch (the second
 * chip row, a finder button and a pill on the detail card) writes the estimated metres beside
 * every step — `매우 가까움 · 50cm 이내` — in the list, the top card, the finder and the detail
 * card, and remembers the choice. Off by default, and a row's distance pill is its own tap
 * target, so the list reads as steps until 지훈 asks for numbers. See
 * [com.jihun.signalradar.v2.V2Distance].
 *
 * Both tabs are here in one app (📏 거리 + 📡 내 주변 10m 레이더), and the UI is the v2 pass:
 * the empty state sits at the top of the list, the scan button reads 스캔 중지 while scanning,
 * the filter chips wrap onto two rows instead of being cut off, a card is a title plus one
 * summary line with no MAC address, and distances are steps (매우 가까움) instead of "약 1.7m".
 *
 * The scanning, identifying and radar code is not copied: `common/` is shared with the other
 * two apps exactly like before, and the v2 screens only choose their own layouts and wording
 * through the hooks in [com.jihun.signalradar.SignalRadarActivity].
 */
android {
    namespace = "com.jihun.signalradar"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jihun.signalradar.v2"
        minSdk = 26
        targetSdk = 34
        versionCode = 11
        versionName = "2.10"
    }

    sourceSets {
        getByName("main") {
            // The shared screens, scanners and classifier, plus app-nearby's radar view and
            // its activity (the v2 activity extends it for the 두 탭 layout).
            java.srcDirs("src/main/java", "../common/src/main/java", "../app-nearby/src/main/java")
            // The v2 layouts live here; the unchanged colours, drawables, themes and the
            // radar assets come from the same two directories the other apps use.
            res.srcDirs("src/main/res", "../common/src/main/res", "../app-nearby/src/main/res")
            assets.srcDirs("../common/src/main/assets")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // The vendor tables (IEEE OUI, Bluetooth SIG company IDs) are read from assets.
    androidResources {
        noCompress += listOf("csv")
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { test ->
            // Robolectric caches its Android runtime under user.home. In sandboxes where
            // that path is not writable, point it at the module build directory instead.
            val home = System.getProperty("user.home").orEmpty()
            if (home.isEmpty() || !File(home).canWrite()) {
                val fallback = layout.buildDirectory.dir("robolectric-home").get().asFile
                fallback.mkdirs()
                test.systemProperty("user.home", fallback.absolutePath)
            }
            // The system /tmp is a small tmpfs here; the graphics runtime unpacks fonts.
            val tmp = layout.buildDirectory.dir("tmp/test-tmp").get().asFile
            tmp.mkdirs()
            test.systemProperty("java.io.tmpdir", tmp.absolutePath)
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.12.2")
    testImplementation("androidx.test:core:1.5.0")
}
