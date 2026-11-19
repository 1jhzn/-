package com.jihun.signalradar.nearby

import android.os.Build
import android.view.View
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The separate APK: own package, two tabs, radar wired to the same scanning screen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class NearbyRadarActivityTest {

    @Test
    fun it_is_its_own_app_so_it_installs_next_to_전파_감지기() {
        val controller = Robolectric.buildActivity(NearbyRadarActivity::class.java).setup()
        val activity = controller.get()
        assertEquals("com.jihun.signalradar.nearby", activity.packageName)
        assertEquals(
            "전파 감지기 (내 주변)",
            activity.getString(com.jihun.signalradar.R.string.app_name_nearby)
        )
        controller.pause().stop().destroy()
    }

    @Test
    fun the_bottom_bar_switches_between_distance_and_nearby() {
        val controller = Robolectric.buildActivity(NearbyRadarActivity::class.java).setup()
        val activity = controller.get()

        val distancePage = activity.findViewById<View>(com.jihun.signalradar.R.id.distancePage)
        val nearbyPage = activity.findViewById<View>(com.jihun.signalradar.R.id.nearbyPage)
        assertEquals(View.VISIBLE, distancePage.visibility)
        assertEquals(View.GONE, nearbyPage.visibility)

        activity.findViewById<View>(com.jihun.signalradar.R.id.tabNearby).performClick()
        assertEquals(View.GONE, distancePage.visibility)
        assertEquals(View.VISIBLE, nearbyPage.visibility)

        activity.findViewById<View>(com.jihun.signalradar.R.id.tabDistance).performClick()
        assertEquals(View.VISIBLE, distancePage.visibility)
        assertEquals(View.GONE, nearbyPage.visibility)

        val distanceLabel = activity.findViewById<TextView>(com.jihun.signalradar.R.id.tabDistanceLabel)
        val nearbyLabel = activity.findViewById<TextView>(com.jihun.signalradar.R.id.tabNearbyLabel)
        assertEquals("거리", distanceLabel.text.toString())
        assertEquals("내 주변", nearbyLabel.text.toString())
        controller.pause().stop().destroy()
    }

    @Test
    fun the_nearby_tab_has_a_radar_and_its_own_scan_button() {
        val controller = Robolectric.buildActivity(NearbyRadarActivity::class.java).setup()
        val activity = controller.get()

        val radar = activity.findViewById<NearbyRadarView>(com.jihun.signalradar.R.id.nearbyRadar)
        assertNotNull(radar)
        assertTrue(radar.blips.isEmpty())
        assertTrue(!radar.scanning)

        val scan = activity.findViewById<TextView>(com.jihun.signalradar.R.id.nearbyScan)
        assertEquals("▶  스캔 시작", scan.text.toString())
        val title = activity.findViewById<TextView>(com.jihun.signalradar.R.id.nearbyTitle)
        assertEquals("내 10m 안 기기 0개", title.text.toString())
        controller.pause().stop().destroy()
    }

    @Test
    fun the_distance_tab_still_holds_the_whole_v12_screen() {
        val controller = Robolectric.buildActivity(NearbyRadarActivity::class.java).setup()
        val activity = controller.get()
        for (id in listOf(
            com.jihun.signalradar.R.id.signalList,
            com.jihun.signalradar.R.id.btnScan,
            com.jihun.signalradar.R.id.btnBaseline,
            com.jihun.signalradar.R.id.chipAll,
            com.jihun.signalradar.R.id.chipNew,
            com.jihun.signalradar.R.id.statusChip,
            com.jihun.signalradar.R.id.noticeCard
        )) {
            assertNotNull("missing view $id", activity.findViewById<View>(id))
        }
        controller.pause().stop().destroy()
    }
}
