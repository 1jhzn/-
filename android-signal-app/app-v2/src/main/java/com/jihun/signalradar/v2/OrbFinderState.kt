package com.jihun.signalradar.v2

import com.jihun.signalradar.direction.SignalDirectionTracker
import kotlin.math.roundToInt

/**
 * The state behind the 신호 찾기 (orb) screen, without a single Android type so it can be
 * tested on the JVM:
 *
 *  - how big the orb is, from the smoothed level ([orbScale]);
 *  - which direction the signal was loudest on, and which way the phone is pointing
 *    ([peakAzimuth], [aligned]) — the twelve-sector sweep of the v1 방향 찾기, unchanged;
 *  - how far the screen has blended into emerald ([emeraldProgress]): only after
 *    `매우 가까움` has been **held** for [EMERALD_HOLD_MS], so a single loud packet cannot
 *    flash the whole screen green.
 */
class OrbFinderState(
    private val tracker: SignalDirectionTracker = SignalDirectionTracker(),
    private val emeraldHoldMs: Long = EMERALD_HOLD_MS
) {

    var step: String? = null
        private set

    var smoothed: Float? = null
        private set

    var peakAzimuth: Float? = null
        private set

    var peakRssi: Float? = null
        private set

    var measuredSectors: Int = 0
        private set

    var sampleCount: Int = 0
        private set

    /** When `매우 가까움` was first reached and kept, or null while it is not held. */
    var veryNearSince: Long? = null
        private set

    /**
     * One reading. [stepOf] turns a raw level into the word the screen shows, so this class
     * never needs to know about Bluetooth, Wi-Fi or a base station.
     */
    fun onSample(rssi: Int, heading: Float?, now: Long, stepOf: (Int) -> String?): String? {
        smoothed = tracker.onSample(rssi, heading, now)
        val label = stepOf(rssi)
        step = label
        if (label == V2Look.VERY_NEAR) {
            if (veryNearSince == null) veryNearSince = now
        } else {
            veryNearSince = null
        }
        peakAzimuth = tracker.peakAzimuth
        peakRssi = tracker.peakRssi
        measuredSectors = tracker.measuredSectors
        sampleCount = tracker.sampleCount
        return label
    }

    /** 0 while the signal is not in reach, up to 1 once `매우 가까움` has been held. */
    fun emeraldProgress(now: Long): Float = emeraldFor(veryNearSince, now, emeraldHoldMs)

    /** 0.32 for a distant signal, 1.0 for one in your hand. */
    fun orbScale(): Float = scaleFor(smoothed)

    /** True while the phone points at the direction the signal was loudest on. */
    fun aligned(heading: Float?): Boolean = tracker.isAligned(heading)

    fun sectorValue(index: Int): Float? = tracker.sectorValue(index)

    fun levelText(): String {
        val now = smoothed?.roundToInt()?.toString() ?: "–"
        val best = peakRssi?.roundToInt()?.toString() ?: "–"
        return "측정 진행: ${measuredSectors}/${SignalDirectionTracker.SECTORS} 방향 · 최고 $best · 현재 $now dBm"
    }

    fun reset() {
        tracker.reset()
        step = null
        smoothed = null
        peakAzimuth = null
        peakRssi = null
        measuredSectors = 0
        sampleCount = 0
        veryNearSince = null
    }

    companion object {
        /** How long `매우 가까움` has to last before the screen turns emerald. */
        const val EMERALD_HOLD_MS = 1_600L

        const val MIN_SCALE = 0.32f
        const val MAX_SCALE = 1.0f

        /** Below this the orb sits at its smallest; at this level it fills the screen. */
        const val FLOOR_DBM = -95f
        const val CEIL_DBM = -40f

        fun scaleFor(rssi: Float?): Float {
            if (rssi == null) return MIN_SCALE
            val t = ((rssi - FLOOR_DBM) / (CEIL_DBM - FLOOR_DBM)).coerceIn(0f, 1f)
            return MIN_SCALE + (MAX_SCALE - MIN_SCALE) * t
        }

        fun emeraldFor(nearSince: Long?, now: Long, holdMs: Long = EMERALD_HOLD_MS): Float {
            if (nearSince == null) return 0f
            if (holdMs <= 0L) return 1f
            return ((now - nearSince).toFloat() / holdMs).coerceIn(0f, 1f)
        }
    }
}
