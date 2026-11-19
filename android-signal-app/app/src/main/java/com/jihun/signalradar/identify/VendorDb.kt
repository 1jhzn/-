package com.jihun.signalradar.identify

import android.content.Context
import android.util.Log

/**
 * Manufacturer lookup backed by two public registries shipped in assets:
 *
 *  - oui.csv            : IEEE MA-L assignments (MAC prefix -> organization)
 *  - ble_companies.csv  : Bluetooth SIG company identifiers (0x004C -> Apple, Inc.)
 *
 * The tables are loaded once on a background thread at app start.
 */
object VendorDb {

    private const val TAG = "VendorDb"

    private val ouiTable = HashMap<String, String>(48000)
    private val bleTable = HashMap<Int, String>(4600)

    @Volatile
    private var loaded = false

    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val app = context.applicationContext
            try {
                loadOui(app)
            } catch (t: Throwable) {
                Log.w(TAG, "OUI table load failed", t)
            }
            try {
                loadBle(app)
            } catch (t: Throwable) {
                Log.w(TAG, "BLE company table load failed", t)
            }
            loaded = true
        }
    }

    private fun loadOui(context: Context) {
        context.assets.open("oui.csv").bufferedReader().useLines { lines ->
            lines.forEach { line ->
                if (line.length > 8 && line[6] == ',') {
                    val key = line.substring(0, 6).uppercase()
                    val name = line.substring(7).trim()
                    if (name.isNotEmpty()) ouiTable[key] = name
                }
            }
        }
        Log.i(TAG, "OUI entries: ${ouiTable.size}")
    }

    private fun loadBle(context: Context) {
        context.assets.open("ble_companies.csv").bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val comma = line.indexOf(',')
                if (comma in 4..5) {
                    val id = line.substring(0, comma).toIntOrNull(16) ?: return@forEach
                    val name = line.substring(comma + 1).trim()
                    if (name.isNotEmpty()) bleTable[id] = name
                }
            }
        }
        Log.i(TAG, "BLE company entries: ${bleTable.size}")
    }

    /** Full organization name registered for a MAC address prefix, or null. */
    fun vendorForMac(mac: String?): String? {
        if (mac.isNullOrBlank()) return null
        val clean = mac.replace(":", "").replace("-", "").uppercase()
        if (clean.length < 6) return null
        return ouiTable[clean.substring(0, 6)]
    }

    /** Bluetooth SIG company name for a company identifier, or null. */
    fun vendorForCompany(companyId: Int?): String? {
        if (companyId == null) return null
        return bleTable[companyId]
    }

    /** "Apple, Inc." -> "애플". Unknown vendors keep their registered name. */
    fun koreanName(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val lower = raw.lowercase()
        for ((needle, korean) in KOREAN) {
            if (lower.contains(needle)) return korean
        }
        return cleanName(raw)
    }

    private fun cleanName(raw: String): String {
        var name = raw.replace(Regex("\\s+"), " ").trim()
        name = name.removeSuffix(".")
        if (name.length > 46) name = name.substring(0, 44) + "…"
        return name
    }

    private val KOREAN: List<Pair<String, String>> = listOf(
        "samsung electronics" to "삼성전자",
        "samsung electro" to "삼성전기",
        "samsung sds" to "삼성SDS",
        "samsung" to "삼성",
        "apple" to "애플",
        "lg electronics" to "LG전자",
        "lg innotek" to "LG이노텍",
        "lg " to "LG",
        "google" to "구글",
        "xiaomi" to "샤오미",
        "huawei" to "화웨이",
        "sony ericsson" to "소니에릭슨",
        "sony" to "소니",
        "bose" to "보스",
        "anker" to "앤커",
        "tile" to "타일(Tile)",
        "garmin" to "가민",
        "nordic semiconductor" to "노르딕 반도체",
        "espressif" to "에스프레시프(ESP32)",
        "intel" to "인텔",
        "qualcomm" to "퀄컴",
        "broadcom" to "브로드컴",
        "murata" to "무라타",
        "texas instruments" to "텍사스인스트루먼트",
        "tp-link" to "TP-Link",
        "tp link" to "TP-Link",
        "efm networks" to "ipTIME(EFM)",
        "efmnetworks" to "ipTIME(EFM)",
        "d-link" to "D-Link",
        "netgear" to "넷기어",
        "asustek" to "ASUS",
        "asus" to "ASUS",
        "humax" to "휴맥스",
        "kaon" to "카온",
        "dasan" to "다산네트웍스",
        "hewlett packard" to "HP",
        "hp inc" to "HP",
        "dell" to "델",
        "lenovo" to "레노버",
        "amazon" to "아마존",
        "microsoft" to "마이크로소프트",
        "cisco" to "시스코",
        "ubiquiti" to "유비퀴티",
        "aruba" to "아루바",
        "ruckus" to "러커스",
        "zte" to "ZTE",
        "fiberhome" to "파이버홈",
        "technicolor" to "테크니컬러",
        "sagemcom" to "사젬콤",
        "arcadyan" to "아르카디안",
        "sercomm" to "서컴",
        "gemtek" to "젬텍",
        "totolink" to "TOTOLINK",
        "mercury" to "머큐리",
        "tenda" to "텐다",
        "realtek" to "리얼텍",
        "mediatek" to "미디어텍",
        "oppo" to "오포",
        "vivo" to "비보",
        "oneplus" to "원플러스",
        "motorola" to "모토로라",
        "nokia" to "노키아",
        "panasonic" to "파나소닉",
        "toshiba" to "도시바",
        "sharp" to "샤프",
        "canon" to "캐논",
        "seiko epson" to "엡손",
        "epson" to "엡손",
        "brother" to "브라더",
        "logitech" to "로지텍",
        "razer" to "레이저",
        "gopro" to "고프로",
        "dji" to "DJI",
        "bosch" to "보쉬",
        "philips" to "필립스",
        "signify" to "필립스 휴",
        "ikea" to "이케아",
        "nest" to "네스트",
        "ecobee" to "에코비",
        "roku" to "로쿠",
        "sonos" to "소노스",
        "harman" to "하만(JBL)",
        "jbl" to "JBL",
        "beats" to "비츠",
        "withings" to "위딩스",
        "fitbit" to "핏빗",
        "polar electro" to "폴라",
        "suunto" to "순토",
        "garmin international" to "가민",
        "nintendo" to "닌텐도",
        "valve" to "밸브",
        "tesla" to "테슬라",
        "hyundai" to "현대",
        "kia" to "기아",
        "sk telecom" to "SK텔레콤",
        "kt corporation" to "KT",
        "lg uplus" to "LG유플러스"
    )
}
