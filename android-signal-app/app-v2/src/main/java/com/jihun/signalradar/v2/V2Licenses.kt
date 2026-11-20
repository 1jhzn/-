package com.jihun.signalradar.v2

/**
 * v2.7 — the words on the `📄 오픈소스 라이선스` card (지훈: "오픈소스라이선스로 해줄수있어?").
 *
 * The card answers three things at once and nothing else:
 *
 *  - who made this app: `전파 v2.7` / [COPYRIGHT] — the version comes from the installed
 *    package, so this line can never disagree with the APK it is drawn in;
 *  - under what licence it is shared: [LICENSE_NAME], a plain-Korean [SUMMARY] of what that
 *    means for a person who wants to copy the code, and the untouched English [MIT_TEXT];
 *  - what else the APK carries: only the libraries and the two data tables that are really
 *    compiled into or bundled with it ([LIBRARIES]), with each one's licence.
 *
 * Nothing is listed that the app does not ship. The test libraries (JUnit, Robolectric) stay
 * out because they are never in the APK, and Material Components stays out because this app
 * is built on AndroidX + AppCompat only — a licence card that names a library nobody linked
 * would be a wrong answer to the same question.
 */
object V2Licenses {

    /** The line under the app version. 지훈's own name and handle, spelled as on GitHub. */
    const val COPYRIGHT = "Copyright © 2026 이지훈 (@1jhzn)"

    const val LICENSE_NAME = "MIT License"

    /**
     * `전파 v2.7` — the launcher name plus the installed version name. A blank or missing
     * version name (a stripped debug build) still reads as `전파`, never `전파 vnull`.
     */
    fun appTitle(versionName: String?): String {
        val version = versionName?.trim().orEmpty()
        return if (version.isEmpty()) APP_NAME else "$APP_NAME v$version"
    }

    const val APP_NAME = "전파"

    /** What MIT means, in the words a middle-school reader can use. */
    const val SUMMARY =
        "이 앱의 코드는 누구나 자유롭게 쓸 수 있어요 — 복사해서 쓰거나, 고치거나, 다른 사람에게 " +
            "나눠 줘도 되고, 돈을 받고 팔아도 됩니다. 대신 두 가지는 꼭 지켜 주세요. " +
            "① 이 저작권 표시와 허가 문구를 지우지 말고 함께 남길 것, " +
            "② 이 앱은 아무 보증도 없이 ‘있는 그대로’ 나눈다는 것."

    /**
     * The libraries and the two tables that really travel inside the APK. Order: AndroidX,
     * Kotlin, then the data. Each line names the project, the part this app uses and the
     * licence it comes under.
     */
    val LIBRARIES: List<Library> = listOf(
        Library(
            name = "AndroidX",
            detail = "core-ktx · appcompat · activity-ktx · recyclerview",
            license = "Apache License 2.0"
        ),
        Library(
            name = "Kotlin Standard Library",
            detail = "앱 코드를 돌리는 기본 라이브러리",
            license = "Apache License 2.0"
        ),
        Library(
            name = "IEEE OUI 등록부",
            detail = "oui.csv · MAC 주소로 제조사 이름 찾기",
            license = "IEEE 공개 등록 데이터"
        ),
        Library(
            name = "Bluetooth SIG Assigned Numbers",
            detail = "ble_companies.csv · 회사 ID로 회사 이름 찾기",
            license = "Bluetooth SIG 공개 목록"
        )
    )

    /** `· AndroidX — core-ktx … (Apache License 2.0)` one line per entry, for the card. */
    fun libraryLines(): String = LIBRARIES.joinToString("\n") { library ->
        "· ${library.name} — ${library.detail} (${library.license})"
    }

    /** The MIT licence exactly as the OSI and the repository's `LICENSE` file spell it. */
    const val MIT_TEXT = """MIT License

Copyright (c) 2026 이지훈 (1jhzn)

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE."""

    /** One bundled library or data table, as the licence card writes it. */
    data class Library(val name: String, val detail: String, val license: String)
}
