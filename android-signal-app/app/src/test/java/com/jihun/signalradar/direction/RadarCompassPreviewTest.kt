package com.jihun.signalradar.direction

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.jihun.signalradar.R
import com.jihun.signalradar.model.Signal
import com.jihun.signalradar.model.SignalSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The direction finder touches custom drawing, a custom view inside a dialog layout and
 * sensor plumbing — all of it is walked through here so a crash shows up in the build
 * rather than on the phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class RadarCompassPreviewTest {

    @Test
    fun dialog_layout_inflates_and_the_radar_measures_square() {
        val context = ContextThemeWrapper(
            ApplicationProvider.getApplicationContext<android.app.Application>(),
            R.style.Theme_SignalRadar
        )
        val root = LayoutInflater.from(context).inflate(R.layout.dialog_direction, null, false)
        val radar = root.findViewById<RadarCompassView>(R.id.dirRadar)
        assertNotNull(radar)
        assertNotNull(root.findViewById<View>(R.id.dirReset))
        assertNotNull(root.findViewById<View>(R.id.dirAligned))

        root.measure(
            View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.AT_MOST)
        )
        root.layout(0, 0, 720, root.measuredHeight)

        assertEquals(radar.width, radar.height)
        assertTrue("radar width=${radar.width}", radar.width > 200)
    }

    @Test
    fun radar_draws_in_every_state_without_crashing() {
        val context = ContextThemeWrapper(
            ApplicationProvider.getApplicationContext<android.app.Application>(),
            R.style.Theme_SignalRadar
        )
        val root = LayoutInflater.from(context).inflate(R.layout.dialog_direction, null, false)
        val radar = root.findViewById<RadarCompassView>(R.id.dirRadar)
        root.measure(
            View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.AT_MOST)
        )
        root.layout(0, 0, 720, root.measuredHeight)
        val canvas = Canvas(Bitmap.createBitmap(720, root.measuredHeight, Bitmap.Config.ARGB_8888))

        // Nothing heard yet.
        radar.heading = 0f
        radar.peakAzimuth = null
        radar.sectorPeaks = null
        radar.draw(canvas)

        // A completed sweep with a peak, some sectors empty.
        radar.heading = 200f
        radar.peakAzimuth = 45f
        radar.sectorPeaks = FloatArray(SignalDirectionTracker.SECTORS) { index ->
            if (index % 3 == 0) Float.NaN else -95f + index * 3f
        }
        radar.aligned = true
        radar.draw(canvas)

        // A phone without a compass still draws the dial.
        radar.compassAvailable = false
        radar.draw(canvas)

        // And with a real tracker result.
        val tracker = SignalDirectionTracker()
        var now = 0L
        repeat(60) {
            now += 200
            tracker.onSample(-70 + (it % 5), 120f, now)
        }
        radar.compassAvailable = true
        radar.peakAzimuth = tracker.peakAzimuth
        radar.sectorPeaks = FloatArray(SignalDirectionTracker.SECTORS) { i ->
            tracker.sectorValue(i) ?: Float.NaN
        }
        radar.draw(canvas)
    }

    @Test
    fun the_finder_ticks_with_a_live_signal_and_closes_cleanly() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        var now = 0L
        var rssi = -70
        val signal = Signal(
            id = "ble:tick",
            source = SignalSource.BLE,
            title = "테스트 기기",
            vendor = null,
            kind = null,
            detail = null,
            address = "AA:BB",
            rssi = rssi,
            firstSeen = 0L,
            lastSeen = 0L
        )
        val dialog = DirectionFinderDialog(
            context,
            signal,
            {
                now += 400
                rssi -= 2
                signal.copy(rssi = rssi, lastSeen = now)
            },
            { false }
        )
        dialog.show()
        org.robolectric.shadows.ShadowLooper.idleMainLooper(3, java.util.concurrent.TimeUnit.SECONDS)
        dialog.dismiss()
        org.robolectric.shadows.ShadowLooper.idleMainLooper(1, java.util.concurrent.TimeUnit.SECONDS)
    }

    @Test
    fun the_dialog_opens_and_closes_without_a_crash() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val signal = Signal(
            id = "ble:AA:BB",
            source = SignalSource.BLE,
            title = "테스트 기기",
            vendor = "애플",
            kind = "에어팟",
            detail = null,
            address = "AA:BB",
            rssi = -60,
            firstSeen = 0L,
            lastSeen = 0L
        )
        val dialog = DirectionFinderDialog(context, signal, { signal }, { false })
        dialog.show()
        dialog.dismiss()
    }
}
