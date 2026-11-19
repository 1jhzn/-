package com.jihun.signalradar.direction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.roundToInt

/** The maths of the direction finder, tested without a phone or a radio. */
class SignalDirectionTrackerTest {

    @Test
    fun smoothing_takes_the_edges_off_jitter() {
        val tracker = SignalDirectionTracker()
        var now = 0L
        var last = 0f
        val raw = listOf(-60, -52, -66, -55, -63, -58, -61, -57)
        for (value in raw) {
            now += 200
            last = tracker.onSample(value, 0f, now)
        }
        // The raw readings swing over 14 dB; the smoothed value must sit inside that band
        // and close to the middle of it instead of following every jump.
        assertTrue("smoothed=$last", last in -62f..-54f)
        assertTrue("smoothed=$last", kotlin.math.abs(last - (-59f)) < 3f)
    }

    @Test
    fun peak_direction_is_where_the_signal_was_strongest() {
        val tracker = SignalDirectionTracker()
        var now = 0L
        for (degree in 0 until 360 step 5) {
            val rad = Math.toRadians((degree - 120).toDouble())
            val rssi = (-80 + 26 * cos(rad)).roundToInt()
            repeat(10) {
                now += 200
                tracker.onSample(rssi, degree.toFloat(), now)
            }
        }
        val peak = tracker.peakAzimuth ?: 0f
        assertTrue(
            "peak=$peak (expected near 120°)",
            SignalDirectionTracker.circularDifference(peak, 120f) <= 20f
        )
        assertNotNull(tracker.peakRssi)
    }

    @Test
    fun peak_does_not_jump_for_a_wobble_much_weaker_than_the_hysteresis() {
        val tracker = SignalDirectionTracker()
        var now = 0L
        repeat(40) {
            now += 200
            tracker.onSample(-60, 90f, now)
        }
        val firstPeak = tracker.peakAzimuth ?: 0f

        // 0.5 dB stronger in another direction: within the 1 dB hysteresis, so it stays.
        repeat(40) {
            now += 200
            tracker.onSample(-60, 0f, now)
        }
        assertEquals(firstPeak, tracker.peakAzimuth ?: 0f, 2f)
        assertTrue(SignalDirectionTracker.circularDifference(tracker.peakAzimuth ?: 0f, 90f) < 20f)
    }

    @Test
    fun peak_moves_to_a_clearly_stronger_direction() {
        val tracker = SignalDirectionTracker()
        var now = 0L
        repeat(40) {
            now += 200
            tracker.onSample(-70, 90f, now)
        }
        repeat(60) {
            now += 200
            tracker.onSample(-55, 270f, now)
        }
        val peak = tracker.peakAzimuth ?: 0f
        assertTrue(
            "peak=$peak (expected near 270°)",
            SignalDirectionTracker.circularDifference(peak, 270f) <= 20f
        )
    }

    @Test
    fun aligned_only_within_fifteen_degrees_of_the_peak() {
        val tracker = SignalDirectionTracker()
        var now = 0L
        repeat(40) {
            now += 200
            tracker.onSample(-55, 42f, now)
        }
        val peak = tracker.peakAzimuth ?: 0f
        assertTrue(tracker.isAligned(SignalDirectionTracker.normalize(peak + 10f)))
        assertTrue(!tracker.isAligned(SignalDirectionTracker.normalize(peak + 40f)))
        // Wrapping across north must work too.
        assertTrue(tracker.isAligned(SignalDirectionTracker.normalize(peak - 12f)))
    }

    @Test
    fun sectors_cover_the_compass_evenly() {
        assertEquals(0, SignalDirectionTracker.sectorOf(0f))
        assertEquals(0, SignalDirectionTracker.sectorOf(29.9f))
        assertEquals(1, SignalDirectionTracker.sectorOf(30f))
        assertEquals(6, SignalDirectionTracker.sectorOf(180f))
        assertEquals(11, SignalDirectionTracker.sectorOf(359.9f))
        assertEquals(0, SignalDirectionTracker.sectorOf(360f))
        assertEquals(11, SignalDirectionTracker.sectorOf(-5f))
    }

    @Test
    fun bearings_read_like_a_person_would_say_them() {
        assertEquals("북쪽 0°", SignalDirectionTracker.bearingText(0f))
        assertEquals("북동쪽 42°", SignalDirectionTracker.bearingText(42f))
        assertEquals("동쪽 90°", SignalDirectionTracker.bearingText(90f))
        assertEquals("남쪽 180°", SignalDirectionTracker.bearingText(180f))
        assertEquals("서쪽 270°", SignalDirectionTracker.bearingText(270f))
        assertEquals("북쪽 350°", SignalDirectionTracker.bearingText(350f))
        assertEquals("북서쪽 315°", SignalDirectionTracker.bearingText(315f))
    }

    @Test
    fun trend_notices_a_rising_signal() {
        val tracker = SignalDirectionTracker()
        var now = 0L
        repeat(40) {
            now += 200
            tracker.onSample(-80, 45f, now)
        }
        repeat(10) {
            now += 200
            tracker.onSample(-55, 45f, now)
        }
        val change = tracker.trendDb() ?: 0f
        assertTrue("trend=$change", change > 2f)
    }

    @Test
    fun trend_is_silent_until_there_is_enough_history() {
        val tracker = SignalDirectionTracker()
        tracker.onSample(-70, 10f, 0L)
        tracker.onSample(-70, 10f, 200L)
        assertNull(tracker.trendDb())
    }

    @Test
    fun reset_forgets_the_peak() {
        val tracker = SignalDirectionTracker()
        var now = 0L
        repeat(20) {
            now += 200
            tracker.onSample(-50, 100f, now)
        }
        assertNotNull(tracker.peakAzimuth)
        tracker.reset()
        assertNull(tracker.peakAzimuth)
        assertNull(tracker.peakRssi)
        assertNull(tracker.smoothedRssi())
        assertEquals(0, tracker.sampleCount)
        for (index in 0 until SignalDirectionTracker.SECTORS) {
            assertNull(tracker.sectorValue(index))
        }
    }

    @Test
    fun without_a_compass_the_strongest_level_is_still_reported() {
        val tracker = SignalDirectionTracker()
        var now = 0L
        repeat(10) {
            now += 200
            tracker.onSample(-72, null, now)
        }
        repeat(10) {
            now += 200
            tracker.onSample(-48, null, now)
        }
        assertNull(tracker.peakAzimuth)
        assertNotNull(tracker.peakRssi)
        assertTrue((tracker.peakRssi ?: 0f) > -55f)
    }
}
