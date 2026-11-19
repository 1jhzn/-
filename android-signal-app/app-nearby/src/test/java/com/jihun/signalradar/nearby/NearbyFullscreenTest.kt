package com.jihun.signalradar.nearby

import android.os.Build
import android.view.View
import android.widget.TextView
import com.jihun.signalradar.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The ⛶ 전체화면 / ✕ 축소 toggle on the 10 m radar: the app chrome leaves the screen, the
 * radar itself gets the room and the back key steps out of fullscreen without closing the
 * app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class NearbyFullscreenTest {

    private fun layout(activity: android.app.Activity, width: Int = 1080, height: Int = 2100) {
        val root = activity.findViewById<View>(android.R.id.content)
        val specW = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
        val specH = View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        root.measure(specW, specH)
        root.layout(0, 0, width, height)
    }

    private fun visibility(activity: NearbyRadarActivity, id: Int): Int =
        activity.findViewById<View>(id).visibility

    @Test
    fun the_toggle_turns_the_radar_screen_on_and_off() {
        val controller = Robolectric.buildActivity(NearbyRadarActivity::class.java).setup()
        val activity = controller.get()
        activity.findViewById<View>(R.id.tabNearby).performClick()

        val toggle = activity.findViewById<TextView>(R.id.nearbyFullscreen)
        assertEquals("⛶ 전체화면", toggle.text.toString())
        assertFalse(activity.isFullscreen)

        // ---- on: header, tab bar, legends, notes and the list make way
        toggle.performClick()
        assertTrue(activity.isFullscreen)
        assertEquals("✕ 축소", toggle.text.toString())
        assertEquals(View.GONE, visibility(activity, R.id.headerBar))
        assertEquals(View.GONE, visibility(activity, R.id.tabBar))
        assertEquals(View.GONE, visibility(activity, R.id.nearbyLegendRadios))
        assertEquals(View.GONE, visibility(activity, R.id.nearbyLegendDots))
        assertEquals(View.GONE, visibility(activity, R.id.nearbyNote))
        assertEquals(View.GONE, visibility(activity, R.id.nearbyLegendHint))
        assertEquals(View.GONE, visibility(activity, R.id.nearbyListHeader))
        assertEquals(View.GONE, visibility(activity, R.id.nearbyListBox))

        // …while the radar, the scan button and the heading stay usable on top of it.
        assertEquals(View.VISIBLE, visibility(activity, R.id.nearbyRadar))
        assertEquals(View.VISIBLE, visibility(activity, R.id.nearbyScan))
        assertEquals(View.VISIBLE, visibility(activity, R.id.nearbyHeading))

        // ---- off: every one of them comes back
        toggle.performClick()
        assertFalse(activity.isFullscreen)
        assertEquals("⛶ 전체화면", toggle.text.toString())
        assertEquals(View.VISIBLE, visibility(activity, R.id.headerBar))
        assertEquals(View.VISIBLE, visibility(activity, R.id.tabBar))
        assertEquals(View.VISIBLE, visibility(activity, R.id.nearbyLegendRadios))
        assertEquals(View.VISIBLE, visibility(activity, R.id.nearbyLegendDots))
        assertEquals(View.VISIBLE, visibility(activity, R.id.nearbyNote))
        assertEquals(View.VISIBLE, visibility(activity, R.id.nearbyLegendHint))
        assertEquals(View.VISIBLE, visibility(activity, R.id.nearbyListHeader))
        assertEquals(View.VISIBLE, visibility(activity, R.id.nearbyListBox))
        controller.pause().stop().destroy()
    }

    @Test
    fun the_radar_gets_more_room_than_in_the_normal_screen() {
        val controller = Robolectric.buildActivity(NearbyRadarActivity::class.java).setup()
        val activity = controller.get()
        activity.findViewById<View>(R.id.tabNearby).performClick()
        layout(activity)
        val radar = activity.findViewById<View>(R.id.nearbyRadar)
        val normalDiameter = minOf(radar.width, radar.height)
        assertTrue("radar should have been laid out", normalDiameter > 0)

        activity.findViewById<TextView>(R.id.nearbyFullscreen).performClick()
        layout(activity)
        var fullDiameter = minOf(radar.width, radar.height)
        // The square card is sized from a layout listener; give the second pass a chance.
        layout(activity)
        fullDiameter = minOf(radar.width, radar.height)

        // The dial is drawn as a circle inside the card, so the shorter side is its size.
        assertTrue(
            "fullscreen dial should be bigger ($normalDiameter -> $fullDiameter)",
            fullDiameter > normalDiameter
        )
        // …and the card keeps a square shape instead of stretching into a tall empty panel.
        assertEquals(radar.width, radar.height)
        controller.pause().stop().destroy()
    }

    @Test
    fun back_leaves_fullscreen_and_only_a_second_back_closes_the_app() {
        val controller = Robolectric.buildActivity(NearbyRadarActivity::class.java).setup()
        val activity = controller.get()
        activity.findViewById<View>(R.id.tabNearby).performClick()
        activity.findViewById<TextView>(R.id.nearbyFullscreen).performClick()
        assertTrue(activity.isFullscreen)

        activity.onBackPressedDispatcher.onBackPressed()

        assertFalse("back should have left fullscreen", activity.isFullscreen)
        assertFalse("the app must stay open", activity.isFinishing)
        assertEquals(View.VISIBLE, visibility(activity, R.id.headerBar))
        assertEquals(View.VISIBLE, visibility(activity, R.id.tabBar))
        assertEquals("⛶ 전체화면", activity.findViewById<TextView>(R.id.nearbyFullscreen).text.toString())

        // With fullscreen already gone, back means what it always means.
        activity.onBackPressedDispatcher.onBackPressed()
        assertTrue(activity.isFinishing)
        controller.pause().stop().destroy()
    }

    @Test
    fun the_toggle_is_only_on_the_nearby_tab() {
        val controller = Robolectric.buildActivity(NearbyRadarActivity::class.java).setup()
        val activity = controller.get()
        // The 거리 tab never shows the radar, so its fullscreen button goes with the page.
        assertEquals(View.GONE, visibility(activity, R.id.nearbyPage))
        activity.findViewById<View>(R.id.tabNearby).performClick()
        assertEquals(View.VISIBLE, visibility(activity, R.id.nearbyFullscreen))
        controller.pause().stop().destroy()
    }
}
