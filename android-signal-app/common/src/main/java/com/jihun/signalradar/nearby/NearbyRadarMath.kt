package com.jihun.signalradar.nearby

import com.jihun.signalradar.identify.Classifier
import com.jihun.signalradar.model.Signal
import com.jihun.signalradar.model.SignalSource
import kotlin.math.roundToInt

/**
 * One device on the 10 m radar: how far away it is estimated to be and, only when
 * "🧭 방향 찾기" was actually swept, the compass bearing it was heard on.
 */
data class NearbyBlip(
    val signal: Signal,
    val metres: Float,
    val bearing: Float?
) {
    val measured: Boolean get() = bearing != null
}

/**
 * The arithmetic behind the 10 m radar, kept out of the view so it can be tested.
 *
 * A phone radio gives a strength, not a direction, so only two of the three numbers on the
 * radar are real: the distance (an estimate from the signal level) and, once
 * "🧭 방향 찾기" has been swept, the bearing. Everything that has not been swept gets a
 * placeholder angle taken from its address, so it stays in the same spot while the list
 * refreshes instead of jumping around.
 */
object NearbyRadarMath {

    /** The radar's outer ring. */
    const val MAX_METRES = 10f

    /** Devices up to this are drawn: a guess of 10.2 m should still land on the 10 m ring. */
    const val LIMIT_METRES = 10.5f

    /** Placeholder directions: 24 places of 15° each. */
    const val SLOTS = 24
    const val SLOT_DEG = 360f / SLOTS

    /** Keep a blip off the "나" marker even when the estimate says "right here". */
    const val MIN_FRACTION = 0.055f

    /**
     * The devices that belong inside the circle, nearest first.
     *
     * Base stations are left out: their signal level says nothing about how far away the
     * tower is. [bearingOf] returns the direction measured for a signal id, or null when it
     * has not been measured yet. [distanceOf] lets a screen use its own tuned estimate (the
     * v2 app's indoor one) while every other caller keeps the shared default.
     */
    fun nearbyBlips(
        signals: List<Signal>,
        distanceOf: ((Signal) -> Double?)? = null,
        bearingOf: (String) -> Float?
    ): List<NearbyBlip> {
        val blips = ArrayList<NearbyBlip>(signals.size)
        for (signal in signals) {
            if (signal.source == SignalSource.CELL) continue
            val level = signal.smoothedRssi ?: signal.rssi.toFloat()
            val metres = (distanceOf?.invoke(signal) ?: Classifier.distanceMeters(
                level.toDouble(),
                signal.source,
                signal.txPower,
                signal.frequencyMhz
            )) ?: continue
            if (metres > LIMIT_METRES) continue
            blips.add(NearbyBlip(signal, metres.toFloat(), bearingOf(signal.id)))
        }
        blips.sortWith(compareBy({ it.metres }, { it.signal.address }))
        return blips
    }

    /** 0 at the centre, 1 on the 10 m ring. */
    fun radiusFraction(metres: Float): Float {
        val clamped = metres.coerceIn(0f, MAX_METRES)
        return (clamped / MAX_METRES).coerceAtLeast(MIN_FRACTION)
    }

    /** Which of the 24 placeholder directions this address belongs to. */
    fun slotOf(address: String): Int {
        val hash = address.uppercase().hashCode().toLong() and 0xFFFFFFFFL
        return (hash % SLOTS).toInt()
    }

    /**
     * Placeholder angle per address in degrees from north.
     *
     * Two addresses can share a slot; they are then spread inside that slot by address
     * order, so the picture is the same on every refresh and no two dots sit exactly on
     * top of each other.
     */
    fun placeholderAngles(addresses: Collection<String>): Map<String, Float> {
        val result = HashMap<String, Float>(addresses.size * 2)
        val bySlot = addresses.distinct().groupBy { slotOf(it) }
        for ((slot, members) in bySlot) {
            val sorted = members.sorted()
            val count = sorted.size
            for ((index, address) in sorted.withIndex()) {
                val offset = if (count == 1) {
                    0f
                } else {
                    (index.toFloat() / (count - 1) - 0.5f) * (SLOT_DEG * 0.7f)
                }
                result[address] = normalize(slot * SLOT_DEG + offset)
            }
        }
        return result
    }

    /** "0.8m", "2.4m", "10m" — short enough to sit beside a dot. */
    fun metreText(metres: Float): String {
        val clamped = metres.coerceIn(0f, MAX_METRES)
        val tenths = (clamped * 10).roundToInt()
        return if (tenths % 10 == 0) "${tenths / 10}m" else "${tenths / 10}.${tenths % 10}m"
    }

    /** A name that fits next to a dot, without cutting a Korean syllable in half. */
    fun shortName(name: String, max: Int = 11): String {
        val clean = name.replace(Regex("\\s+"), " ").trim()
        if (clean.length <= max) return clean
        return clean.take((max - 1).coerceAtLeast(1)).trimEnd() + "…"
    }

    fun normalize(degrees: Float): Float {
        var value = degrees % 360f
        if (value < 0f) value += 360f
        return value
    }
}
