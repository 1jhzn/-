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
        versionCode = 1
        versionName = "2.0"
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
