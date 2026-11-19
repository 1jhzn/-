package com.jihun.signalradar.direction

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.jihun.signalradar.R
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Draws the finder dialog into a PNG so the radar can be looked at without a phone.
 * Uses Robolectric's native graphics, which is heavier than the ordinary unit tests.
 */
@Ignore("Visual preview only: remove this to render a PNG into build/previews")
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE], qualifiers = "w411dp-h891dp-xxhdpi")
class RadarScreenshotTest {

    @Test
    fun render_finder_dialog() {
        val context = ContextThemeWrapper(
            ApplicationProvider.getApplicationContext<android.app.Application>(),
            R.style.Theme_SignalRadar
        )
        val root = LayoutInflater.from(context).inflate(R.layout.dialog_direction, null, false)
        val radar = root.findViewById<RadarCompassView>(R.id.dirRadar)

        val sectors = FloatArray(SignalDirectionTracker.SECTORS) { index ->
            val centre = index * SignalDirectionTracker.SECTOR_DEG
            val distance = SignalDirectionTracker.circularDifference(centre, 45f)
            if (distance > 100f) Float.NaN else -92f + 38f * (1f - distance / 180f)
        }
        radar.heading = 15f
        radar.peakAzimuth = 45f
        radar.sectorPeaks = sectors
        radar.aligned = true
        radar.compassAvailable = true

        root.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.AT_MOST)
        )
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        radar.invalidate()

        val bitmap = Bitmap.createBitmap(root.measuredWidth, root.measuredHeight, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))

        val dir = File(System.getProperty("signal.preview.dir") ?: "build/previews")
        dir.mkdirs()
        val out = File(dir, "radar-dialog.png")
        out.parentFile?.mkdirs()
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("wrote ${out.absolutePath} ${root.measuredWidth}x${root.measuredHeight}")
    }
}
