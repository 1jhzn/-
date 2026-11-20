package com.jihun.signalradar.v2

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * One proximity tap of the 신호 찾기 screen: how hard it buzzes, how long it lasts and how
 * long the screen waits before the next one.
 *
 * [band] is 1 (far, barely there) to [OrbHapticsMap.BANDS] (in your hand), and the three
 * numbers grow with it, so the phone in your hand tells you the distance without looking.
 */
data class OrbPulse(
    val band: Int,
    val amplitude: Int,
    val durationMs: Long,
    val gapMs: Long,
    val proximity: Float
)

/**
 * 전파 감지기 v2.3 — the finder's nearness feedback, as vibration instead of sound.
 *
 * 지훈 first asked for a beep that grew louder as he got closer, then changed his mind:
 * > "진동만 크게해줘"
 * So this is a metal-detector feel rather than a speaker: the closer the Bluetooth signal,
 * the stronger the tap and the shorter the wait between taps. Nothing is played out loud,
 * so the app is still usable in class or in bed without headphones.
 *
 * A phone radio gives a level (dBm), not a distance, so the mapping is a deliberately coarse
 * five-step banding. Coarse steps are the point: a continuous curve feels like an unchanging
 * buzz, while five clearly different steps can be felt through a pocket.
 *
 *  - below [SILENT_DBM] the finder is silent (`null`): a signal that faint is not near anything
 *  - each band's amplitude (48 → 255 of 255), length (22 ms → 88 ms) and gap (1.5 s → 0.38 s)
 *    climbs one step, so every band is distinguishable by feel alone
 *
 * The mapping is plain arithmetic so it is tested on the JVM; [OrbHaptics] only keeps the
 * rhythm, and [OrbMotor] is the one place that touches the phone's motor.
 */
object OrbHapticsMap {

    const val BANDS = 5

    /** Quieter than this: no vibration at all. Roughly 10 m away in open air. */
    const val SILENT_DBM = -92f

    /** At and above this the finder is in reach — about half a metre. */
    const val NEAR_DBM = -45f

    /** Out of 255. The faintest tap is felt, but it is not a buzz in the pocket. */
    const val MIN_AMPLITUDE = 40
    const val MAX_AMPLITUDE = 255

    const val MIN_DURATION_MS = 18L
    const val MAX_DURATION_MS = 95L

    /** The slowest (far) and the fastest (near) wait between two taps. */
    const val MAX_GAP_MS = 1_500L
    const val MIN_GAP_MS = 380L

    /**
     * The dBm each band reaches up to, weakest band first: -70 dBm is band 3.
     * The far bands are wider because a distant Bluetooth reading jumps around, while a
     * packet from your own hand is steady.
     */
    private val UPPER_DBM = floatArrayOf(-85f, -76f, -67f, -58f, Float.POSITIVE_INFINITY)

    private val AMPLITUDE = intArrayOf(48, 92, 140, 196, MAX_AMPLITUDE)
    private val DURATION_MS = longArrayOf(22L, 33L, 46L, 64L, MAX_DURATION_MS)
    private val GAP_MS = longArrayOf(MAX_GAP_MS, 1_100L, 820L, 560L, MIN_GAP_MS)

    /** 0.0 for a signal at the edge of hearing, 1.0 for one in your hand. */
    fun proximity(rssi: Float?): Float {
        if (rssi == null) return 0f
        return ((rssi - SILENT_DBM) / (NEAR_DBM - SILENT_DBM)).coerceIn(0f, 1f)
    }

    /** 0 when nothing is heard, otherwise 1..[BANDS] from far to in your hand. */
    fun bandOf(rssi: Float?): Int {
        if (rssi == null || rssi <= SILENT_DBM) return 0
        for (index in UPPER_DBM.indices) {
            if (rssi <= UPPER_DBM[index]) return index + 1
        }
        return BANDS
    }

    /** The tap for this level, or null when the signal is too faint to be worth a buzz. */
    fun pulseFor(rssi: Float?): OrbPulse? {
        val band = bandOf(rssi)
        if (band == 0) return null
        val index = band - 1
        return OrbPulse(
            band = band,
            amplitude = AMPLITUDE[index],
            durationMs = DURATION_MS[index],
            gapMs = GAP_MS[index],
            proximity = proximity(rssi)
        )
    }

    /**
     * Length to use on a phone whose motor has no amplitude control.
     *
     * Such a phone can only switch the motor on for a while, so the strength has to be carried
     * by the length instead: the same five bands become roughly 50 ms → 210 ms, a very clear
     * difference in the hand even though every tap is equally hard.
     */
    fun fallbackDurationMs(durationMs: Long): Long =
        (durationMs * 2.4).toLong().coerceIn(48L, 220L)
}

/**
 * The phone's motor, behind one tiny interface.
 *
 * It is an interface so the rhythm and the fallback can be tested without a phone, and so a
 * motor that refuses the request ends as silence instead of a crash (some vendor ROMs do).
 */
interface OrbMotor {

    /** Whether this motor can buzz at a chosen strength (most phones since 2018). */
    val hasAmplitudeControl: Boolean

    fun buzz(durationMs: Long, amplitude: Int)

    fun cancel()
}

/** [OrbMotor] on top of the real [Vibrator]; a phone without one buzzes nowhere. */
class AndroidMotor(private val vibrator: Vibrator?) : OrbMotor {

    override val hasAmplitudeControl: Boolean = try {
        vibrator != null && vibrator.hasVibrator() && vibrator.hasAmplitudeControl()
    } catch (e: Exception) {
        false
    }

    override fun buzz(durationMs: Long, amplitude: Int) {
        val motor = vibrator ?: return
        // No strength control: the length carries the difference instead.
        val millis = if (hasAmplitudeControl) durationMs else OrbHapticsMap.fallbackDurationMs(durationMs)
        try {
            val effect = if (hasAmplitudeControl) {
                VibrationEffect.createOneShot(millis, amplitude.coerceIn(1, 255))
            } else {
                VibrationEffect.createOneShot(millis, VibrationEffect.DEFAULT_AMPLITUDE)
            }
            motor.vibrate(effect)
        } catch (e: Exception) {
            // A motor that refuses the request must not break the finder.
        }
    }

    override fun cancel() {
        try {
            vibrator?.cancel()
        } catch (e: Exception) {
            // Nothing to stop, or no permission to stop it.
        }
    }
}

/**
 * The rhythm of the finder's taps.
 *
 * Every tick of the finder screen calls [onLevel] with the current level. That call always
 * answers which band the signal is in (so the button can show it) and only touches the motor
 * when that band's gap has passed — so the taps get faster as the signal gets closer, and a
 * steady signal never turns into a continuous buzz.
 */
class OrbHaptics(
    private val motor: OrbMotor,
    private val clock: () -> Long = System::currentTimeMillis
) {

    private var nextAt = 0L

    /** The band the last reading was in, 0 for "nothing near". */
    var band: Int = 0
        private set

    /** The last pulse handed back, kept for the button and for the tests. */
    var lastPulse: OrbPulse? = null
        private set

    /** How many taps have actually reached the motor since the last [stop]. */
    var taps: Int = 0
        private set

    /**
     * One reading of the finder. Returns the band's pulse (or null when the signal is too
     * faint) and buzzes only when this band's gap has passed since the previous tap.
     */
    fun onLevel(rssi: Float?): OrbPulse? {
        val pulse = OrbHapticsMap.pulseFor(rssi)
        if (pulse == null) {
            band = 0
            lastPulse = null
            nextAt = 0L
            return null
        }
        band = pulse.band
        lastPulse = pulse
        val now = clock()
        if (now >= nextAt) {
            motor.buzz(pulse.durationMs, pulse.amplitude)
            taps++
            nextAt = now + pulse.gapMs
        }
        return pulse
    }

    /** Silence now, and the next reading may tap at once. Used on pause and on leaving. */
    fun stop() {
        band = 0
        lastPulse = null
        nextAt = 0L
        taps = 0
        motor.cancel()
    }

    companion object {
        /** The phone's own motor, or a [OrbHaptics] that quietly does nothing. */
        fun from(context: Context): OrbHaptics {
            val vibrator = try {
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            } catch (e: Exception) {
                null
            }
            return OrbHaptics(AndroidMotor(vibrator))
        }
    }
}
