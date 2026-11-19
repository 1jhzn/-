package com.jihun.signalradar.direction

/**
 * The one place that decides how a compass bearing becomes a point on the screen.
 *
 * Both radar views draw a card that turns under the phone, so the sign has to be the same
 * everywhere or the arrow ends up pointing at the mirror image of the signal — the bug
 * 지훈 saw as "방향도 너무 안 맞고".
 *
 * The screen angle here is measured clockwise from the top of the screen (where the phone
 * marker is), which is also how the Canvas draws the dial:
 *  - the dial itself is rotated by [dialRotation], i.e. -heading. Turning the phone
 *    clockwise therefore turns the N tick counter-clockwise, exactly like a real compass
 *    card, and N stays over the same piece of the room;
 *  - a bearing is drawn at [screenAngle] = bearing - heading. Point the phone at the
 *    signal (bearing == heading) and it lands at 0°, straight up under the marker.
 *
 * Worked example, the one the tests hold: phone facing east (heading 90°). North is 90°
 * counter-clockwise on screen (left), south is 90° clockwise (right) — right is where
 * south is when you face east.
 */
object CompassGeometry {

    /** Rotation the canvas gets for a compass [heading]: negative, so the card turns back. */
    fun dialRotation(heading: Float): Float = -SignalDirectionTracker.normalize(heading)

    /** Where a compass [bearing] appears on screen, in degrees clockwise from the top. */
    fun screenAngle(bearing: Float, heading: Float): Float =
        SignalDirectionTracker.normalize(bearing - heading)

    /** The compass bearing a point at [screenAngle] corresponds to, for the given heading. */
    fun bearingOf(screenAngle: Float, heading: Float): Float =
        SignalDirectionTracker.normalize(screenAngle + heading)

    /** True when the phone's top is within [tolerance] degrees of the bearing. */
    fun pointsAt(bearing: Float, heading: Float, tolerance: Float): Boolean =
        SignalDirectionTracker.circularDifference(bearing, heading) <= tolerance
}
