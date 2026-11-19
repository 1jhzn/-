package com.jihun.signalradar.identify

import com.jihun.signalradar.model.Signal
import com.jihun.signalradar.model.SignalSource

/**
 * A short, human name for "what kind of thing is this?".
 *
 * The full guess lives in [Signal.kind] ("삼성 기기(갤럭시·버즈·워치)"), which is the right
 * amount of detail for the detail card but far too long to sit next to a dot on a radar.
 * This trims it to something that fits in a few characters ("버즈") and pairs it with an
 * icon, so the 10 m radar can be read at a glance.
 *
 * Everything here is a guess from the broadcast data — [Signal.kind] is already worded that
 * way, and the radar labels it as 추정.
 *
 * Pure Kotlin: no Android types.
 */
object DeviceType {

    data class Info(val label: String, val icon: String)

    private val UNKNOWN_BLE = Info("블루투스 기기", "📶")
    private val UNKNOWN_WIFI = Info("와이파이", "📡")

    fun of(signal: Signal): Info = of(
        signal.source,
        signal.kind,
        signal.title,
        signal.vendor,
        signal.broadcastName
    )

    fun of(
        source: SignalSource,
        kind: String?,
        title: String? = null,
        vendor: String? = null,
        broadcastName: String? = null
    ): Info = when (source) {
        SignalSource.CELL -> Info("기지국", "🗼")
        SignalSource.WIFI -> wifi(kind, title, vendor)
        SignalSource.BLE -> ble(kind, title, vendor, broadcastName)
    }

    private val wifiRules = listOf(
        "핫스팟" to Info("핫스팟", "📱"),
        "공유기" to Info("공유기", "📶"),
        "cctv" to Info("카메라", "📷"),
        "카메라" to Info("카메라", "📷"),
        "프린터" to Info("프린터", "🖨️"),
        "차량" to Info("차량", "🚗"),
        "테슬라" to Info("차량", "🚗"),
        "직결" to Info("기기 직결", "🔗"),
        "숨김" to Info("숨김 공유기", "🕶️"),
        "티브이" to Info("TV", "📺")
    )

    private val bleRules = listOf(
        "에어팟" to Info("에어팟", "🎧"),
        "airpods" to Info("에어팟", "🎧"),
        "버즈" to Info("버즈", "🎧"),
        "이어버드" to Info("이어버드", "🎧"),
        "이어폰" to Info("이어폰", "🎧"),
        "오디오" to Info("오디오", "🔊"),
        "에어태그" to Info("추적기", "🏷️"),
        "추적기" to Info("추적기", "🏷️"),
        "타일" to Info("추적기", "🏷️"),
        "워치" to Info("워치", "⌚"),
        "밴드" to Info("밴드", "⌚"),
        "아이폰" to Info("아이폰", "📱"),
        "아이패드" to Info("아이패드", "📱"),
        "갤럭시" to Info("갤럭시", "📱"),
        "픽셀" to Info("픽셀", "📱"),
        "스마트폰" to Info("스마트폰", "📱"),
        "크롬캐스트" to Info("크롬캐스트", "📺"),
        "에코" to Info("에코", "🔊"),
        "비콘" to Info("비콘", "📍"),
        "입력장치" to Info("입력기기", "⌨️"),
        "심박" to Info("심박 센서", "❤️"),
        "esp32" to Info("개발보드", "🔧"),
        "iot" to Info("IoT 기기", "🔧"),
        "노트북" to Info("노트북", "💻"),
        "맥" to Info("맥", "💻")
    )

    private fun wifi(kind: String?, title: String?, vendor: String?): Info {
        val haystack = listOfNotNull(kind, title, vendor).joinToString(" ").lowercase()
        if (haystack.isBlank()) return UNKNOWN_WIFI
        for ((keyword, info) in wifiRules) {
            if (haystack.contains(keyword)) return info
        }
        if (haystack.contains("애플")) return Info("애플 기기", "🍎")
        if (haystack.contains("삼성") || haystack.contains("lg")) return Info("가전·폰", "📱")
        return UNKNOWN_WIFI
    }

    private fun ble(
        kind: String?,
        title: String?,
        vendor: String?,
        broadcastName: String?
    ): Info {
        val haystack = listOfNotNull(kind, title, vendor, broadcastName)
            .joinToString(" ")
            .lowercase()
        if (haystack.isBlank()) return UNKNOWN_BLE
        for ((keyword, info) in bleRules) {
            if (haystack.contains(keyword)) return info
        }
        if (haystack.contains("애플")) return Info("애플 기기", "🍎")
        if (haystack.contains("삼성")) return Info("삼성 기기", "📱")
        if (haystack.contains("구글")) return Info("구글 기기", "📱")
        return UNKNOWN_BLE
    }
}
