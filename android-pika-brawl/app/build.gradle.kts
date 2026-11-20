// 피카츄 브롤 배구 — 앱 모듈 (외부 라이브러리 없이 WebView + 로컬 assets)
plugins {
    id("com.android.application")
}

android {
    namespace = "com.jihun.pikabrawl"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jihun.pikabrawl"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // 게임 파일은 압축 없이 그대로 담는다(WebView가 바로 읽도록).
    androidResources {
        noCompress += listOf("html", "js", "css", "apk")
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }
}
