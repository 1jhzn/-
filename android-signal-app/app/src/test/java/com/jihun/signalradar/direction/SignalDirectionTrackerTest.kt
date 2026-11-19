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

    private class Feed(val tracker: SignalDirectionTracker) {
        var now = 0L
        fun sample(rssi: Int, heading: Float?, times: Int = 1) {
            repeat(times) {
                now += 200
                tracker.onSample(rssi, heading, now)
            }
        }
    }

    /** Four directions at a quiet level, so the tracker is allowed to show a bearing. */
    private fun seedFourSectors(tracker: SignalDirectionTracker, level: Int = -75) {
        val feed = Feed(tracker)
        for (degree in floatArrayOf(0f, 90f, 180f, 270f)) {
            feed.sample(level, degree, 3)
        }
    }

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
    fun no_bearing_until_four_directions_are_measured() {
        val tracker = SignalDirectionTracker()
        val feed = Feed(tracker)
        feed.sample(-55, 0f, 10)
        assertEquals(1, tracker.measuredSectors)
        assertNull("one direction must not become a bearing", tracker.peakAzimuth)
        // The level is still reported while the bearing waits.
        assertNotNull(tracker.peakRssi)

        feed.sample(-55, 90f, 10)
        feed.sample(-55, 180f, 10)
        assertEquals(3, tracker.measuredSectors)
        assertNull("three directions are still too few", tracker.peakAzimuth)

        feed.sample(-55, 270f, 10)
        assertEquals(4, tracker.measuredSectors)
        assertNotNull("four directions are enough", tracker.peakAzimuth)
    }

    @Test
    fun probe_directions_only_show_the_measured_count() {
        val tracker = SignalDirectionTracker()
        val feed = Feed(tracker)
        feed.sample(-70, 10f, 5)
        feed.sample(-70, 40f, 5)
        assertEquals(("측정 진행: 2/12 방향"), SignalDirectionTracker.progressLabel(tracker.measuredSectors))
        assertEquals("측정 진행: 12/12 방향", SignalDirectionTracker.progressLabel(12))
        // Never count a direction twice, however many packets arrive from it.
        feed.sample(-70, 15f, 40)
        assertEquals(2, tracker.measuredSectors)
    }

    @Test
    fun reset_forgets_how_much_was_measured() {
        val tracker = SignalDirectionTracker()
        seedFourSectors(tracker)
        assertEquals(4, tracker.measuredSectors)
        tracker.reset()
        assertEquals(0, tracker.measuredSectors)
        assertNull(tracker.peakAzimuth)
    }

    @Test
    fun peak_does_not_jump_for_a_wobble_that_is_not_clearly_stronger() {
        val tracker = SignalDirectionTracker()
        seedFourSectors(tracker)
        val feed = Feed(tracker)
        // The strong direction, once the sweep above left a bearing.
        feed.sample(-55, 90f, 20)
        feed.sample(-70, 0f, 5)
        feed.sample(-70, 180f, 5)
        feed.sample(-70, 270f, 5)
        val firstPeak = tracker.peakAzimuth ?: 0f
        assertTrue(SignalDirectionTracker.circularDifference(firstPeak, 90f) <= 20f)

        // Same level from another direction: inside the hysteresis, so the needle stays.
        feed.sample(-55, 270f, 20)
        assertTrue(SignalDirectionTracker.circularDifference(tracker.peakAzimuth ?: 0f, 90f) <= 25f)
    }

    @Test
    fun peak_moves_to_a_clearly_stronger_direction() {
        val tracker = SignalDirectionTracker()
        seedFourSectors(tracker, -70)
        val feed = Feed(tracker)
        feed.sample(-55, 270f, 40)
        val peak = tracker.peakAzimuth ?: 0f
        assertTrue(
            "peak=$peak (expected near 270°)",
            SignalDirectionTracker.circularDifference(peak, 270f) <= 20f
        )
    }

    /**
     * The bug this guards against: one strong reading off a wall or through a doorway
     * pulled the needle to a direction the signal is not really coming from.
     */
    @Test
    fun a_lone_spike_does_not_beat_a_broad_lobe() {
        val tracker = SignalDirectionTracker()
        val feed = Feed(tracker)
        // A wide lobe around north-east: three neighbouring directions at -70.
        feed.sample(-70, 0f, 6)
        feed.sample(-70, 30f, 6)
        feed.sample(-70, 60f, 6)
        // One reflection to the south, only 2 dB stronger and on its own.
        feed.sample(-68, 180f, 6)
        // And two more directions elsewhere so the minimum count is met.
        feed.sample(-80, 270f, 6)

        val peak = tracker.peakAzimuth ?: 0f
        assertTrue(
            "peak=$peak should stay in the broad north-east lobe",
            SignalDirectionTracker.circularDifference(peak, 30f) <= 25f
        )
    }

    @Test
    fun a_clearly_stronger_broad_lobe_still_wins() {
        val tracker = SignalDirectionTracker()
        val feed = Feed(tracker)
        feed.sample(-80, 0f, 6)
        feed.sample(-80, 30f, 6)
        feed.sample(-80, 60f, 6)
        // Three south directions at -62: a real signal, not a stray reflection.
        feed.sample(-62, 150f, 6)
        feed.sample(-62, 180f, 6)
        feed.sample(-62, 210f, 6)

        val peak = tracker.peakAzimuth ?: 0f
        assertTrue(
            "peak=$peak (expected near 180°)",
            SignalDirectionTracker.circularDifference(peak, 180f) <= 25f
        )
    }

    @Test
    fun a_direction_keeps_an_average_instead_of_its_luckiest_packet() {
        // Walls and a body in the path throw single packets 20 dB stronger. With a max-hold a
        // sector would keep that one packet forever and the needle would follow it.
        val tracker = SignalDirectionTracker()
        val feed = Feed(tracker)
        val sector = SignalDirectionTracker.sectorOf(90f)
        // A quiet sweep round the circle first, so a bearing is allowed at all.
        feed.sample(-80, 0f, 6)
        feed.sample(-80, 90f, 6)
        feed.sample(-80, 180f, 6)
        feed.sample(-80, 270f, 6)
        val quiet = tracker.sectorValue(sector) ?: 0f
        assertEquals(-80f, quiet, 0.5f)

        feed.sample(-40, 90f)
        val afterOne = tracker.sectorValue(sector) ?: 0f
        assertTrue("quiet=$quiet afterOne=$afterOne", afterOne < -70f)
        // The "최고" line still reports the loudest level actually heard in that direction.
        assertTrue("peak=${tracker.peakRssi}", (tracker.peakRssi ?: -100f) > afterOne + 5f)

        // A signal that really is that strong keeps pulling the average up.
        repeat(12) { feed.sample(-50, 90f) }
        val settled = tracker.sectorValue(sector) ?: 0f
        assertTrue("settled=$settled", settled > -60f)
    }

    @Test
    fun a_sector_average_cannot_be_faked_by_an_impossible_spike() {
        val tracker = SignalDirectionTracker()
        val feed = Feed(tracker)
        feed.sample(-75, 0f, 6)
        feed.sample(-75, 90f, 6)
        feed.sample(-75, 180f, 6)
        feed.sample(-75, 270f, 6)
        // One wild packet in the east, quieter everywhere else.
        feed.sample(-25, 90f)
        val east = tracker.sectorValue(SignalDirectionTracker.sectorOf(90f)) ?: 0f
        val west = tracker.sectorValue(SignalDirectionTracker.sectorOf(270f)) ?: 0f
        assertTrue("east=$east west=$west", east - west < 15f)
    }

    @Test
    fun aligned_only_within_fifteen_degrees_of_the_peak() {
        val tracker = SignalDirectionTracker()
        seedFourSectors(tracker)
        val feed = Feed(tracker)
        feed.sample(-55, 42f, 20)

        val peak = tracker.peakAzimuth ?: 0f
        assertTrue(tracker.isAligned(SignalDirectionTracker.normalize(peak + 10f)))
        assertTrue(!tracker.isAligned(SignalDirectionTracker.normalize(peak + 40f)))
        // Wrapping across north must work too.
        assertTrue(tracker.isAligned(SignalDirectionTracker.normalize(peak - 12f)))
    }

    @Test
    fun sectors_are_centred_on_the_compass_points() {
        // Sector 0 spans 345°–15°, sector 1 spans 15°–45°, and so on: the bearing of a
        // reading and the arc drawn for it must agree.
        assertEquals(0, SignalDirectionTracker.sectorOf(0f))
        assertEquals(0, SignalDirectionTracker.sectorOf(14.9f))
        assertEquals(1, SignalDirectionTracker.sectorOf(15.1f))
        assertEquals(1, SignalDirectionTracker.sectorOf(29.9f))
        assertEquals(1, SignalDirectionTracker.sectorOf(30f))
        assertEquals(6, SignalDirectionTracker.sectorOf(180f))
        assertEquals(11, SignalDirectionTracker.sectorOf(344.9f))
        assertEquals(0, SignalDirectionTracker.sectorOf(345f))
        assertEquals(0, SignalDirectionTracker.sectorOf(359.9f))
        assertEquals(0, SignalDirectionTracker.sectorOf(360f))
        assertEquals(0, SignalDirectionTracker.sectorOf(-5f))
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
        seedFourSectors(tracker)
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
