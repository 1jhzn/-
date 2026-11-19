import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.jihun.signalradar"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jihun.signalradar"
        minSdk = 26
        targetSdk = 34
        versionCode = 6
        versionName = "1.5"
    }

    // `common/` is the code and the resources both this app and :app-nearby run.
    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/java", "../common/src/main/java")
            res.srcDirs("src/main/res", "../common/src/main/res")
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
