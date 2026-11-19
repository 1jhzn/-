package com.jihun.signalradar.direction

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.cos
import kotlin.math.sin

/**
 * Where the needle actually lands, read from the pixels.
 *
 * The dial is a card that turns under the phone. If the sign of that turn is wrong, the
 * needle points at the mirror image of the signal and 지훈 sees "방향이 너무 안 맞아" even
 * though every number in the maths is right. So this renders the real view and looks for the
 * bright needle at four points of the dial:
 *  - phone facing the signal → needle straight up (under the phone marker);
 *  - phone facing east, signal north → needle on the left, where north is when you face east;
 *  - phone facing east, signal south → needle on the right.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE], qualifiers = "w411dp-h891dp-xxhdpi")
class RadarNeedleDirectionTest {

    private val size = 600

    private fun render(headingDegrees: Float, peakDegrees: Float?): Bitmap {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val view = RadarCompassView(context)
        view.compassAvailable = true
        view.heading = headingDegrees
        view.peakAzimuth = peakDegrees
        view.sectorPeaks = null
        view.aligned = false

        view.measure(
            View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, size, size)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.parseColor("#070B10"))
        // The card eases toward the heading a little on each frame; 30 frames settle it.
        repeat(30) { view.draw(canvas) }
        return bitmap
    }

    /** Brightest channel of the pixel at [degrees] on the screen, at 0.55 of the dial radius. */
    private fun brightness(bitmap: Bitmap, degrees: Float): Int {
        val cx = size / 2f
        val cy = size / 2f
        val outer = size / 2f - 4f * 3f
        val radius = outer * 0.55f
        val rad = Math.toRadians(degrees.toDouble())
        val x = (cx + sin(rad).toFloat() * radius).toInt()
        val y = (cy - cos(rad).toFloat() * radius).toInt()
        val color = bitmap.getPixel(x, y)
        return maxOf(Color.red(color), Color.green(color), Color.blue(color))
    }

    @Test
    fun facing_the_signal_puts_the_needle_straight_up() {
        val bitmap = render(headingDegrees = 0f, peakDegrees = 0f)
        val up = brightness(bitmap, 0f)
        val down = brightness(bitmap, 180f)
        val right = brightness(bitmap, 90f)
        assertTrue("up=$up down=$down right=$right", up > down + 60)
        assertTrue("up=$up right=$right", up > right + 60)
    }

    @Test
    fun a_signal_ahead_becomes_the_up_arrow_at_any_heading() {
        // The phone has been turned 100° and the signal was measured at 100°: still up.
        val bitmap = render(headingDegrees = 100f, peakDegrees = 100f)
        val up = brightness(bitmap, 0f)
        val down = brightness(bitmap, 180f)
        assertTrue("up=$up down=$down", up > down + 60)
    }

    @Test
    fun facing_east_puts_a_signal_to_the_north_on_the_left() {
        // Facing east, north is 90° counter-clockwise from the top of the screen.
        val bitmap = render(headingDegrees = 90f, peakDegrees = 0f)
        val left = brightness(bitmap, 270f)
        val right = brightness(bitmap, 90f)
        val up = brightness(bitmap, 0f)
        assertTrue("left=$left right=$right up=$up", left > right + 60)
        assertTrue("left=$left up=$up", left > up + 60)
    }

    @Test
    fun facing_east_puts_a_signal_to_the_south_on_the_right() {
        val bitmap = render(headingDegrees = 90f, peakDegrees = 180f)
        val left = brightness(bitmap, 270f)
        val right = brightness(bitmap, 90f)
        assertTrue("left=$left right=$right", right > left + 60)
    }

    @Test
    fun the_north_letter_sits_on_the_left_when_the_phone_faces_east() {
        // The N label is the teal one among the four cardinal points; facing east it must
        // move to the left, with the rest of the card. No needle here, so the only teal ink
        // is the letter itself.
        val bitmap = render(headingDegrees = 90f, peakDegrees = null)
        // Sample just inside the label ring, where the letters are drawn (0.47 of the dial).
        val cx = size / 2f
        val cy = size / 2f
        val outer = size / 2f - 4f * 3f
        val radius = outer * 0.47f
        fun tealNear(degrees: Float): Int {
            val rad = Math.toRadians(degrees.toDouble())
            var best = 0
            for (dx in -6..6) {
                for (dy in -6..6) {
                    val x = (cx + sin(rad).toFloat() * radius + dx).toInt()
                    val y = (cy - cos(rad).toFloat() * radius + dy).toInt()
                    if (x < 0 || y < 0 || x >= size || y >= size) continue
                    val color = bitmap.getPixel(x, y)
                    // The teal N is the only green-dominant ink on the dial.
                    if (Color.green(color) > Color.red(color) + 20 &&
                        Color.green(color) > Color.blue(color)
                    ) {
                        best = maxOf(best, Color.green(color))
                    }
                }
            }
            return best
        }
        val left = tealNear(270f)
        val right = tealNear(90f)
        assertTrue("left=$left right=$right", left > right + 40)
    }
}
