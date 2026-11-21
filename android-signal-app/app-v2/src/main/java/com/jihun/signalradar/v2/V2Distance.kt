package com.jihun.signalradar.v2

import android.content.SharedPreferences
import com.jihun.signalradar.model.Signal

/**
 * v2.4 — 지훈: "거리도 버튼을 누르면 예상을 볼수있게 해줘".
 *
 * v2.3 writes a distance as a step word everywhere (매우 가까움) and never as metres. This
 * adds the `📏 예상 거리` switch: with it on, the estimate is written beside the step
 * (`매우 가까움 · 50cm 이내`) in the list rows, the top card, the finder and the detail card.
 * The switch is remembered between launches, and off by default so the list stays a clean
 * row of step words until 지훈 asks for numbers.
 *
 * The number is the log-distance estimate ([V2Calibration]): a path-loss guess from the level,
 * the advertised 1 m power and the band. It moves with every packet and with walls and bodies
 * in the way, so it is written with `약` and is never a measurement.
 *
 * v2.8: the number itself comes from [V2Calibration]'s tuned indoor model, not the free-space
 * one, so the metres match the step word beside them (`가까움 · 약 1.2m`, not `약 1.8m`).
 */
object V2Distance {

    /** The one preferences file every v2 switch uses (진동, 기준 …). */
    const val PREFS = "signal_radar"

    /** v2.4: the 📏 switch, off until it is pressed. */
    const val KEY_ON = "distance_metres_on"

    fun isOn(prefs: SharedPreferences): Boolean = prefs.getBoolean(KEY_ON, false)

    fun save(prefs: SharedPreferences, on: Boolean) {
        prefs.edit().putBoolean(KEY_ON, on).apply()
    }

    /** `50cm 이내` · `약 1.2m` · `약 5m` · `60m+`, or null for a base station. */
    fun metresOf(signal: Signal): String? = V2Calibration.metresText(signal)

    /**
     * What a distance badge says: the step alone, or the step with the estimate beside it.
     * `매우 가까움` → `매우 가까움 · 50cm 이내`. A base station has no step and no badge.
     */
    fun badge(step: String?, metres: String?, on: Boolean): String? {
        if (step == null) return null
        return if (on && metres != null) "$step · $metres" else step
    }

    fun badge(signal: Signal, on: Boolean): String? =
        badge(V2Look.stepOf(signal), metresOf(signal), on)

    /** The finder's own line under the big step word: `📏 예상 50cm 이내`. */
    fun finderLine(metres: String?): String? =
        if (metres == null) null else "📏 예상 $metres"
}
