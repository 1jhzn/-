package com.jihun.signalradar.v2

import com.jihun.signalradar.R
import com.jihun.signalradar.identify.Classifier
import com.jihun.signalradar.model.Signal
import com.jihun.signalradar.model.SignalSource

/**
 * The colours and wording the v2.1 Find-My style screens share.
 *
 * One place decides what a distance step looks like (a pale pill with the step in its own
 * colour) and how a radio is drawn (a deep circle with a white glyph), so the list, the
 * top callout and the detail card never disagree.
 */
object V2Look {

    /** A step's pill: its background and the colour of the word inside it. */
    data class StepStyle(val background: Int, val textColor: Int)

    const val VERY_NEAR = "매우 가까움"
    const val NEAR = "가까움"
    const val MID = "보통"
    const val FAR = "멀어요"
    const val VERY_FAR = "아주 멀어요"

    fun stepStyle(label: String?): StepStyle = when (label) {
        VERY_NEAR -> StepStyle(R.drawable.bg_v2_step_very, R.color.v2_emerald_deep)
        NEAR -> StepStyle(R.drawable.bg_v2_step_near, R.color.v2_blue_deep)
        MID -> StepStyle(R.drawable.bg_v2_step_mid, R.color.v2_amber)
        FAR -> StepStyle(R.drawable.bg_v2_step_far, R.color.v2_ink_soft)
        VERY_FAR -> StepStyle(R.drawable.bg_v2_step_far2, R.color.v2_ink_faint)
        // A base station has no distance worth writing, so it gets its own quiet pill.
        else -> StepStyle(R.drawable.bg_v2_step_far2, R.color.v2_icon_cell)
    }

    fun iconFor(source: SignalSource): Int = when (source) {
        SignalSource.BLE -> R.drawable.ic_ble
        SignalSource.WIFI -> R.drawable.ic_wifi
        SignalSource.CELL -> R.drawable.ic_cell
    }

    fun iconBadgeFor(source: SignalSource): Int = when (source) {
        SignalSource.BLE -> R.drawable.bg_v2_icon_ble
        SignalSource.WIFI -> R.drawable.bg_v2_icon_wifi
        SignalSource.CELL -> R.drawable.bg_v2_icon_cell
    }

    /** The distance step of a signal, or null for a base station. */
    fun stepOf(signal: Signal): String? = Classifier.distanceStepLabel(
        signal.smoothedRssi ?: signal.rssi.toFloat(),
        signal.source,
        signal.txPower,
        signal.frequencyMhz
    )

    /** `블루투스 · 애플` — the one line under a row's title. */
    fun summaryLine(signal: Signal): String {
        val maker = signal.vendor
            ?.takeIf { it.isNotBlank() && it != signal.title && !it.contains("미확인") }
            ?: signal.kind?.takeIf { it.isNotBlank() && it != signal.title }
        return if (maker == null) signal.source.label else signal.source.label + " · " + maker
    }
}
