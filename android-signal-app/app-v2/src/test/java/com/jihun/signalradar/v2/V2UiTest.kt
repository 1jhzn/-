package com.jihun.signalradar.v2

import android.Manifest
import android.app.Application
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.jihun.signalradar.R
import com.jihun.signalradar.SignalAdapter
import com.jihun.signalradar.SignalRadarActivity
import com.jihun.signalradar.identify.Classifier
import com.jihun.signalradar.model.Signal
import com.jihun.signalradar.model.SignalSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * 전파 감지기 v2: the separate app, and the four UI changes 지훈 asked for.
 *
 *  1순위 — empty-state text at the top of the list, `스캔 중지` while scanning
 *  2순위 — no filter chip cut off, one-line card title, no duplicated description
 *  3순위 — distance steps instead of `약 1.7m`, MAC folded away in the detail card
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE], qualifiers = "w360dp-h780dp-xhdpi")
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class V2UiTest {

    private fun grantScanPermissions() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(app).grantPermissions(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
            "android.permission.NEARBY_WIFI_DEVICES"
        )
    }

    private fun bleSignal(
        rssi: Int = -50,
        smoothed: Float = -50.0f,
        title: String = "지훈의 AirPods",
        address: String = "F8:1E:DF:12:34:56"
    ) = Signal(
        id = "ble:$address",
        source = SignalSource.BLE,
        title = title,
        vendor = "애플",
        kind = "에어팟",
        detail = "에어팟 · 서비스 배터리",
        address = address,
        rssi = rssi,
        firstSeen = 0L,
        lastSeen = 1_000L,
        smoothedRssi = smoothed,
        txPower = -52,
        broadcastName = title,
        isConnected = true,
        isBonded = true
    )

    // ------------------------------------------------------------- app identity

    @Test
    fun it_is_a_separate_app_with_its_own_package_and_name() {
        val controller = Robolectric.buildActivity(V2RadarActivity::class.java).setup()
        val activity = controller.get()
        assertEquals("com.jihun.signalradar.v2", activity.packageName)
        assertEquals("전파 감지기 v2", activity.getString(R.string.app_name_v2))
        controller.pause().stop().destroy()
    }

    // -------------------------------------------------------------- 1순위: 버튼

    @Test
    fun the_scan_button_reads_stop_while_scanning_and_start_again_after() {
        grantScanPermissions()
        val controller = Robolectric.buildActivity(V2RadarActivity::class.java).setup()
        val activity = controller.get()
        val button = activity.findViewById<TextView>(R.id.btnScan)

        assertEquals("▶  스캔 시작", button.text.toString())
        button.performClick()
        assertTrue("scanning=${button.text}", button.text.contains("스캔 중지"))
        button.performClick()
        assertTrue("stopped=${button.text}", button.text.contains("스캔 시작"))
        controller.pause().stop().destroy()
    }

    @Test
    fun the_nearby_tabs_own_scan_button_reads_stop_too() {
        grantScanPermissions()
        val controller = Robolectric.buildActivity(V2RadarActivity::class.java).setup()
        val activity = controller.get()
        activity.findViewById<View>(R.id.tabNearby).performClick()

        val scan = activity.findViewById<TextView>(R.id.nearbyScan)
        assertEquals("▶  스캔 시작", scan.text.toString())
        scan.performClick()
        assertTrue("scanning=${scan.text}", scan.text.contains("스캔 중지"))
        scan.performClick()
        assertTrue("stopped=${scan.text}", scan.text.contains("스캔 시작"))
        controller.pause().stop().destroy()
    }

    @Test
    fun the_radar_fullscreen_toggle_still_works_in_the_v2_layout() {
        val controller = Robolectric.buildActivity(V2RadarActivity::class.java).setup()
        val activity = controller.get()
        activity.findViewById<View>(R.id.tabNearby).performClick()

        val toggle = activity.findViewById<TextView>(R.id.nearbyFullscreen)
        assertEquals("⛶ 전체화면", toggle.text.toString())
        toggle.performClick()
        assertEquals(View.GONE, activity.findViewById<View>(R.id.headerBar).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.tabBar).visibility)
        assertEquals("✕ 축소", toggle.text.toString())

        activity.onBackPressedDispatcher.onBackPressed()
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.headerBar).visibility)
        assertEquals("⛶ 전체화면", toggle.text.toString())
        controller.pause().stop().destroy()
    }

    // --------------------------------------------------------- 1순위: 빈 화면 위치

    @Test
    fun the_empty_message_sits_at_the_top_of_the_list_area() {
        val controller = Robolectric.buildActivity(V2RadarActivity::class.java).setup()
        val activity = controller.get()
        val root = activity.findViewById<View>(android.R.id.content)
        measure(root, activity, 360, 640)

        val emptyState = activity.findViewById<LinearLayout>(R.id.emptyState)
        val emptyText = activity.findViewById<TextView>(R.id.emptyText)
        val list = activity.findViewById<View>(R.id.signalList)
        assertEquals(View.VISIBLE, emptyState.visibility)
        assertTrue("empty state must hug the top", emptyState.gravity and Gravity.TOP != 0)

        // The text is in the upper part of the list area, not at the bottom of a blank screen.
        val offset = emptyText.top - emptyState.top
        assertTrue(
            "the message must start at the top of the list area: offset=$offset listHeight=${list.height}",
            offset <= dp(activity, 80)
        )
        assertTrue(
            "the message must not sit in the lower part of the list area: " +
                "offset=${offset} listHeight=${list.height}",
            offset < list.height * 2 / 3
        )
        controller.pause().stop().destroy()
    }

    // ---------------------------------------------------------- 2순위: 필터 칩

    @Test
    fun no_filter_chip_is_cut_off_on_a_360dp_or_412dp_phone() {
        val controller = Robolectric.buildActivity(V2RadarActivity::class.java).setup()
        val activity = controller.get()
        val root = activity.findViewById<View>(android.R.id.content)
        val chips = listOf(
            R.id.chipAll, R.id.chipNew, R.id.chipBle, R.id.chipWifi, R.id.chipCell
        )

        for (widthDp in listOf(320, 360, 412)) {
            measure(root, activity, widthDp, 780)
            for (id in chips) {
                val chip = activity.findViewById<TextView>(id)
                val needed = chip.paint.measureText(chip.text.toString()) +
                    chip.paddingLeft + chip.paddingRight
                assertTrue(
                    "width=${widthDp}dp chip=${chip.text} width=${chip.width} needs=$needed",
                    chip.width + 1 >= needed
                )
                // Inside the screen, not hanging off the right edge.
                assertTrue(
                    "width=${widthDp}dp chip=${chip.text} right=${chip.right}",
                    chip.right <= root.width
                )
            }
        }
        controller.pause().stop().destroy()
    }

    // ------------------------------------------------------- 2순위: 카드 한 줄

    @Test
    fun a_card_has_a_one_line_title_and_one_summary_line_without_the_address() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val adapter = SignalAdapter(
            R.layout.item_signal_v2,
            SignalAdapter.RowStyle.SUMMARY
        ) { }
        adapter.submit(
            listOf(
                bleSignal(),
                bleSignal(
                    rssi = -95, smoothed = -95f, title = "이름 없는 블루투스 기기",
                    address = "02:00:00:00:00:01"
                )
            )
        )
        val list = RecyclerView(context)
        list.layoutManager = LinearLayoutManager(context)
        list.adapter = adapter
        list.measure(
            View.MeasureSpec.makeMeasureSpec(dp(context, 360), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dp(context, 900), View.MeasureSpec.EXACTLY)
        )
        list.layout(0, 0, dp(context, 360), dp(context, 900))
        org.robolectric.shadows.ShadowLooper.idleMainLooper()

        val row = list.getChildAt(0)
        assertNotNull("the list must draw a row", row)
        val title = row.findViewById<TextView>(R.id.itemTitle)
        val summary = row.findViewById<TextView>(R.id.itemDetail)
        val distance = row.findViewById<TextView>(R.id.itemDistance)

        assertEquals("the title is one line", 1, title.maxLines)
        assertEquals(TextUtils.TruncateAt.END, title.ellipsize)
        assertEquals("블루투스 · 애플", summary.text.toString())

        // 3순위: the distance is a step, never "약 1.7m".
        assertEquals("매우 가까움", distance.text.toString())
        assertFalse(distance.text.contains("약"))
        assertFalse(distance.text.contains("m"))

        // The address (and the old vendor/description lines) are not on the card at all.
        assertNull("no separate vendor line", row.findViewById<View>(R.id.itemVendor))
        assertNull("no address line", row.findViewById<View>(R.id.itemAddress))
        for (index in 0 until list.childCount) {
            val child = list.getChildAt(index)
            collectText(child).forEach { text ->
                assertFalse("row text must not print a MAC: $text", text.contains("F8:1E"))
                assertFalse("row text must not print a MAC: $text", text.contains("02:00:00"))
            }
        }

        val second = list.getChildAt(1)
        val secondDistance = second.findViewById<TextView>(R.id.itemDistance)
        assertEquals("아주 멀어요", secondDistance.text.toString())
    }

    @Test
    fun the_title_never_grows_past_one_line_even_with_a_long_name() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val adapter = SignalAdapter(R.layout.item_signal_v2, SignalAdapter.RowStyle.SUMMARY) { }
        adapter.submit(
            listOf(bleSignal(title = "아주아주아주아주아주아주아주아주 긴 이름의 블루투스 이어폰"))
        )
        val list = RecyclerView(context)
        list.layoutManager = LinearLayoutManager(context)
        list.adapter = adapter
        list.measure(
            View.MeasureSpec.makeMeasureSpec(dp(context, 360), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dp(context, 400), View.MeasureSpec.EXACTLY)
        )
        list.layout(0, 0, dp(context, 360), dp(context, 400))
        org.robolectric.shadows.ShadowLooper.idleMainLooper()

        val title = list.getChildAt(0).findViewById<TextView>(R.id.itemTitle)
        assertNotNull("the title must be laid out", title.layout)
        assertEquals("the title stays on one line", 1, title.layout.lineCount)
        assertTrue("the long title keeps the end ellipsis", title.layout.getEllipsisCount(0) > 0)
    }

    // -------------------------------------------------------- 3순위: 거리 단계

    @Test
    fun distances_are_read_as_steps_and_the_old_metres_are_still_available() {
        assertEquals("매우 가까움", Classifier.distanceStep(0.4))
        assertEquals("가까움", Classifier.distanceStep(2.9))
        assertEquals("보통", Classifier.distanceStep(7.9))
        assertEquals("멀어요", Classifier.distanceStep(19.9))
        assertEquals("아주 멀어요", Classifier.distanceStep(40.0))

        // -70 dBm BLE is 2.5 m, which is one step, not a decimal number.
        assertEquals("가까움", Classifier.distanceStepLabel(-70, SignalSource.BLE))
        assertEquals("매우 가까움", Classifier.distanceStepLabel(-48, SignalSource.BLE, txPower = -50))
        assertNull(Classifier.distanceStepLabel(-70, SignalSource.CELL))

        // v1 wording is untouched: the other two apps keep their metre estimates.
        assertEquals("약 2.5m", Classifier.distanceLabel(-70, SignalSource.BLE))
    }

    // -------------------------------------------------------- 3순위: MAC 접기

    @Test
    fun the_detail_card_folds_the_mac_behind_a_toggle() {
        val controller = Robolectric.buildActivity(V2RadarActivity::class.java).setup()
        val activity = controller.get()
        val method = SignalRadarActivity::class.java
            .getDeclaredMethod("showDetails", Signal::class.java)
        method.isAccessible = true
        method.invoke(activity, bleSignal())
        val dialog = ShadowDialog.getLatestDialog()
        assertTrue(dialog.isShowing)

        val body = dialog.findViewById<TextView>(R.id.detailBody)
        val toggle = dialog.findViewById<TextView>(R.id.detailAddressToggle)
        val address = dialog.findViewById<TextView>(R.id.detailAddress)
        val subtitle = dialog.findViewById<TextView>(R.id.detailSubtitle)

        assertNotNull(toggle)
        assertNotNull(address)
        assertEquals("세부 주소 보기 ▾", toggle.text.toString())
        assertEquals(View.GONE, address.visibility)
        assertFalse("collapsed body must not print the MAC", body.text.contains("F8:1E"))
        assertFalse("collapsed body must not print an address line", body.text.contains("주소:"))
        assertFalse("the subtitle must not carry the MAC", subtitle.text.contains("F8:1E"))

        toggle.performClick()
        assertEquals(View.VISIBLE, address.visibility)
        assertEquals("F8:1E:DF:12:34:56", address.text.toString())
        assertEquals("세부 주소 접기 ▴", toggle.text.toString())

        toggle.performClick()
        assertEquals(View.GONE, address.visibility)
        assertEquals("세부 주소 보기 ▾", toggle.text.toString())
        dialog.dismiss()
    }

    @Test
    fun the_detail_card_shows_the_distance_as_a_step() {
        val controller = Robolectric.buildActivity(V2RadarActivity::class.java).setup()
        val activity = controller.get()
        val method = SignalRadarActivity::class.java
            .getDeclaredMethod("showDetails", Signal::class.java)
        method.isAccessible = true
        method.invoke(activity, bleSignal())
        val dialog = ShadowDialog.getLatestDialog()
        val body = dialog.findViewById<TextView>(R.id.detailBody)
        assertTrue(body.text.toString(), body.text.contains("대략 거리: 매우 가까움"))
        assertFalse(body.text.toString(), body.text.contains("약 "))
        dialog.dismiss()
    }

    // ---------------------------------------------------------------- helpers

    private fun measure(root: View, activity: android.app.Activity, widthDp: Int, heightDp: Int) {
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        val w = View.MeasureSpec.makeMeasureSpec(dp(activity, widthDp), View.MeasureSpec.EXACTLY)
        val h = View.MeasureSpec.makeMeasureSpec(dp(activity, heightDp), View.MeasureSpec.EXACTLY)
        root.measure(w, h)
        root.layout(0, 0, dp(activity, widthDp), dp(activity, heightDp))
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
    }

    private fun dp(context: android.content.Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private fun collectText(view: View): List<String> {
        val texts = ArrayList<String>()
        if (view is TextView) texts.add(view.text?.toString().orEmpty())
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) texts.addAll(collectText(view.getChildAt(index)))
        }
        return texts
    }
}
