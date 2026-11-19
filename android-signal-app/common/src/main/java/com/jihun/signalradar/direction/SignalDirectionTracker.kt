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
 * Two accuracy guards live here as well, because a hand-held "turn until it is loudest"
 * hunt is easily fooled:
 *  - A needle is not shown at all until at least [MIN_SECTORS_FOR_PEAK] of the 12
 *    directions have been measured. One or two readings say almost nothing.
 *  - Directions are compared after blending each sector with its two neighbours. A single
 *    lone spike (a reflection off a wall, a body blocking the path) has to be clearly
 *    stronger than a broad lobe before it can pull the needle to itself.
 *  - Each sector keeps its own exponential moving average as well, so one lucky packet —
 *    or one body stepping into the path — cannot become a sector's whole value and drag
 *    the needle to a direction the signal never really came from.
 *
 * Pure Kotlin on purpose: no Android types, so the maths is unit-tested on the JVM.
 */
class SignalDirectionTracker(
    private val smoothing: Float = 0.35f,
    private val historyMs: Long = 20_000L
) {

    private var ema: Float? = null

    /** Per-sector level: an average over the readings heard while pointing that way. */
    private val sectorLevel = FloatArray(SECTORS) { NO_VALUE }

    /** Highest single reading heard in each sector, for the "최고 -xx dBm" line only. */
    private val sectorMax = FloatArray(SECTORS) { NO_VALUE }
    private val sectorCos = DoubleArray(SECTORS)
    private val sectorSin = DoubleArray(SECTORS)
    private val sectorWeight = DoubleArray(SECTORS)
    private val sectorSeen = BooleanArray(SECTORS)
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

    /** How many of the 12 directions have a reading; drives "측정 진행: 8/12 방향". */
    var measuredSectors: Int = 0
        private set

    /** True once enough of the circle has been walked to trust a bearing. */
    val hasPeak: Boolean
        get() = peakAzimuth != null
    fun reset() {
        ema = null
        bestSector = -1
        peakRssi = null
        peakAzimuth = null
        lastSmoothed = null
        sampleCount = 0
        measuredSectors = 0
        history.clear()
        for (i in 0 until SECTORS) {
            sectorLevel[i] = NO_VALUE
            sectorMax[i] = NO_VALUE
            sectorCos[i] = 0.0
            sectorSin[i] = 0.0
            sectorWeight[i] = 0.0
            sectorSeen[i] = false
        }
    }

    fun smoothedRssi(): Float? = lastSmoothed

    /** Smoothed level heard while the phone pointed into this sector, if any. */
    fun sectorValue(index: Int): Float? =
        sectorLevel[wrap(index)].takeIf { it > NO_VALUE }

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
            if (!sectorSeen[index]) {
                sectorSeen[index] = true
                measuredSectors++
            }
            // Linear power as the weight: a reading 10 dB stronger counts ten times more,
            // which is what makes the heat map and the refined bearing line up.
            val weight = 10.0.pow(value.toDouble() / 10.0)
            val rad = Math.toRadians(azimuth.toDouble())
            sectorCos[index] += weight * cos(rad)
            sectorSin[index] += weight * sin(rad)
            sectorWeight[index] += weight
            // Average the sector instead of remembering its best packet: a reflection that
            // happened to be caught once must not define a whole direction.
            val previousLevel = sectorLevel[index]
            sectorLevel[index] = if (previousLevel > NO_VALUE) {
                previousLevel + SECTOR_SMOOTHING * (value - previousLevel)
            } else {
                value
            }
            if (value > sectorMax[index]) sectorMax[index] = value
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
        if (measuredSectors < MIN_SECTORS_FOR_PEAK) {
            // Too little of the circle seen: keep the level, hold the needle back.
            bestSector = -1
            peakAzimuth = null
            peakRssi = strongestSectorLevel()
            return
        }

        var candidate = -1
        var bestScore = NO_VALUE
        for (i in 0 until SECTORS) {
            val score = smoothedScore(i) ?: continue
            if (score > bestScore) {
                bestScore = score
                candidate = i
            }
        }
        if (candidate < 0) {
            peakAzimuth = null
            return
        }
        val currentScore = if (bestSector >= 0) smoothedScore(bestSector) else null
        // Only jump to another direction when it is clearly stronger, otherwise the needle
        // would flip between two almost equal lobes every time the signal wobbles.
        if (currentScore == null || bestScore >= currentScore + PEAK_HYSTERESIS_DB) {
            bestSector = candidate
        }
        // The direction is chosen from the smoothed sectors, but "최고" on screen means the
        // loudest level actually heard that way, so it may be higher than the average.
        peakRssi = sectorMax[bestSector].takeIf { it > NO_VALUE } ?: sectorValue(bestSector)
        peakAzimuth = refinedAzimuth(bestSector)
    }

    /**
     * A sector's level blended with its two neighbours, in dB.
     *
     * A direction surrounded by equally strong readings keeps its full value; a lone spike
     * with nothing on either side pays [ISOLATION_DB], so a reflection has to be a lot
     * stronger than a real lobe before it wins.
     */
    private fun smoothedScore(index: Int): Float? {
        val center = sectorValue(index) ?: return null
        val left = sectorValue(index - 1) ?: (center - ISOLATION_DB)
        val right = sectorValue(index + 1) ?: (center - ISOLATION_DB)
        return CENTER_WEIGHT * center + NEIGHBOUR_WEIGHT * (left + right)
    }

    private fun strongestSectorLevel(): Float? {
        var strongest: Float? = null
        for (i in 0 until SECTORS) {
            val value = sectorValue(i) ?: continue
            if (strongest == null || value > strongest) strongest = value
        }
        return strongest ?: lastSmoothed
    }

    /**
     * The bearing inside the winning sector: where the phone actually pointed while the
     * signal was loudest, nudged by a parabola through the two neighbouring sectors so the
     * needle lands between sector centres instead of snapping to them.
     */
    private fun refinedAzimuth(index: Int): Float? {
        val weight = sectorWeight[index]
        if (weight <= 0.0) return null
        val meanAzimuth = normalize(
            Math.toDegrees(
                atan2(sectorSin[index] / weight, sectorCos[index] / weight)
            ).toFloat()
        )
        val center = sectorCenter(index)
        val intra = signedDifference(center, meanAzimuth) * INTRA_WEIGHT
        val shift = (intra + parabolicOffsetDegrees(index))
            .coerceIn(-SECTOR_DEG / 2f, SECTOR_DEG / 2f)
        return normalize(center + shift)
    }

    /** How far the true peak sits from the sector centre, in degrees, within ±15°. */
    private fun parabolicOffsetDegrees(index: Int): Float {
        val center = sectorValue(index) ?: return 0f
        val left = sectorValue(index - 1) ?: return 0f
        val right = sectorValue(index + 1) ?: return 0f
        val denominator = left - 2f * center + right
        // A flat top or a valley gives no usable peak position.
        if (denominator >= -0.001f) return 0f
        return (0.5f * (left - right) / denominator).coerceIn(-0.5f, 0.5f) * SECTOR_DEG
    }

    companion object {
        const val SECTORS = 12
        const val SECTOR_DEG = 360f / SECTORS

        /** Directions that must be measured before a bearing is shown at all. */
        const val MIN_SECTORS_FOR_PEAK = 4

        /** Within this many degrees of the peak counts as "pointing at it". */
        const val ALIGNED_DEG = 15f

        private const val NO_VALUE = Float.NEGATIVE_INFINITY

        /** Weight of each new reading in a sector's own average. */
        private const val SECTOR_SMOOTHING = 0.3f

        private const val PEAK_HYSTERESIS_DB = 1.5f
        private const val CENTER_WEIGHT = 0.5f
        private const val NEIGHBOUR_WEIGHT = 0.25f

        /** How much a direction with no neighbours on either side is discounted. */
        private const val ISOLATION_DB = 6f

        /** How much of the within-sector spread is trusted vs. the sector grid. */
        private const val INTRA_WEIGHT = 1.0f
        private const val TREND_MIN_SPAN_MS = 3_000L

        fun normalize(degrees: Float): Float {
            var value = degrees % 360f
            if (value < 0f) value += 360f
            return value
        }

        /**
         * Which 30° sector a bearing belongs to.
         *
         * Sectors are centred on 0°, 30°, 60° … so sector 0 covers 345°–15°. That matters:
         * the radar draws each heat arc around `index * 30°`, and an index computed from the
         * left edge of the sector instead would put every arc 15° away from where it was
         * actually measured — the "it points slightly wrong" bug.
         */
        fun sectorOf(azimuth: Float): Int {
            val shifted = normalize(azimuth + SECTOR_DEG / 2f)
            return (shifted / SECTOR_DEG).toInt() % SECTORS
        }

        fun sectorCenter(index: Int): Float = wrapStatic(index) * SECTOR_DEG

        fun progressLabel(measured: Int): String =
            "측정 진행: ${measured.coerceIn(0, SECTORS)}/$SECTORS 방향"

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
