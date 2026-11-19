package com.jihun.signalradar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "새 신호가 잡혔어요" buzz: one faint tap at most every ten seconds, no matter how many
 * devices appear at once (지훈: "너무 진동이 심하니까 줄여주고").
 */
class NewSignalBuzzTest {

    @Test
    fun the_first_new_signal_may_tap() {
        assertTrue(NewSignalBuzz().allow(1_000L))
    }

    @Test
    fun a_room_full_of_devices_still_taps_only_once() {
        val buzz = NewSignalBuzz()
        var allowed = 0
        // Fifteen devices found in the same refresh.
        for (device in 0 until 15) {
            if (buzz.allow(5_000L)) allowed++
        }
        assertEquals(1, allowed)
    }

    @Test
    fun ten_seconds_of_quiet_after_each_tap() {
        val buzz = NewSignalBuzz()
        assertTrue(buzz.allow(0L))
        assertFalse(buzz.allow(9_999L))
        assertTrue(buzz.allow(10_000L))
        assertFalse(buzz.allow(19_000L))
        assertTrue(buzz.allow(20_500L))
    }

    @Test
    fun saving_a_new_baseline_lets_the_next_signal_tap_right_away() {
        val buzz = NewSignalBuzz()
        assertTrue(buzz.allow(1_000L))
        assertFalse(buzz.allow(2_000L))
        buzz.reset()
        assertTrue(buzz.allow(2_000L))
    }

    @Test
    fun the_tap_is_short_and_faint() {
        assertEquals(18L, NewSignalBuzz.DURATION_MS)
        // VibrationEffect amplitudes run 1..255; 1 is the weakest the motor can give.
        assertEquals(1, NewSignalBuzz.AMPLITUDE)
        assertEquals(10_000L, NewSignalBuzz.MIN_GAP_MS)
    }
}
