package com.jihun.signalradar.v2

import com.jihun.signalradar.identify.Classifier
import com.jihun.signalradar.model.Signal

/**
 * v2.8 — 지훈: "전파 2.7 거리가 너무 안 맞아" → "멀어".
 *
 * The metres v2.7 wrote were the shared free-space estimate: -59 dBm at 1 m with n = 2.8. A
 * phone in a room never sees that: the hand, the case, the walls and the furniture take their
 * cut before the signal has travelled anywhere, so a pair of earbuds on the desk reads -50 and
 * a device across the room reads -80 — and the old formula turned those into 1.8 m and 5.6 m.
 * Every v2 screen said "멀어" for things that were right there.
 *
 * This is the one place that decides the numbers the v2 screens write, and they use the tuned
 * indoor model ([Classifier.INDOOR_BLE]) instead of the textbook one:
 *
 *  - `-67 dBm` at 1 m, `n = 3.1`, and an advertised 1 m value is believed only in
 *    `-78…-48 dBm` (a device that puts its 0 m output in that field used to be read as a weak
 *    1 m value and pushed several metres too far).
 *  - Both paths — the room reference and a device's own advertised value — get the same 8 dB
 *    of indoor loss taken off, so they stay on one scale.
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
    fun stepLabel(rssi: Float, signal: Signal): String? = Classifier.distanceStepLabel(
        rssi,
        signal.source,
        signal.txPower,
        signal.frequencyMhz,
        INDOOR
    )

    fun stepLabel(rssi: Int, signal: Signal): String? = stepLabel(rssi.toFloat(), signal)

    /** Whether the detail card may call a txPower "1m 기준" — the same range the maths believes. */
    fun isOneMetreTxPower(txPower: Int?): Boolean = INDOOR.isOneMetreTxPower(txPower)
}
