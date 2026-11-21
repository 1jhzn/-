package com.jihun.signalradar.v2

import com.jihun.signalradar.identify.Classifier
import com.jihun.signalradar.model.Signal

/**
 * v2.9 — 지훈's own measurement: "책상 위 탭이 1m인데 -86 dBm · 예상 약 4.1m".
 *
 * v2.8 wrote its metres from a room reference of -67 dBm with n = 3.1. That was still too far:
 * a Galaxy tablet sitting 0.8–1 m away on the desk read -86 dBm and the finder called it 4.1 m.
 * v2.9 puts the reference where his phone actually is — `-85 dBm` at 1 m — and uses n = 3.0,
 * so the same reading is now `가까움 · 약 1.1m`:
 *
 *  - `-55 … -80 dBm` (in your hand, right beside it): `매우 가까움`, `50cm 이내` … `약 0.7m`
 *  - `-86 dBm` (the tablet on the desk, 0.8–1 m): `가까움 · 약 1.1m`
 *  - `-92 dBm` (across the room): `가까움 · 약 1.7m`
 *  - `-98 dBm` (through a wall): `보통 · 약 2.7m`
 *
 * An advertised 1 m value is still believed only in -78…-48 dBm, and gets the same 26 dB taken
 * off (−59 − 26 = −85), so a device that publishes its own 1 m power and one that does not sit
 * on one scale instead of two ([Classifier.INDOOR_BLE] holds the numbers).
 *
 * The whole app uses this: the list rows and the top card ([V2Look], [V2Distance]), the finder's
 * step word and 📏 예상 line, the detail card, and the 10 m radar's dot positions.
 * 지훈 does not have to tune anything: these are the defaults.
 */
object V2Calibration {

    /** The tuned indoor numbers every v2 screen reads. */
    val INDOOR: Classifier.DistanceTuning = Classifier.INDOOR_BLE

    /** The level a screen should use: the smoothed one when the scanner has it. */
    fun level(signal: Signal): Float = signal.smoothedRssi ?: signal.rssi.toFloat()

    /** `1.2` — the estimate in metres, or null for a base station. */
    fun metres(signal: Signal): Double? = Classifier.distanceMeters(
        level(signal).toDouble(),
        signal.source,
        signal.txPower,
        signal.frequencyMhz,
        INDOOR
    )

    /** `50cm 이내` · `약 1.2m` · `약 5m` · `60m+`, or null for a base station. */
    fun metresText(signal: Signal): String? = Classifier.distanceLabel(
        level(signal),
        signal.source,
        signal.txPower,
        signal.frequencyMhz,
        INDOOR
    )

    /** The step word for a signal: `매우 가까움` … `아주 멀어요`, or null for a base station. */
    fun stepLabel(signal: Signal): String? = stepLabel(level(signal), signal)

    /** The step word for a level of one signal (the finder measures a fresher level than the row). */
    fun stepLabel(rssi: Float, signal: Signal): String? {
        val metres = Classifier.distanceMeters(
            rssi.toDouble(),
            signal.source,
            signal.txPower,
            signal.frequencyMhz,
            INDOOR
        ) ?: return null
        return stepOf(metres)
    }

    /**
     * `매우 가까움` … `아주 멀어요` from a distance, on v2's own edges.
     *
     * The word has to agree with the number beside it: on 지훈's measured scale a level of
     * -98 dBm (through a wall) is 2.7 m, and the shared 3 m edge would still call that
     * `가까움` while the row says 2.7 m. So the near/mid edge sits at 2.2 m here — the words are
     * the same five, and `Classifier.distanceStep` is untouched for the other two apps.
     */
    fun stepOf(metres: Double): String = when {
        metres < 1.0 -> "매우 가까움"
        metres < 2.2 -> "가까움"
        metres < 8.0 -> "보통"
        metres < 20.0 -> "멀어요"
        else -> "아주 멀어요"
    }

    fun stepLabel(rssi: Int, signal: Signal): String? = stepLabel(rssi.toFloat(), signal)

    /** Whether the detail card may call a txPower "1m 기준" — the same range the maths believes. */
    fun isOneMetreTxPower(txPower: Int?): Boolean = INDOOR.isOneMetreTxPower(txPower)
}
