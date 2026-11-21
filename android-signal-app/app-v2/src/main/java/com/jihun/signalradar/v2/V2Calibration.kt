package com.jihun.signalradar.v2

import com.jihun.signalradar.identify.Classifier
import com.jihun.signalradar.model.Signal

/**
 * v2.10 — "내 근처 50cm 안에 없는데 있다고 나와": the middle of v2.8 and v2.9.
 *
 * v2.9 had put the room reference at `-85 dBm` at 1 m, the level his phone showed for a tablet
 * on the desk. That made a metre a metre, but ordinary devices 1–3 m away — a usual -59…-67 dBm
 * output heard at -68…-76 dBm — came out under half a metre, so the list said `50cm 이내` about
 * things that were not beside the phone at all.
 *
 * v2.10 uses `-74 dBm` at 1 m with n = 3.0, so `50cm 이내` needs about `-64.9 dBm` or stronger
 * — a device truly in his hand:
 *
 *  - `-64 dBm` and up (in your hand): `매우 가까움 · 50cm 이내`
 *  - `-68 dBm` (a metre and a half): `가까움 · 약 1.6m`, not `50cm 이내`
 *  - `-74 dBm`: `가까움 · 약 1m`
 *  - `-80 dBm`: `가까움 · 약 1.6m`
 *  - `-86 dBm`: `보통 · 약 2.5m`
 *
 * An advertised 1 m value is still believed only in -78…-48 dBm, and gets the same 15 dB taken
 * off (−59 − 15 = −74), so a device that publishes its own 1 m power and one that does not sit
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
