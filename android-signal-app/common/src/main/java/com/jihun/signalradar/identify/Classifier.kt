package com.jihun.signalradar.identify

import com.jihun.signalradar.model.SignalSource
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Turns raw radio data into something a person can read: what the device probably is,
 * which band it uses, and roughly how far away it sits.
 */
object Classifier {

    // ---------------------------------------------------------------- Bluetooth

    private const val APPLE = 0x004C

    fun bleKind(companyId: Int?, data: ByteArray?): String? {
        if (companyId == null) return null
        return when (companyId) {
            APPLE -> appleKind(data)
            0x0075 -> "삼성 기기(갤럭시·버즈·워치)"
            0x00E0, 0x018E -> "구글 기기(픽셀·크롬캐스트·패스트페어)"
            0x038F -> "샤오미 기기(폰·밴드·IoT)"
            0x00C4 -> "LG 기기"
            0x027D -> "화웨이 기기"
            0x067C -> "타일(Tile) 분실물 추적기"
            0x0CC2 -> "앤커(사운드코어·이어버드)"
            0x009E -> "보스(Bose) 오디오"
            0x0157 -> "샤오미 밴드(휴미)"
            0x0087 -> "가민 워치·사이클링"
            0x0059 -> "노르딕 칩 기기(IoT·비콘)"
            0x02E5 -> "ESP32 개발보드·IoT 기기"
            0x0006 -> "마이크로소프트 기기"
            0x0171 -> "아마존 기기(에코·파이어TV)"
            0x000F -> "브로드컴 칩 기기"
            0x000D -> "텍사스인스트루먼트 칩 기기"
            0x000A, 0x001D, 0x00D7 -> "퀄컴 칩 기기"
            0x013C -> "무라타 칩 기기"
            0x012D -> "소니 기기"
            0x0056 -> "소니에릭슨 기기"
            0x00B8 -> "퀄컴 칩 기기"
            else -> null
        }
    }

    /**
     * Apple advertises a 1-byte "type" right after its company id, which tells us
     * whether the signal comes from AirPods, an iPhone nearby, or a Find My tracker.
     */
    private fun appleKind(data: ByteArray?): String {
        val type = if (data != null && data.size > 2) data[2].toInt() and 0xFF else -1
        return when (type) {
            0x12 -> "에어태그·분실물 추적기(Find My)"
            0x10 -> "아이폰·아이패드 근접 신호"
            0x0F -> "핸드오프(아이폰·맥·워치)"
            0x0E -> "아이폰 근접 신호"
            0x0D -> "아이폰 근접 신호"
            0x0C -> "핸드오프"
            0x09 -> "에어팟·비츠"
            0x07 -> "에어팟"
            0x05 -> "에어드롭"
            0x02 -> "아이비콘(iBeacon) 비콘"
            else -> "애플 기기(아이폰·에어팟·태그 중 하나)"
        }
    }

    fun bleServiceHint(uuids: List<String>?): String? {
        if (uuids.isNullOrEmpty()) return null
        val found = ArrayList<String>(2)
        for (uuid in uuids) {
            val s = uuid.lowercase()
            val label = when {
                s.startsWith("0000fe9f") -> "패스트페어(구글)"
                s.startsWith("0000feaa") -> "에드스톤 비콘"
                s.startsWith("0000fd6f") -> "노출 알림"
                s.startsWith("0000fe2c") -> "샤오미 서비스"
                s.startsWith("0000180f") -> "배터리"
                s.startsWith("00001812") -> "입력장치(HID)"
                s.startsWith("0000180d") -> "심박수"
                s.startsWith("0000fe9a") -> "애플 서비스"
                else -> null
            }
            if (label != null && !found.contains(label)) found.add(label)
        }
        if (found.isEmpty()) return null
        return found.joinToString(", ")
    }

    // ------------------------------------------------------------------ Wi-Fi

    fun wifiKind(ssid: String, vendor: String?): String? {
        val s = ssid.uppercase()
        if (ssid.isBlank() || s == "<UNKNOWN SSID>") return "숨김 SSID(이름 감춘 공유기)"

        val byName = when {
            s.contains("IPTIME") || s.contains("IP_TIME") -> "ipTIME 공유기"
            s.contains("MYLGNET") || s.contains("MYLG") || s.contains("U+NET") ||
                s.contains("U+_") || s.contains("LG_UPLUS") || s.contains("UPLUS") ->
                "LG유플러스 공유기"
            s.contains("SKB") || s.contains("SK_WIFI") || s.contains("SKT_WIFI") ->
                "SK브로드밴드 공유기"
            s.contains("KT_GIGA") || s.contains("KT_WLAN") || s.contains("KT_WIFI") ||
                s.contains("OLLEH") -> "KT 공유기"
            s.contains("IPHONE") -> "아이폰 핫스팟"
            s.contains("SM-") || s.contains("GALAXY") || s.contains("갤럭시") -> "갤럭시 핫스팟"
            s.contains("DIRECT-") -> "기기 직결(Wi-Fi Direct)"
            s.contains("TP-LINK") || s.contains("TPLINK") -> "TP-Link 공유기"
            s.contains("TOTOLINK") -> "TOTOLINK 공유기"
            s.contains("CCTV") || s.contains("IPCAM") || s.contains("IP CAM") -> "CCTV·IP카메라"
            s.contains("PRINTER") || s.contains("EPSON") || s.contains("BROTHER") -> "프린터"
            s.contains("TESLA") -> "테슬라 차량"
            else -> null
        }
        if (byName != null) return byName

        val v = vendor?.lowercase() ?: return null
        return when {
            v.contains("apple") -> "애플 기기(맥·아이폰 핫스팟)"
            v.contains("samsung") -> "삼성 기기(폰·TV·가전)"
            v.contains("efm networks") -> "ipTIME 공유기"
            v.contains("tp-link") -> "TP-Link 공유기"
            v.contains("lg electronics") -> "LG 기기"
            else -> null
        }
    }

    fun wifiSecurity(capabilities: String): String = when {
        capabilities.contains("WPA3") -> "WPA3"
        capabilities.contains("WPA2") -> "WPA2"
        capabilities.contains("WPA") -> "WPA"
        capabilities.contains("WEP") -> "WEP(구형·취약)"
        else -> "개방(비밀번호 없음)"
    }

    fun bandOf(frequencyMhz: Int): String = when {
        frequencyMhz in 2400..2500 -> "2.4GHz"
        frequencyMhz in 4900..5930 -> "5GHz"
        frequencyMhz > 5930 -> "6GHz"
        else -> "${frequencyMhz}MHz"
    }

    fun channelOf(frequencyMhz: Int): Int? = when {
        frequencyMhz == 2484 -> 14
        frequencyMhz in 2412..2472 -> (frequencyMhz - 2407) / 5
        frequencyMhz in 5000..5900 -> (frequencyMhz - 5000) / 5
        frequencyMhz >= 5955 -> (frequencyMhz - 5950) / 5
        else -> null
    }

    // ------------------------------------------------------------------ Cells

    fun carrierFromMccMnc(mcc: String?, mnc: String?): String? {
        if (mcc.isNullOrBlank() || mnc.isNullOrBlank()) return null
        return when (mcc + mnc) {
            "45005" -> "SK텔레콤"
            "45008" -> "KT"
            "45006" -> "LG유플러스"
            else -> null
        }
    }

    fun lteBandLabel(bands: IntArray?): String? {
        val band = bands?.firstOrNull() ?: return null
        if (band <= 0) return null
        return "B$band"
    }

    // ------------------------------------------------------- Strength / range

    /**
     * Rough distance in metres from an RSSI reading, using the log-distance path loss model.
     *
     * Bluetooth: the advertisement often carries the transmitter's own "how strong am I at
     * 1 m" value; when it does, that is far better than a generic guess, so it wins.
     * Wi-Fi: each band starts from a different level at 1 m and 5/6 GHz loses more on the way.
     *
     * Both exponents are indoor values (지훈's rooms: walls, furniture, his own body in the
     * path). Free space (n = 2) made a router in the next room read as 14 m; n = 2.8–2.9
     * shrinks that to a believable distance and, because the exponent divides the dB spread,
     * also stops the number jittering as much. Phone antennas and walls still make this an
     * estimate, not a measurement.
     */
    fun distanceMeters(rssi: Int, source: SignalSource): Double? =
        distanceMeters(rssi.toDouble(), source, null, null)

    fun distanceMeters(
        rssi: Double,
        source: SignalSource,
        txPower: Int? = null,
        frequencyMhz: Int? = null,
        tuning: DistanceTuning = DEFAULT_TUNING
    ): Double? {
        val (reference, exponent) = when (source) {
            SignalSource.BLE ->
                (txPower?.takeIf { tuning.isOneMetreTxPower(it) }?.toDouble()
                    ?.minus(tuning.txPowerIndoorDropDb) ?: tuning.bleReference1m) to
                    tuning.bleExponent
            SignalSource.WIFI -> wifiReference(frequencyMhz) to WIFI_EXPONENT
            SignalSource.CELL -> return null
        }
        val metres = 10.0.pow((reference - rssi) / (10.0 * exponent))
        return metres.coerceIn(0.02, 500.0)
    }

    /**
     * One set of knobs for the Bluetooth distance estimate, so a caller can use its own
     * room measurements without moving the defaults the older screens and their tests rely on.
     *
     * [txPowerIndoorDropDb] is how far the room scale sits below a device's own advertised
     * free-space 1 m value (walls, furniture, the phone's own case and the hand holding it).
     * A device that advertises its free-space 1 m power gets the same offset taken off, so a
     * "−59 dBm at 1 m" advertisement and the room reference land on one model instead of two
     * scales. v2.8 used 8 dB for its -67 dBm scale; v2.9 uses 26 dB for 지훈's measured
     * -85 dBm scale, which is the same anchor (−59 − 26 = −85).
     */
    data class DistanceTuning(
        val bleReference1m: Double = BLE_REF_1M,
        val bleExponent: Double = BLE_EXPONENT,
        val txPowerMin: Int = TX_POWER_MIN,
        val txPowerMax: Int = TX_POWER_MAX,
        val txPowerIndoorDropDb: Double = 0.0
    ) {
        fun isOneMetreTxPower(txPower: Int?): Boolean =
            txPower != null && txPower in txPowerMin..txPowerMax
    }

    /**
     * v2.9 (지훈's own measurement): the level his phone really shows for a device he can reach.
     *
     * He put a Galaxy tablet on the desk, about 0.8–1 m away, and the v2.8 finder wrote
     * `-86 dBm · 보통 · 약 4.1m`. So in *his* room 1 m is `-85 dBm`, not `-67`:
     *
     *  - `-85 dBm` at 1 m (the measured level, one dB above the 0.8–1 m reading he sent).
     *  - `n = 3.0`: indoor walls and bodies make the level fall about 30 dB per tenfold distance,
     *    so `-86` lands at ~1.1 m, `-92` at ~1.7 m, `-98` at ~2.7 m.
     *  - An advertised 1 m value is still only believed in `-78…-48 dBm`: devices that put their
     *    own antenna output in that field (0 m values like -30…-45, or +12) are not 1 m levels.
     *  - `txPowerIndoorDropDb = 26.0` keeps the two paths on this one scale. A device advertising
     *    the usual `-59 dBm` at 1 m is the same device the room sees at `-85` (−59 − 26); without
     *    that offset an advertised value would still be read on the old free-space scale and
     *    land metres away from the room reading next to it.
     *
     * Every v2 screen reads this one tuning, so the list, the top card, the finder, the detail
     * card and the 10 m radar cannot disagree.
     */
    val INDOOR_BLE = DistanceTuning(
        bleReference1m = -85.0,
        bleExponent = 3.0,
        txPowerMin = -78,
        txPowerMax = -48,
        txPowerIndoorDropDb = 26.0
    )

    /** Level a Wi-Fi router of this band should produce at 1 m. */
    private fun wifiReference(frequencyMhz: Int?): Double = when {
        frequencyMhz == null -> -43.0
        frequencyMhz in 2400..2500 -> -40.0
        frequencyMhz in 4900..5930 -> -46.0
        frequencyMhz > 5930 -> -49.0
        else -> -43.0
    }

    /**
     * Short label for the list row: under a metre to a tenth, one decimal up to 10 m,
     * whole metres beyond that, and a blunt "60m+" when the guess stops being useful.
     * Cells return null (a base station's level says nothing about its distance).
     */
    fun distanceLabel(
        rssi: Int,
        source: SignalSource,
        txPower: Int? = null,
        frequencyMhz: Int? = null,
        tuning: DistanceTuning = DEFAULT_TUNING
    ): String? = distanceLabel(rssi.toFloat(), source, txPower, frequencyMhz, tuning)

    fun distanceLabel(
        rssi: Float,
        source: SignalSource,
        txPower: Int? = null,
        frequencyMhz: Int? = null,
        tuning: DistanceTuning = DEFAULT_TUNING
    ): String? {
        val metres = distanceMeters(rssi.toDouble(), source, txPower, frequencyMhz, tuning)
            ?: return null
        return when {
            metres < 0.5 -> "50cm 이내"
            metres < 10.0 -> "약 ${oneDecimal(metres)}m"
            metres < 60.0 -> "약 ${metres.roundToInt()}m"
            else -> "60m+"
        }
    }

    /**
     * The same estimate as [distanceLabel], written as a step instead of metres.
     *
     * 지훈 asked for the decimal metres to go away ("거리 약 1.7m 이런 소수점 버리고 … 단계로"):
     * "약 1.7m" reads like a measurement, while the number behind it is only an estimate that
     * moves with every packet. A step says what a person actually wants to know — is this
     * thing next to me or in the next room. Cells still return null.
     */
    fun distanceStepLabel(
        rssi: Int,
        source: SignalSource,
        txPower: Int? = null,
        frequencyMhz: Int? = null,
        tuning: DistanceTuning = DEFAULT_TUNING
    ): String? = distanceStepLabel(rssi.toFloat(), source, txPower, frequencyMhz, tuning)

    fun distanceStepLabel(
        rssi: Float,
        source: SignalSource,
        txPower: Int? = null,
        frequencyMhz: Int? = null,
        tuning: DistanceTuning = DEFAULT_TUNING
    ): String? {
        val metres = distanceMeters(rssi.toDouble(), source, txPower, frequencyMhz, tuning)
            ?: return null
        return distanceStep(metres)
    }

    /**
     * Five steps, no decimal point: under 1 m, 3 m, 8 m, 20 m, then everything beyond.
     *
     * The boundaries sit where the estimate is still trustworthy: below 1 m a Bluetooth
     * device is effectively in your hand, and past 20 m the reading says little more than
     * "far away", so a sixth step would be a made-up precision.
     */
    fun distanceStep(metres: Double): String = when {
        metres < 1.0 -> "매우 가까움"
        metres < 3.0 -> "가까움"
        metres < 8.0 -> "보통"
        metres < 20.0 -> "멀어요"
        else -> "아주 멀어요"
    }

    /**
     * Whether an advertised txPower can serve as the "how strong am I at 1 m" level.
     *
     * Only a negative RSSI-like value in [-100, -20] dBm is that. Apple and some other
     * chips put the antenna's own output power in the same field instead (+12 dBm is a
     * typical one), and reading that as a 1 m reference turned a nearby iPhone into "60m+".
     */
    fun isOneMetreTxPower(txPower: Int?): Boolean =
        txPower != null && txPower in TX_POWER_MIN..TX_POWER_MAX

    /** 0.42 -> "0.4", 2.44 -> "2.4", 3.0 -> "3". */
    private fun oneDecimal(value: Double): String {
        val tenths = (value * 10).roundToInt()
        return if (tenths % 10 == 0) "${tenths / 10}" else "${tenths / 10}.${tenths % 10}"
    }

    /** 0..100 for the little strength bar. */
    fun strengthPercent(rssi: Int, source: SignalSource): Int {
        val (weak, strong) = when (source) {
            SignalSource.BLE -> -95 to -55
            SignalSource.WIFI -> -92 to -45
            SignalSource.CELL -> -120 to -80
        }
        val ratio = (rssi - weak).toFloat() / (strong - weak).toFloat()
        return (ratio.coerceIn(0f, 1f) * 100f).toInt()
    }

    private val DEFAULT_TUNING = DistanceTuning()

    private const val BLE_REF_1M = -59.0

    /** Indoor path loss exponent for Bluetooth: 2.8, not the free-space 2. */
    private const val BLE_EXPONENT = 2.8

    /** Indoor path loss exponent for Wi-Fi: 2.9 (5/6 GHz lose a little more on the way). */
    private const val WIFI_EXPONENT = 2.9
    private const val TX_POWER_MIN = -100
    private const val TX_POWER_MAX = -20
}
