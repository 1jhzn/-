package com.jihun.signalradar.v2

import android.os.Build
import android.os.Vibrator
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 전파 감지기 v2.3 — the finder's nearness feedback (지훈: "진동만 크게해줘").
 *
 *  - the dBm → five-band mapping: silence when far, and amplitude · length · gap all climbing
 *    as the signal gets closer
 *  - the rhythm: one tap per band gap, faster when nearer, silent when very far, nothing left
 *    running after [OrbHaptics.stop]
 *  - the fallback for a motor without amplitude control, which carries strength in length
 *  - no crash on a phone whose motor refuses the request
 */
class OrbHapticsMapTest {

    @Test
    fun a_signal_at_the_edge_of_hearing_is_silent() {
        assertNull("nothing heard", OrbHapticsMap.pulseFor(null))
        assertNull("-95 dBm is out of reach", OrbHapticsMap.pulseFor(-95f))
        assertNull("-92 dBm is exactly the edge", OrbHapticsMap.pulseFor(-92f))
        assertEquals(0, OrbHapticsMap.bandOf(-92f))
        assertEquals(0, OrbHapticsMap.bandOf(null))
    }

    @Test
    fun the_five_bands_are_spread_over_the_levels_a_phone_hears() {
        assertEquals(1, OrbHapticsMap.bandOf(-91f))
        assertEquals(1, OrbHapticsMap.bandOf(-86f))
        assertEquals(2, OrbHapticsMap.bandOf(-80f))
        assertEquals(3, OrbHapticsMap.bandOf(-70f))
        assertEquals(4, OrbHapticsMap.bandOf(-62f))
        assertEquals(5, OrbHapticsMap.bandOf(-52f))
        assertEquals("a device in your hand", 5, OrbHapticsMap.bandOf(-30f))
        assertEquals(OrbHapticsMap.BANDS, OrbHapticsMap.bandOf(-30f))
    }

    @Test
    fun getting_closer_means_a_harder_longer_and_more_often_tap() {
        var previous: OrbPulse? = null
        for (level in listOf(-91f, -80f, -70f, -62f, -52f)) {
            val pulse = OrbHapticsMap.pulseFor(level)
            assertNotNull("level $level must buzz", pulse)
            val now = pulse!!
            if (previous != null) {
                val before = previous!!
                assertTrue(
                    "amplitude must climb: ${before.amplitude} → ${now.amplitude}",
                    now.amplitude > before.amplitude
                )
                assertTrue(
                    "the tap must last longer: ${before.durationMs} → ${now.durationMs}",
                    now.durationMs > before.durationMs
                )
                assertTrue(
                    "the wait must shrink: ${before.gapMs} → ${now.gapMs}",
                    now.gapMs < before.gapMs
                )
            }
            previous = now
        }
    }

    @Test
    fun the_numbers_stay_inside_the_range_a_motor_and_a_person_accept() {
        val far = OrbHapticsMap.pulseFor(-91f)!!
        val near = OrbHapticsMap.pulseFor(-45f)!!

        assertTrue("far is faint but felt: ${far.amplitude}", far.amplitude in 40..70)
        assertEquals("in your hand: full strength", OrbHapticsMap.MAX_AMPLITUDE, near.amplitude)
        assertTrue("far is slow: ${far.gapMs}", far.gapMs >= 1_200L && far.gapMs <= 1_500L)
        assertTrue("near is quick: ${near.gapMs}", near.gapMs in 300L..400L)
        assertTrue("near taps are not a continuous buzz", near.durationMs < 100L)
        assertEquals(OrbHapticsMap.BANDS, near.band)
        for (pulse in listOf(far, near)) {
            assertTrue(pulse.amplitude in OrbHapticsMap.MIN_AMPLITUDE..255)
            assertTrue(pulse.durationMs in OrbHapticsMap.MIN_DURATION_MS..OrbHapticsMap.MAX_DURATION_MS)
        }
        assertEquals("full strength in your hand", 1f, near.proximity, 0.001f)
        assertTrue("a far signal is still positive", far.proximity in 0.01f..0.2f)
    }

    @Test
    fun proximity_runs_from_zero_to_one_over_the_same_range() {
        assertEquals(0f, OrbHapticsMap.proximity(null), 0.001f)
        assertEquals(0f, OrbHapticsMap.proximity(-92f), 0.001f)
        assertEquals(0.5f, OrbHapticsMap.proximity(-68.5f), 0.01f)
        assertEquals(1f, OrbHapticsMap.proximity(-45f), 0.001f)
        assertEquals("clamped at the top", 1f, OrbHapticsMap.proximity(-20f), 0.001f)
    }

    @Test
    fun a_phone_without_amplitude_control_carries_the_strength_in_the_length() {
        val far = OrbHapticsMap.fallbackDurationMs(OrbHapticsMap.MIN_DURATION_MS)
        val near = OrbHapticsMap.fallbackDurationMs(OrbHapticsMap.MAX_DURATION_MS)
        assertTrue("far fallback is still felt: $far", far >= 48L)
        assertTrue("near fallback is long: $near", near >= 200L)
        assertTrue("and clearly longer than far: $far → $near", near > far * 3)
        // The real bands, end to end.
        val bands = (1..OrbHapticsMap.BANDS).map { band ->
            val pulse = OrbHapticsMap.pulseFor(-93f + band * 8f)!!
            OrbHapticsMap.fallbackDurationMs(pulse.durationMs)
        }
        assertEquals("five different lengths", 5, bands.distinct().size)
        assertEquals("in climbing order", bands.sorted(), bands)
    }
}

/** A motor that records what it was asked to do, and a clock the test moves by hand. */
private class RecordingMotor(override val hasAmplitudeControl: Boolean = true) : OrbMotor {
    val taps = ArrayList<Pair<Long, Int>>()
    var cancels = 0

    override fun buzz(durationMs: Long, amplitude: Int) {
        taps.add(durationMs to amplitude)
    }

    override fun cancel() {
        cancels++
    }
}

class OrbHapticsRhythmTest {

    private var now = 1_000L
    private val motor = RecordingMotor()
    private val haptics = OrbHaptics(motor) { now }

    @Test
    fun a_steady_signal_taps_once_per_gap_and_not_on_every_tick() {
        // The finder ticks every 160 ms; band 3 waits 820 ms.
        var taps = 0
        repeat(10) {
            haptics.onLevel(-70f)
            taps = motor.taps.size
            now += 160L
        }
        // 10 ticks are 1,440 ms: a tap at 0, at 820 and at 1,640 is not reached yet.
        assertEquals("two taps in 1.4 s at band 3", 2, taps)
        assertTrue(motor.taps.all { it.first == 46L && it.second == 140 })
    }

    @Test
    fun the_same_second_buzzes_more_often_close_than_far() {
        fun tapsIn(level: Float, millis: Long): Int {
            now = 0L
            val motor = RecordingMotor()
            val fast = OrbHaptics(motor) { now }
            while (now < millis) {
                fast.onLevel(level)
                now += 160L
            }
            return motor.taps.size
        }
        val far = tapsIn(-88f, 5_000L)
        val near = tapsIn(-50f, 5_000L)
        assertTrue("far: $far taps in 5 s", far in 2..5)
        assertTrue("near: $near taps in 5 s", near >= 10)
        assertTrue("closer must buzz more often: $far → $near", near > far * 2)
    }

    @Test
    fun a_far_signal_never_touches_the_motor_and_the_button_still_knows_the_band() {
        assertNull(haptics.onLevel(-95f))
        assertEquals(0, haptics.band)
        assertTrue(motor.taps.isEmpty())

        val pulse = haptics.onLevel(-70f)
        assertNotNull(pulse)
        assertEquals(3, pulse!!.band)
        assertEquals(3, haptics.band)
        assertEquals(1, motor.taps.size)
    }

    @Test
    fun stop_silences_the_motor_and_the_next_reading_taps_at_once() {
        haptics.onLevel(-50f)
        assertEquals(1, motor.taps.size)
        now += 100L
        haptics.onLevel(-50f)
        assertEquals("too soon for a second tap", 1, motor.taps.size)

        haptics.stop()
        assertEquals(1, motor.cancels)
        assertEquals(0, haptics.band)
        assertNull(haptics.lastPulse)

        haptics.onLevel(-50f)
        assertEquals("a fresh start taps immediately", 2, motor.taps.size)
    }

    @Test
    fun the_band_is_reported_even_between_taps_so_the_button_can_show_it() {
        haptics.onLevel(-80f)
        now += 100L
        val pulse = haptics.onLevel(-80f)
        assertEquals(2, pulse?.band)
        assertEquals(2, haptics.band)
        assertEquals("no second tap yet", 1, motor.taps.size)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class AndroidMotorTest {

    @Test
    fun the_phones_motor_refuses_nothing_and_never_crashes_the_finder() {
        val vibrator = ApplicationProvider.getApplicationContext<android.app.Application>()
            .getSystemService(Vibrator::class.java)
        assertNotNull(vibrator)
        val motor = AndroidMotor(vibrator)
        // Robolectric's motor reports no amplitude control, which is the fallback path:
        // the length has to carry the strength, and nothing may throw.
        assertFalse(motor.hasAmplitudeControl)
        motor.buzz(22L, 48)
        motor.buzz(95L, 255)
        motor.cancel()

        // And the whole finder stack on top of it.
        val haptics = OrbHaptics.from(
            ApplicationProvider.getApplicationContext<android.app.Application>()
        )
        assertNotNull(haptics.onLevel(-46f))
        assertEquals(5, haptics.band)
        haptics.stop()
    }

    @Test
    fun a_phone_without_a_motor_says_nothing_and_does_not_crash() {
        val motor = AndroidMotor(null)
        assertFalse(motor.hasAmplitudeControl)
        motor.buzz(40L, 200)
        motor.cancel()
        val haptics = OrbHaptics(motor)
        assertEquals(4, haptics.onLevel(-62f)?.band)
    }
}
