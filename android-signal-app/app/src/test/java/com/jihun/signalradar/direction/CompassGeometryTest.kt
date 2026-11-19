package com.jihun.signalradar.direction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sign that decides where the arrow points.
 *
 * 지훈: "방향도 너무 안 맞고". Both radar views turn a card under the phone, so the only thing
 * that can be wrong is the sign of that turn. Android's compass heading counts clockwise
 * from north (0 = north, 90 = east), and a real compass card turns the other way under you:
 * turn the phone clockwise and the N tick goes counter-clockwise, so N keeps pointing at the
 * same piece of the room. These tests pin that down, plus the one consequence that matters
 * on screen: facing the signal must put the needle straight up.
 */
class CompassGeometryTest {

    private fun assertAngle(expected: Float, actual: Float) {
        assertEquals("expected $expected° got $actual°", expected, actual, 0.001f)
    }

    @Test
    fun the_dial_turns_back_under_the_phone() {
        assertAngle(0f, CompassGeometry.dialRotation(0f))
        // Phone turned 90° clockwise → the card is rotated -90° (counter-clockwise).
        assertAngle(-90f, CompassGeometry.dialRotation(90f))
        // Normalised, 90° the other way is the same -90° turn seen from the other side.
        assertAngle(90f, SignalDirectionTracker.normalize(CompassGeometry.dialRotation(-90f)))
    }

    @Test
    fun the_north_tick_turns_counter_clockwise_when_the_phone_turns_clockwise() {
        // Screen angle counts clockwise from the top of the screen.
        assertAngle(0f, CompassGeometry.screenAngle(0f, 0f))
        // With the phone facing east, north has moved to the left (270° = -90°).
        assertAngle(270f, CompassGeometry.screenAngle(0f, 90f))
        // Facing south, north is behind (180°).
        assertAngle(180f, CompassGeometry.screenAngle(0f, 180f))
    }

    @Test
    fun facing_east_puts_south_on_the_right_where_it_really_is() {
        // Face east: north is on your left, south on your right.
        assertAngle(270f, CompassGeometry.screenAngle(0f, 90f))
        assertAngle(90f, CompassGeometry.screenAngle(180f, 90f))
        assertAngle(0f, CompassGeometry.screenAngle(90f, 90f))
    }

    @Test
    fun the_arrow_points_straight_up_when_the_phone_faces_the_signal() {
        for (bearing in floatArrayOf(0f, 42f, 123f, 200f, 315f, 359f)) {
            assertAngle(0f, CompassGeometry.screenAngle(bearing, bearing))
            assertTrue(CompassGeometry.pointsAt(bearing, bearing, 0.5f))
        }
    }

    @Test
    fun a_signal_ninety_degrees_to_the_right_appears_on_the_right() {
        // Phone faces north, signal is east: the arrow must sit at +90°, the right side.
        assertAngle(90f, CompassGeometry.screenAngle(90f, 0f))
        // The same signal seen with the phone turned 45° clockwise is 45° from the top.
        assertAngle(45f, CompassGeometry.screenAngle(90f, 45f))
    }

    @Test
    fun screen_angle_and_bearing_are_inverse() {
        for (bearing in floatArrayOf(0f, 17f, 90f, 181f, 270f, 359f)) {
            for (heading in floatArrayOf(0f, 45f, 90f, 210f, 350f)) {
                val screen = CompassGeometry.screenAngle(bearing, heading)
                assertAngle(bearing, CompassGeometry.bearingOf(screen, heading))
            }
        }
    }

    @Test
    fun pointing_at_it_is_a_fifteen_degree_window_either_side() {
        assertTrue(CompassGeometry.pointsAt(100f, 112f, 15f))
        assertTrue(CompassGeometry.pointsAt(100f, 88f, 15f))
        assertFalse(CompassGeometry.pointsAt(100f, 130f, 15f))
        // Across north.
        assertTrue(CompassGeometry.pointsAt(2f, 350f, 15f))
    }
}
