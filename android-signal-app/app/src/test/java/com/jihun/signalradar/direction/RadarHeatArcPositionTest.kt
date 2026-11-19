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
 * The radar draws its heat arcs under `canvas.rotate(-heading)`, one arc per sector centred
 * on `index * 30°`. This test paints one loud sector (south) and one quiet compass and then
 * reads the actual pixels: the warm arc must sit where south is, and it must not be shifted
 * by half a sector — the 15° error that made the arrow point slightly off.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE], qualifiers = "w411dp-h891dp-xxhdpi")
class RadarHeatArcPositionTest {

    private fun render(headingDegrees: Float = 0f): Bitmap {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val view = RadarCompassView(context)
        view.compassAvailable = true
        view.heading = headingDegrees
        val sectors = FloatArray(SignalDirectionTracker.SECTORS) { -80f }
        sectors[6] = -50f // south
        view.sectorPeaks = sectors
        view.peakAzimuth = null
        view.aligned = false

        view.measure(
            View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, 600, 600)
        val bitmap = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.parseColor("#070B10"))
        // The dial eases toward the phone's heading, so a few frames are needed before the
        // card has actually turned.
        repeat(12) { view.draw(canvas) }
        return bitmap
    }

    /** Red channel of the pixel at [degrees], on the ring where the heat arcs are drawn. */
    private fun warmth(bitmap: Bitmap, degrees: Float): Int {
        val cx = 300f
        val cy = 300f
        val outer = 300f - 4f * 3f
        val radius = outer * 0.70f
        val rad = Math.toRadians(degrees.toDouble())
        val x = (cx + sin(rad).toFloat() * radius).toInt()
        val y = (cy - cos(rad).toFloat() * radius).toInt()
        return Color.red(bitmap.getPixel(x, y))
    }

    @Test
    fun the_warm_arc_sits_on_the_sector_it_was_measured_in() {
        val bitmap = render()
        val south = warmth(bitmap, 180f)
        val north = warmth(bitmap, 0f)
        val east = warmth(bitmap, 90f)
        assertTrue("south=$south north=$north", south > north + 40)
        assertTrue("south=$south east=$east", south > east + 40)
    }

    @Test
    fun the_arc_is_not_shifted_by_half_a_sector() {
        val bitmap = render()
        // Sector 6 covers 165°–195°. Both edges must be warm; a 15° shift would leave
        // 170° cold while 200° lit up instead.
        val nearStart = warmth(bitmap, 170f)
        val centre = warmth(bitmap, 180f)
        val beyondEnd = warmth(bitmap, 200f)
        assertTrue("170°=$nearStart 180°=$centre", nearStart > 150)
        assertTrue("200°=$beyondEnd", beyondEnd < centre)
    }

    @Test
    fun turning_the_phone_turns_the_arc_with_it() {
        // With the phone facing east, the same south reading appears 90° counter-clockwise
        // on the card (a real compass behaves like that).
        val bitmap = render(headingDegrees = 90f)
        val drawnAt = warmth(bitmap, 90f)
        val drawnWrong = warmth(bitmap, 180f)
        assertTrue("90°=$drawnAt 180°=$drawnWrong", drawnAt > drawnWrong + 40)
    }
}
