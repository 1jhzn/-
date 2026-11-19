pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "SignalRadar"
include(":app")
// The second, separately installable APK (package com.jihun.signalradar.nearby). It shares
// the scanning code with :app through source directories instead of a copy.
include(":app-nearby")
// 전파 감지기 v2: one separate APK (com.jihun.signalradar.v2) that installs next to the two
// apps above and never replaces them. It runs the same scanning code and carries the v2 UI.
include(":app-v2")
