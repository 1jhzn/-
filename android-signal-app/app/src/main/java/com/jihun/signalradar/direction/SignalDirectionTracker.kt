package com.jihun.signalradar.direction

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Turns a stream of (signal strength, phone heading) samples into something the radar
 * can draw: a smoothed level, a heat map of how strong the signal was in each direction,
 * and the direction of the strongest reading.
 *
 * Radio strength jumps around by several dB even when nothing moves, so every raw value
 * goes through an exponential moving average first; the peak direction is only allowed to
 * move on when a new direction is clearly stronger, which keeps the needle from dancing.
 *
 * Pure Kotlin on purpose: no Android types, so the maths is unit-tested on the JVM.
 */
class SignalDirectionTracker(
    private val smoothing: Float = 0.35f,
    private val historyMs: Long = 20_000L
) {

    private var ema: Float? = null
    private val sectorPeak = FloatArray(SECTORS) { NO_VALUE }
    private val sectorCos = DoubleArray(SECTORS)
    private val sectorSin = DoubleArray(SECTORS)
    private val sectorWeight = DoubleArray(SECTORS)
    private val history = ArrayDeque<Pair<Long, Float>>()
    private var bestSector = -1

    /** Strongest smoothed level seen, and the compass bearing it was heard on. */
    var peakRssi: Float? = null
        private set

    var peakAzimuth: Float? = null
        private set

    var lastSmoothed: Float? = null
        private set

    var lastHeading: Float? = null
        private set

    var sampleCount: Int = 0
        private set

    fun reset() {
        ema = null
        bestSector = -1
        peakRssi = null
        peakAzimuth = null
        lastSmoothed = null
        sampleCount = 0
        history.clear()
        for (i in 0 until SECTORS) {
            sectorPeak[i] = NO_VALUE
            sectorCos[i] = 0.0
            sectorSin[i] = 0.0
            sectorWeight[i] = 0.0
        }
    }

    fun smoothedRssi(): Float? = lastSmoothed

    /** Highest smoothed level heard while the phone pointed into this sector, if any. */
    fun sectorValue(index: Int): Float? =
        sectorPeak[index].takeIf { it > NO_VALUE }

    /** Feed one observation. [heading] is the phone's compass bearing, null when unknown. */
    fun onSample(rssi: Int, heading: Float?, now: Long): Float {
        sampleCount++
        lastHeading = heading?.let { normalize(it) }

        val previous = ema
        val value = if (previous == null) {
            rssi.toFloat()
        } else {
            previous + smoothing * (rssi - previous)
        }
        ema = value
        lastSmoothed = value

        history.addLast(now to value)
        while (history.size > 2 && now - history.first().first > historyMs) history.removeFirst()

        val azimuth = lastHeading
        if (azimuth != null) {
            val index = sectorOf(azimuth)
            // Linear power as the weight: a reading 10 dB stronger counts ten times more,
            // which is what makes the heat map and the refined bearing line up.
            val weight = 10.0.pow(value.toDouble() / 10.0)
            val rad = Math.toRadians(azimuth.toDouble())
            sectorCos[index] += weight * cos(rad)
            sectorSin[index] += weight * sin(rad)
            sectorWeight[index] += weight
            if (value > sectorPeak[index]) sectorPeak[index] = value
            refreshPeak()
        } else if (peakRssi == null || value > (peakRssi ?: NO_VALUE)) {
            // No compass: still report the strongest level, just without a direction.
            peakRssi = value
        }
        return value
    }

    /** Difference in dB over the last few seconds; null until enough samples exist. */
    fun trendDb(windowMs: Long = 6_000L): Float? {
        if (history.size < 2) return null
        val now = history.last().first
        if (now - history.first().first < TREND_MIN_SPAN_MS) return null
        var past: Float? = null
        for (entry in history) {
            if (now - entry.first >= windowMs) past = entry.second
        }
        val reference = past ?: history.first().second
        return history.last().second - reference
    }

    /** True when the phone is pointing at the strongest direction found so far. */
    fun isAligned(heading: Float? = lastHeading, tolerance: Float = ALIGNED_DEG): Boolean {
        val peak = peakAzimuth ?: return false
        val current = heading ?: return false
        return circularDifference(current, peak) <= tolerance
    }

    private fun refreshPeak() {
        var candidate = -1
        var strongest = NO_VALUE
        for (i in 0 until SECTORS) {
            if (sectorPeak[i] > strongest) {
                strongest = sectorPeak[i]
                candidate = i
            }
        }
        if (candidate < 0) return
        // Only jump to another direction when it is clearly stronger, otherwise the needle
        // would flip between two almost equal lobes every time the signal wobbles.
        if (bestSector < 0 || strongest >= sectorPeak[bestSector] + PEAK_HYSTERESIS_DB) {
            bestSector = candidate
        }
        peakRssi = sectorPeak[bestSector]
        peakAzimuth = refinedAzimuth(bestSector)
    }

    /** The best sector's own average bearing, nudged by its two neighbours. */
    private fun refinedAzimuth(index: Int): Float? {
        var x = 0.0
        var y = 0.0
        for (offset in -1..1) {
            val i = wrap(index + offset)
            if (sectorWeight[i] <= 0.0) continue
            val factor = if (offset == 0) 1.0 else NEIGHBOUR_WEIGHT
            val w = sectorWeight[i] * factor
            x += w * (sectorCos[i] / sectorWeight[i])
            y += w * (sectorSin[i] / sectorWeight[i])
        }
        if (x == 0.0 && y == 0.0) return null
        return normalize(Math.toDegrees(atan2(y, x)).toFloat())
    }

    companion object {
        const val SECTORS = 12
        const val SECTOR_DEG = 360f / SECTORS

        /** Within this many degrees of the peak counts as "pointing at it". */
        const val ALIGNED_DEG = 15f

        private const val NO_VALUE = Float.NEGATIVE_INFINITY
        private const val PEAK_HYSTERESIS_DB = 1.0f
        private const val NEIGHBOUR_WEIGHT = 0.35
        private const val TREND_MIN_SPAN_MS = 3_000L

        fun normalize(degrees: Float): Float {
            var value = degrees % 360f
            if (value < 0f) value += 360f
            return value
        }

        fun sectorOf(azimuth: Float): Int =
            (normalize(azimuth) / SECTOR_DEG).toInt().coerceIn(0, SECTORS - 1)

        fun sectorCenter(index: Int): Float = wrapStatic(index) * SECTOR_DEG

        fun circularDifference(a: Float, b: Float): Float {
            val diff = abs(normalize(a) - normalize(b))
            return if (diff > 180f) 360f - diff else diff
        }

        /** Shortest signed way to turn from [from] to [to]. */
        fun signedDifference(from: Float, to: Float): Float {
            var diff = normalize(to) - normalize(from)
            if (diff > 180f) diff -= 360f
            if (diff < -180f) diff += 360f
            return diff
        }

        /** "북동쪽 42°" — the words a person would use, plus the exact bearing. */
        fun bearingText(azimuth: Float): String {
            val az = normalize(azimuth)
            val names = arrayOf("북쪽", "북동쪽", "동쪽", "남동쪽", "남쪽", "남서쪽", "서쪽", "북서쪽")
            val index = (((az + 22.5f) / 45f).toInt()) % 8
            return "${names[index]} ${az.roundToInt() % 360}°"
        }

        private fun wrapStatic(index: Int): Int = ((index % SECTORS) + SECTORS) % SECTORS

        private fun wrap(index: Int): Int = wrapStatic(index)
    }
}
