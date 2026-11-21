package com.jihun.signalradar.v2

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.os.Looper
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
import com.jihun.signalradar.SignalRadarActivity
import com.jihun.signalradar.SignalRepository
import com.jihun.signalradar.identify.Classifier
import com.jihun.signalradar.model.Signal
import com.jihun.signalradar.model.SignalSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/**
 * 전파 감지기 v2.1 — the Find-My style pass:
 *
 *  - the pastel top area with the nearest-signal callout, the bright radar and the 기준 card
 *  - the white sheet: grab handle, big title, round scan button, two-row chips, top-of-list
 *    empty state, step badges, no MAC address
 *  - the three pill tabs (신호 · 내 주변 · 기준) and radar fullscreen
 *  - the orb finder: dark at first, emerald once 매우 가까움 is held
 *  - everything that already worked (new-signal banner, MAC fold, GATT wording, steps,
 *    vibration switch) still works
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
        firstSeen = System.currentTimeMillis() - 30_000,
        lastSeen = System.currentTimeMillis(),
        smoothedRssi = smoothed,
        txPower = -52,
        broadcastName = title,
        isConnected = true,
        isBonded = true
    )

    private fun wifiSignal(rssi: Int = -62, ssid: String = "iptime_5G") = Signal(
        id = "wifi:$ssid",
        source = SignalSource.WIFI,
        title = ssid,
        vendor = "ipTIME(EFM)",
        kind = "ipTIME 공유기",
        detail = "5GHz · 44채널",
        address = "00:08:9F:00:00:01",
        rssi = rssi,
        firstSeen = System.currentTimeMillis() - 30_000,
        lastSeen = System.currentTimeMillis(),
        smoothedRssi = rssi.toFloat(),
        frequencyMhz = 5745
    )

    private fun repoOf(activity: V2RadarActivity): SignalRepository {
        val field = SignalRadarActivity::class.java.getDeclaredField("repo")
        field.isAccessible = true
        return field.get(activity) as SignalRepository
    }

    private fun refresh(seconds: Long = 1L) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(seconds, TimeUnit.SECONDS)
    }

    // ------------------------------------------------------------- app identity

    @Test
    fun it_is_a_separate_app_named_전파_with_its_own_package_name_and_the_29_version() {
        val controller = Robolectric.buildActivity(V2RadarActivity::class.java).setup()
        val activity = controller.get()
        assertEquals("com.jihun.signalradar.v2", activity.packageName)
        assertEquals("전파", activity.getString(R.string.app_name_v2))

        // The header title is the same word 지훈 sees under the launcher icon.
        val title = activity.findViewById<android.widget.TextView>(R.id.v2AppTitle)
        assertEquals("전파", title.text.toString())

        val info = activity.packageManager.getPackageInfo(activity.packageName, 0)
        assertEquals("2.9", info.versionName)
        assertEquals(10, info.versionCode)
        controller.pause().stop().destroy()
    }

    // ------------------------------------------------------------ scan button

    @Test
    fun the_round_scan_button_reads_start_and_stop() {
        grantScanPermissions()
        val controller = Robolectric.buildActivity(V2RadarActivity::class.java).setup()
        val activity = controller.get()
        val button = activity.findViewById<TextView>(R.id.btnScan)

        assertEquals("▶ 스캔 시작", button.text.toString())
        button.performClick()
        assertEquals("■ 스캔 중지", button.text.toString())
        button.performClick()
        assertEquals("▶ 스캔 시작", button.text.toString())
        controller.pause().stop().destroy()
    }

    // ------------------------------------------------------------------ tabs

    @Test
    fun the_three_pill_tabs_switch_the_sheet_and_its_top_area() {
        val controller = Robolectric.buildActivity(V2RadarActivity::class.java).setup()
        val activity = controller.get()
        val title = activity.findViewById<TextView>(R.id.v2SheetTitle)

        assertEquals("주변 신호", title.text.toString())
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.v2Callout).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.v2RadarBox).visibility)

        activity.findViewById<View>(R.id.tabNearby).performClick()
        assertEquals("내 주변 레이더", title.text.toString())
        assertEquals(View.GONE, activity.findViewById<View>(R.id.v2Callout).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.v2RadarBox).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.nearbyPage).visibility)

        activity.findViewById<View>(R.id.tabRecord).performClick()
        assertEquals("기준 기록", title.text.toString())
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.v2RecordHero).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.recordPage).visibility)

        activity.findViewById<View>(R.id.tabSignal).performClick()
        assertEquals("주변 신호", title.text.toString())
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.signalPage).visibility)
        controller.pause().stop().destroy()
    }

    @Test
    fun radar_fullscreen_hides_the_sheet_and_back_brings_it_back() {
        val controller = Robolectric.buildActivity(V2RadarActivity::class.java).setup()
        val activity = controller.get()
        activity.findViewById<View>(R.id.tabNearby).performClick()

        val toggle = activity.findViewById<TextView>(R.id.nearbyFullscreen)
        val sheet = activity.findViewById<View>(R.id.v2Sheet)
        assertEquals("⛶ 전체화면", toggle.text.toString())

        toggle.performClick()
        assertEquals(View.GONE, sheet.visibility)
        assertEquals("✕ 축소", toggle.text.toString())

        activity.onBackPressedDispatcher.onBackPressed()
        assertEquals(View.VISIBLE, sheet.visibility)
        assertEquals("⛶ 전체화면", toggle.text.toString())
        controller.pause().stop().destroy()
    }

    // --------------------------------------------------------- empty message

    @Test
    fun the_empty_message_sits_at_the_top_of_the_list_area() {
        val controller = Robolectric.buildActivity(V2RadarActivity::class.java).setup()
        val activity = controller.get()
        val root = activity.findViewById<View>(android.R.id.content)
        measure(root, activity, 360, 780)

        val emptyState = activity.findViewById<LinearLayout>(R.id.emptyState)
        val emptyText = activity.findViewById<TextView>(R.id.emptyText)
        assertEquals(View.VISIBLE, emptyState.visibility)
        assertTrue("empty state must hug the top", emptyState.gravity and Gravity.TOP != 0)

        // The icon and the text start right under the top of the list area (26dp + 18dp),
        // never in the middle or at the bottom of a blank screen.
        val offset = emptyText.top - emptyState.top
        assertTrue("the message must start at the top: offset=$offset", offset <= dp(activity, 70))
        // It is the icon plus a small gap, not a block pushed down the page.
        assertTrue(
            "the message sits right under the icon: top=${emptyText.top}",
            emptyText.top <= dp(activity, 70)
        )
        controller.pause().stop().destroy()
    }

    // ------------------------------------------------------------- filter chips

    @Test
    fun no_filter_chip_is_cut_off_on_a_320_360_or_412dp_phone() {
        val controller = Robolectric.buildActivity(V2RadarActivity::class.java).setup()
        val activity = controller.get()
        val root = activity.findViewById<View>(android.R.id.content)
        // v2.3 is Bluetooth only: 전체 · 🚨 새 신호 · 📌 내 폰 연결, then v2.4's 📏 예상 거리
        // on its own second row.
        val chips = listOf(R.id.chipAll, R.id.chipNew, R.id.chipBle, R.id.chipDistance)
        repoOf(activity).upsert(listOf(bleSignal(), bleSignal(address = "AA:BB:CC:DD:EE:FF")))
        refresh()

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
                assertTrue(
                    "width=${widthDp}dp chip=${chip.text} right=${chip.right}",
                    chip.right <= root.width
                )
            }
        }

        // The radios this screen no longer shows have no chip to press.
        assertEquals(View.GONE, activity.findViewById<View>(R.id.chipWifi).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.chipCell).visibility)
        controller.pause().stop().destroy()
    }

    @Test
    fun the_chip_row_counts_bluetooth_and_offers_my_own_devices() {
        grantScanPermissions()
        val controller = Robolectric.buildActivity(V2RadarActivity::class.java).setup()
        val activity = controller.get()
        val repo = repoOf(activity)
        repo.upsert(
            listOf(
                bleSignal(),
                bleSignal(address = "AA:BB:CC:DD:EE:FF", title = "민수의 Galaxy Buds"),
                bleSignal(address = "02:00:00:00:00:01", title = "이름 없는 기기").copy(
                    isConnected = false,
                    isBonded = false
                )
            )
        )
        refresh()

        assertEquals("전체 3", activity.findViewById<TextView>(R.id.chipAll).text.toString())
        assertEquals("📌 내 폰 연결 2", activity.findViewById<TextView>(R.id.chipBle).text.toString())

        // Pressing it narrows the list to the two devices paired with this phone.
        activity.findViewById<View>(R.id.chipBle).performClick()
        refresh()
        assertEquals(2, activity.findViewById<RecyclerView>(R.id.v2SignalList).adapter?.itemCount)
        activity.findViewById<View>(R.id.chipAll).performClick()
        refresh()
        assertEquals(3, activity.findViewById<RecyclerView>(R.id.v2SignalList).adapter?.itemCount)
        controller.pause().stop().destroy()
    }

    @Test
    fun wifi_and_base_stations_are_neither_read_nor_shown() {
        grantScanPermissions()
        val controller = Robolectric.buildActivity(V2RadarActivity::class.java).setup()
        val activity = controller.get()
        val repo = repoOf(activity)
        val cell = bleSignal(address = "10:20:30:40:50:60", title = "LTE 기지국").copy(
            id = "cell:lte:1234:56",
            source = SignalSource.CELL,
            address = "1234",
            vendor = "SKT",
            kind = "LTE 기지국"
        )
        repo.upsert(listOf(bleSignal(), wifiSignal(), cell))
        refresh()

        // The list is Bluetooth only.
        val list = activity.findViewById<RecyclerView>(R.id.v2SignalList)
        assertEquals(1, list.adapter?.itemCount)

        // The radar sees the same list, and the counter says so.
        activity.findViewById<View>(R.id.tabNearby).performClick()
        refresh()
        assertEquals(1, activity.findViewById<RecyclerView>(R.id.nearbyList).adapter?.itemCount)
        assertEquals(
            "10m 안에 잡힌 기기 1개",
            activity.findViewById<TextView>(R.id.nearbyCount).text.toString()
        )

        // A 기준 saved now holds the Bluetooth device and nothing else.
        activity.findViewById<View>(R.id.tabRecord).performClick()
        saveBaseline(activity, "우리 집")
        assertEquals(1, repo.baselineSize())
        assertEquals("기기 1개", activity.findViewById<TextView>(R.id.v2RecordCount).text.toString())

        // The Wi-Fi device is not in the 기준, so the repository itself flags it — but the
        // screen must never show it: no banner, and the 🚨 chip stays empty.
        assertTrue(
            "the repository is allowed to know about it",
            repo.snapshot().any { it.source == SignalSource.WIFI && it.isNew }
        )
        assertEquals(View.GONE, activity.findViewById<View>(R.id.alertBanner).visibility)
        assertEquals("🚨 새 신호", activity.findViewById<TextView>(R.id.chipNew).text.toString())
        assertEquals("전체 1", activity.findViewById<TextView>(R.id.chipAll).text.toString())
        controller.pause().stop().destroy()
    }

    @Test
    fun a_wifi_or_cell_line_saved_by_v22_is_dropped_from_the_baseline() {
        grantScanPermissions()
        val controller = Robolectric.buildActivity(V2RadarActivity::class.java).setup()
        val activity = controller.get()
        val repo = repoOf(activity)

        // What v2.2 left behind: one 기준 with a Bluetooth device, a Wi-Fi and a base station.
        val store = V2BaselineStore(activity)
        val item = store.add(
            "지난 기준",
            mapOf(
                "ble:F8:1E:DF:12:34:56" to -50,
                "wifi:00:08:9F:00:00:01" to -62,
                "cell:lte:1234:56" to -90
            )
        )
        assertEquals("only the Bluetooth line is kept", 1, item.size)
        store.applyActiveTo(repo)
        assertEquals(1, repo.baselineSize())
        controller.pause().stop().destroy()
    }

    // -------------------------------------------------------------- list rows

    @Test
    fun a_row_is_a_one_line_title_one_summary_and_a_step_badge_without_a_mac() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val adapter = V2SignalAdapter { }
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
        ShadowLooper.idleMainLooper()

        val row = list.getChildAt(0)
        assertNotNull("the list must draw a row", row)
        val title = row.findViewById<TextView>(R.id.itemTitle)
        val summary = row.findViewById<TextView>(R.id.itemDetail)
        val distance = row.findViewById<TextView>(R.id.itemDistance)

        assertEquals("the title is one line", 1, title.maxLines)
        assertEquals(TextUtils.TruncateAt.END, title.ellipsize)
        assertEquals("블루투스 · 애플", summary.text.toString())
        assertEquals("매우 가까움", distance.text.toString())
        assertFalse(distance.text.contains("약"))
        assertFalse("the step badge must not print metres", distance.text.contains("m"))

        // The circular badge on the left is coloured by radio, not the dark v1 square.
        val iconBox = row.findViewById<View>(R.id.itemIconBox)
        assertNotNull("the row has an icon badge", iconBox)
        assertNotNull("the icon badge has its own background", iconBox.background)

        for (index in 0 until list.childCount) {
            collectText(list.getChildAt(index)).forEach { text ->
                assertFalse("row text must not print a MAC: $text", text.contains("F8:1E"))
                assertFalse("row text must not print a MAC: $text", text.contains("02:00:00"))
            }
        }

        // -95 dBm with a -52 dBm advertisement is 3.7 m on the measured scale: 보통.
        val second = list.getChildAt(1).findViewById<TextView>(R.id.itemDistance)
        assertEquals("보통", second.text.toString())
    }

    @Test
    fun the_wifi_row_says_its_own_radio_and_gets_the_green_badge() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val adapter = V2SignalAdapter { }
        adapter.submit(listOf(wifiSignal()))
        val list = RecyclerView(context)
        list.layoutManager = LinearLayoutManager(context)
        list.adapter = adapter
        list.measure(
            View.MeasureSpec.makeMeasureSpec(dp(context, 360), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dp(context, 400), View.MeasureSpec.EXACTLY)
        )
        list.layout(0, 0, dp(context, 360), dp(context, 400))
        ShadowLooper.idleMainLooper()

        val row = list.getChildAt(0)
        assertEquals(
            "와이파이 · ipTIME(EFM)",
            row.findViewById<TextView>(R.id.itemDetail).text.toString()
        )
        // The step is whatever the shared classifier says for this level and band, and the
        // row never falls back to a metre value.
        assertEquals(
            Classifier.distanceStepLabel(-62, SignalSource.WIFI, null, 5745),
            row.findViewById<TextView>(R.id.itemDistance).text.toString()
        )
    }

    @Test
    fun the_title_never_grows_past_one_line_even_with_a_long_name() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val adapter = V2SignalAdapter { }
        adapter.submit(listOf(bleSignal(title = "아주아주아주아주아주아주아주아주 긴 이름의 블루투스 이어폰")))
        val list = RecyclerView(context)
        list.layoutManager = LinearLayoutManager(context)
        list.adapter = adapter
        list.measure(
            View.MeasureSpec.makeMeasureSpec(dp(context, 360), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dp(context, 400), View.MeasureSpec.EXACTLY)
        )
        list.layout(0, 0, dp(context, 360), dp(context, 400))
        ShadowLooper.idleMainLooper()

        val title = list.getChildAt(0).findViewById<TextView>(R.id.itemTitle)
        assertNotNull(title.layout)
        assertEquals("the title stays on one line", 1, title.layout.lineCount)
        assertTrue("the long title keeps the end ellipsis", title.layout.getEllipsisCount(0) > 0)
    }

    @Test
    fun every_step_has_its_own_colour_and_a_base_station_gets_none() {
        val styles = listOf(
            V2Look.VERY_NEAR, V2Look.NEAR, V2Look.MID, V2Look.FAR, V2Look.VERY_FAR
        ).map { V2Look.stepStyle(it).background }
        assertEquals("five steps, five different pills", 5, styles.distinct().size)
        assertEquals(
            R.color.v2_emerald_deep,
            V2Look.stepStyle(V2Look.VERY_NEAR).textColor
        )
        assertNotNull("a null step still gets a quiet pill", V2Look.stepStyle(null).background)

        assertEquals("매우 가까움", Classifier.distanceStep(0.4))
        assertEquals("가까움", Classifier.distanceStep(2.9))
        assertEquals("보통", Classifier.distanceStep(7.9))
        assertEquals("멀어요", Classifier.distanceStep(19.9))
        assertEquals("아주 멀어요", Classifier.distanceStep(40.0))
        assertNull(Classifier.distanceStepLabel(-70, SignalSource.CELL))

        // v1 wording is untouched: the other two apps keep their metre estimates.
        assertEquals("약 2.5m", Classifier.distanceLabel(-70, SignalSource.BLE))
    }

    // --------------------------------------------------------------- the list

    @Test
    fun the_v2_list_shows_the_scanned_bluetooth_signals_with_their_steps() {
        grantScanPermissions()
        val controller = Robolectric.buildActivity(V2RadarActivity::class.java).setup()
        val activity = controller.get()
        val repo = repoOf(activity)
        repo.upsert(listOf(bleSignal(), wifiSignal()))
        refresh()

        val list = activity.findViewById<RecyclerView>(R.id.v2SignalList)
        assertEquals("the Wi-Fi access point is not on a Bluetooth-only screen", 1, list.adapter?.itemCount)

        // The nearest signal is the one the top callout offers to find.
        val title = activity.findViewById<TextView>(R.id.v2CalloutTitle)
        assertEquals("지훈의 AirPods", title.text.toString())
        assertEquals("매우 가까움", activity.findViewById<TextView>(R.id.v2CalloutStep).text.toString())
        controller.pause().stop().destroy()
    }

    @Test
    fun a_new_signal_still_raises_the_banner_after_a_baseline_is_saved() {
        grantScanPermissions()
        val controller = Robolectric.buildActivity(V2RadarActivity::class.java).setup()
        val activity = controller.get()
        val repo = repoOf(activity)
        repo.upsert(listOf(bleSignal()))
        repo.saveBaseline()
        repo.upsert(listOf(bleSignal(address = "AA:BB:CC:DD:EE:FF", title = "민수의 버즈", rssi = -70)))
        refresh()

        val banner = activity.findViewById<View>(R.id.alertBanner)
        assertEquals(View.VISIBLE, banner.visibility)
        assertTrue(
            activity.findViewById<TextView>(R.id.alertTitle).text.contains("새 신호")
        )
        controller.pause().stop().destroy()
    }

    // ---------------------------------------------------------- detail card

    @Test
    fun the_detail_card_folds_the_mac_and_writes_the_distance_as_a_step() {
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

        assertEquals("세부 주소 보기 ▾", toggle.text.toString())
        assertEquals(View.GONE, address.visibility)
        assertFalse("collapsed body must not print the MAC", body.text.contains("F8:1E"))
        assertFalse("the subtitle must not carry the MAC", subtitle.text.contains("F8:1E"))
        assertTrue(body.text.toString(), body.text.contains("대략 거리: 매우 가까움"))

        toggle.performClick()
        assertEquals(View.VISIBLE, address.visibility)
        assertEquals("F8:1E:DF:12:34:56", address.text.toString())
        assertEquals("세부 주소 접기 ▴", toggle.text.toString())
        dialog.dismiss()
        controller.pause().stop().destroy()
    }

    @Test
    fun the_detail_cards_find_button_opens_the_orb_finder() {
        val controller = Robolectric.buildActivity(V2RadarActivity::class.java).setup()
        val activity = controller.get()
        val method = SignalRadarActivity::class.java
            .getDeclaredMethod("showDetails", Signal::class.java)
        method.isAccessible = true
        method.invoke(activity, bleSignal())
        val dialog = ShadowDialog.getLatestDialog()

        val find = dialog.findViewById<TextView>(R.id.detailDirection)
        assertEquals("🎯  가까이 찾기", find.text.toString())
        find.performClick()

        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.orbPage).visibility)
        assertEquals(
            "지훈의 AirPods",
            activity.findViewById<TextView>(R.id.orbTitle).text.toString()
        )
        controller.pause().stop().destroy()
    }

    // ------------------------------------------------------------- orb finder

    @Test
    fun the_orb_grows_with_the_level_and_is_emerald_only_while_very_near_is_held() {
        val state = OrbFinderState()
        val stepOf: (Int) -> String? = { rssi ->
            Classifier.distanceStepLabel(rssi, SignalSource.BLE, -52, null)
        }

        // -85 dBm is far away: a small orb and no emerald at all.
        state.onSample(-85, 10f, 1_000L, stepOf)
        val small = state.orbScale()
        assertEquals(0f, state.emeraldProgress(9_000L), 0.001f)

        // -41 dBm is in your hand: the orb nearly fills the screen…
        state.onSample(-41, 10f, 2_000L, stepOf)
        assertEquals(V2Look.VERY_NEAR, state.step)
        assertTrue("the orb must grow: small=$small big=${state.orbScale()}", state.orbScale() > small)

        // …and the screen only turns emerald once 매우 가까움 has been held for a moment.
        assertEquals(0f, state.emeraldProgress(2_000L), 0.001f)
        val half = state.emeraldProgress(2_800L)
        assertTrue("halfway: $half", half in 0.4f..0.6f)
        assertEquals(1f, state.emeraldProgress(5_000L), 0.001f)

        // It is held: a level that leaves the step wipes the emerald again.
        state.onSample(-70, 10f, 6_000L, stepOf)
        assertEquals(0f, state.emeraldProgress(9_000L), 0.001f)
        assertNotEquals(V2Look.VERY_NEAR, state.step)
    }

    @Test
    fun the_orb_screen_shows_the_step_the_direction_work_and_the_three_buttons() {
        grantScanPermissions()
        val controller = Robolectric.buildActivity(V2RadarActivity::class.java).setup()
        val activity = controller.get()
        repoOf(activity).upsert(listOf(bleSignal(rssi = -44, smoothed = -44f)))
        refresh()

        // The callout is the way into the finder from the top area.
        activity.findViewById<View>(R.id.v2Callout).performClick()
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.orbPage).visibility)

        refresh()
        val step = activity.findViewById<TextView>(R.id.orbStep)
        assertEquals("매우 가까움", step.text.toString())
        assertNotNull(activity.findViewById<TextView>(R.id.orbGuide).text)
        assertEquals("기기 상세", activity.findViewById<TextView>(R.id.orbDetail).text.toString())
        assertEquals("방향 다시", activity.findViewById<TextView>(R.id.orbReset).text.toString())

        // 닫기 puts the app back on the list without leaving it.
        activity.findViewById<View>(R.id.orbClose).performClick()
        assertEquals(View.GONE, activity.findViewById<View>(R.id.orbPage).visibility)
        controller.pause().stop().destroy()
    }

    /**
     * v2.6 (지훈: "방향다시 위치와 기기상세위치 바꿔줘"): the finder's bottom row is
     * 방향 다시 — 기기 상세 — 📏 예상 거리, both in the layout and left to right on a phone.
     */
    @Test
    fun the_finder_buttons_run_방향다시_then_기기상세_then_예상거리() {
        grantScanPermissions()
        val controller = Robolectric.buildActivity(V2RadarActivity::class.java).setup()
        val activity = controller.get()
        repoOf(activity).upsert(listOf(bleSignal(rssi = -44, smoothed = -44f)))
        refresh()
        activity.findViewById<View>(R.id.v2Callout).performClick()
        refresh()

        val reset = activity.findViewById<TextView>(R.id.orbReset)
        val detail = activity.findViewById<TextView>(R.id.orbDetail)
        val distance = activity.findViewById<TextView>(R.id.orbDistanceToggle)
        assertEquals("방향 다시", reset.text.toString())
        assertEquals("기기 상세", detail.text.toString())
        assertEquals("📏 예상 거리", distance.text.toString())

        // All three share one row, in the order 지훈 asked for.
        val row = reset.parent as ViewGroup
        assertEquals("the three buttons share one row", row, detail.parent)
        assertEquals("the three buttons share one row", row, distance.parent)
        assertEquals("방향 다시 must come first", 0, row.indexOfChild(reset))
        assertEquals("기기 상세 must come second", 1, row.indexOfChild(detail))
        assertEquals("📏 예상 거리 must come last", 2, row.indexOfChild(distance))

        // And the row is drawn that way: three equal buttons, left to right.
        measure(activity.findViewById(android.R.id.content), activity, 360, 780)
        assertTrue(
            "방향 다시 must sit left of 기기 상세: reset.right=${reset.right} detail.left=${detail.left}",
            reset.right <= detail.left
        )
        assertTrue(
            "기기 상세 must sit left of 예상 거리: detail.right=${detail.right} distance.left=${distance.left}",
            detail.right <= distance.left
        )
        assertEquals("all three keep the same width", reset.width, detail.width)
        assertEquals("all three keep the same width", reset.width, distance.width)
        assertTrue("the row must fit the screen: distance.right=${distance.right}", distance.right <= row.width)

        // The taps still do what they did: 기기 상세 opens the detail card.
        detail.performClick()
        assertNotNull("기기 상세 must still open the detail card", ShadowDialog.getLatestDialog())
        ShadowDialog.getLatestDialog()?.dismiss()
        controller.pause().stop().destroy()
    }

    // ------------------------------------------------- finder haptics (v2.3)

    @Test
    fun the_finder_shows_the_haptic_step_and_its_switch_and_buzzes_nearer() {
        grantScanPermissions()
        val controller = Robolectric.buildActivity(V2RadarActivity::class.java).setup()
        val activity = controller.get()
        repoOf(activity).upsert(listOf(bleSignal(rssi = -44, smoothed = -44f)))
        refresh()
        activity.findViewById<View>(R.id.v2Callout).performClick()
        refresh()

        // Close: the button says so, and the motor has already tapped.
        val button = activity.findViewById<TextView>(R.id.orbVibrate)
        assertEquals("📳 진동 5/5", button.text.toString())
        assertTrue("the finder must have tapped", (activity.orbHapticsForTest()?.taps ?: 0) >= 1)

        // The switch: off is silent at once and stays off in the saved setting.
        button.performClick()
        assertEquals("🔕 진동", button.text.toString())
        assertEquals(0, activity.orbHapticsForTest()?.taps)
        assertFalse(
            activity.getSharedPreferences("signal_radar", android.content.Context.MODE_PRIVATE)
                .getBoolean(V2RadarActivity.KEY_ORB_HAPTICS, true)
        )
        button.performClick()
        assertTrue(button.text.toString().startsWith("📳 진동"))

        // Leaving the finder (닫기, or the app going to the background) silences the motor.
        activity.findViewById<View>(R.id.orbClose).performClick()
        assertEquals(0, activity.orbHapticsForTest()?.band)
        assertEquals(0, activity.orbHapticsForTest()?.taps)
        controller.pause().stop().destroy()
    }

    @Test
    fun a_far_signal_keeps_the_finder_quiet_and_says_so() {
        grantScanPermissions()
        val controller = Robolectric.buildActivity(V2RadarActivity::class.java).setup()
        val activity = controller.get()
        repoOf(activity).upsert(
            listOf(
                bleSignal(
                    rssi = -112,
                    smoothed = -112f,
                    address = "02:00:00:00:00:99",
                    title = "멀리 있는 기기"
                )
            )
        )
        refresh()
        activity.findViewById<View>(R.id.v2Callout).performClick()
        refresh()

        // -112 dBm is out of reach (v2.9: silence below -110): the switch is on, nothing buzzes.
        assertEquals("📳 진동", activity.findViewById<TextView>(R.id.orbVibrate).text.toString())
        assertEquals(0, activity.orbHapticsForTest()?.band)
        assertEquals(0, activity.orbHapticsForTest()?.taps)
        controller.pause().stop().destroy()
    }

    @Test
    fun pausing_the_finder_stops_the_buzz() {
        grantScanPermissions()
        val controller = Robolectric.buildActivity(V2RadarActivity::class.java).setup()
        val activity = controller.get()
        repoOf(activity).upsert(listOf(bleSignal(rssi = -52, smoothed = -52f)))
        refresh()
        activity.findViewById<View>(R.id.v2Callout).performClick()
        refresh()
        assertTrue((activity.orbHapticsForTest()?.taps ?: 0) >= 1)

        controller.pause()
        assertEquals("nothing may buzz once the screen is paused", 0, activity.orbHapticsForTest()?.band)
        assertEquals(0, activity.orbHapticsForTest()?.taps)
        controller.stop().destroy()
    }

    @Test
    fun back_closes_the_orb_before_it_leaves_the_app() {
        grantScanPermissions()
        val controller = Robolectric.buildActivity(V2RadarActivity::class.java).setup()
        val activity = controller.get()
        repoOf(activity).upsert(listOf(bleSignal()))
        refresh()
        activity.findViewById<View>(R.id.v2Callout).performClick()
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.orbPage).visibility)

        activity.onBackPressedDispatcher.onBackPressed()
        assertEquals(View.GONE, activity.findViewById<View>(R.id.orbPage).visibility)
        assertFalse(activity.isFinishing)
        controller.pause().stop().destroy()
    }

    // -------------------------------------------------------- 기준 · vibrate

    @Test
    fun the_record_tab_shows_the_saved_baseline_and_the_vibrate_switch_agrees_with_it() {
        grantScanPermissions()
        val controller = Robolectric.buildActivity(V2RadarActivity::class.java).setup()
        val activity = controller.get()
        val repo = repoOf(activity)
        repo.upsert(
            listOf(
                bleSignal(),
                bleSignal(address = "AA:BB:CC:DD:EE:FF", title = "민수의 Galaxy Buds", rssi = -74)
            )
        )
        saveBaseline(activity, "우리 집")
        refresh()

        activity.findViewById<View>(R.id.tabRecord).performClick()
        assertEquals(2, repo.baselineSize())
        assertEquals("우리 집", activity.findViewById<TextView>(R.id.v2RecordName).text.toString())
        assertEquals("기기 2개", activity.findViewById<TextView>(R.id.v2RecordCount).text.toString())
        assertTrue(
            activity.findViewById<TextView>(R.id.recordSummary).text.contains("적용 중 ‘우리 집’")
        )

        val row = activity.findViewById<View>(R.id.v2VibrateRow)
        row.performClick()
        assertFalse(repo.vibrateOnNewSignal())
        assertEquals("🔕 꺼짐", activity.findViewById<TextView>(R.id.v2VibrateState).text.toString())
        assertEquals("🔕", activity.findViewById<TextView>(R.id.bellToggle).text.toString())

        row.performClick()
        assertTrue(repo.vibrateOnNewSignal())
        assertEquals("🔔 켜짐", activity.findViewById<TextView>(R.id.v2VibrateState).text.toString())
        controller.pause().stop().destroy()
    }

    /** `📌 지금 신호를 새 기준으로 저장` → the name card → 저장. */
    private fun saveBaseline(activity: V2RadarActivity, name: String) {
        activity.findViewById<View>(R.id.btnBaseline).performClick()
        val dialog = ShadowDialog.getLatestDialog()
        dialog.findViewById<android.widget.EditText>(R.id.nameDialogInput).setText(name)
        dialog.findViewById<View>(R.id.nameDialogOk).performClick()
        refresh()
    }

    // ------------------------------------------------------ licence card (v2.7)

    /**
     * 지훈: "오픈소스라이선스로 해줄수있어?" — the header's ⓘ opens the card, and the card
     * names the app and its version, the author, MIT (summary + full text) and the libraries
     * that are really in the APK.
     */
    @Test
    fun the_header_info_button_opens_the_licence_card() {
        val controller = Robolectric.buildActivity(V2RadarActivity::class.java).setup()
        val activity = controller.get()

        activity.findViewById<View>(R.id.v2LicenseInfo).performClick()
        val dialog = ShadowDialog.getLatestDialog()
        assertNotNull("ⓘ must open the licence card", dialog)

        assertEquals(
            "📄 오픈소스 라이선스",
            dialog.findViewById<TextView>(R.id.licenseTitle).text.toString()
        )
        // 앱 이름 · 버전 · 제작자 — the version comes from the package that is running.
        assertEquals("전파 v2.9", dialog.findViewById<TextView>(R.id.licenseApp).text.toString())
        val copyright = dialog.findViewById<TextView>(R.id.licenseCopyright).text.toString()
        assertEquals("Copyright © 2026 이지훈 (@1jhzn)", copyright)
        assertTrue(copyright.contains("이지훈") && copyright.contains("1jhzn") && copyright.contains("2026"))

        // 적용 라이선스: name, plain-Korean summary and the full MIT text.
        assertEquals("MIT License", dialog.findViewById<TextView>(R.id.licenseName).text.toString())
        val summary = dialog.findViewById<TextView>(R.id.licenseSummary).text.toString()
        assertTrue("the summary must say what MIT allows: $summary", summary.contains("자유롭게"))
        val mit = dialog.findViewById<TextView>(R.id.licenseMit).text.toString()
        assertTrue("the card must carry the MIT text itself", mit.startsWith("MIT License"))
        assertTrue(mit.contains("Permission is hereby granted"))
        assertTrue(mit.contains("Copyright (c) 2026 이지훈 (1jhzn)"))
        assertTrue(mit.contains("WITHOUT WARRANTY OF ANY KIND"))

        // 사용한 오픈소스·표준 데이터: every bundled library and table, with its licence.
        val libraries = dialog.findViewById<TextView>(R.id.licenseLibraries).text.toString()
        for (expected in listOf(
            "AndroidX",
            "Apache License 2.0",
            "Kotlin Standard Library",
            "IEEE OUI",
            "Bluetooth SIG"
        )) {
            assertTrue("the library list must name $expected: $libraries", libraries.contains(expected))
        }

        // 닫기 puts the card away without touching anything else.
        dialog.findViewById<View>(R.id.licenseClose).performClick()
        assertFalse("닫기 must close the card", dialog.isShowing)
        controller.pause().stop().destroy()
    }

    /** The same card from the 기준 tab's named button, at the bottom of the page. */
    @Test
    fun the_record_tab_offers_the_licence_by_name() {
        val controller = Robolectric.buildActivity(V2RadarActivity::class.java).setup()
        val activity = controller.get()
        activity.findViewById<View>(R.id.tabRecord).performClick()

        val button = activity.findViewById<TextView>(R.id.v2LicenseOpen)
        assertEquals("📄 오픈소스 라이선스", button.text.toString())
        assertEquals(View.VISIBLE, button.visibility)

        button.performClick()
        val dialog = ShadowDialog.getLatestDialog()
        assertNotNull("기준 탭의 라이선스 버튼도 같은 카드를 열어야 합니다", dialog)
        assertEquals("전파 v2.9", dialog.findViewById<TextView>(R.id.licenseApp).text.toString())
        dialog.dismiss()
        controller.pause().stop().destroy()
    }

    @Test
    fun the_licence_title_falls_back_to_the_plain_name_without_a_version() {
        assertEquals("전파", V2Licenses.appTitle(null))
        assertEquals("전파", V2Licenses.appTitle("   "))
        assertEquals("전파 v2.7", V2Licenses.appTitle("2.7"))
    }

    // --------------------------------------- the scan stays where 지훈 left it (v2.7)

    /**
     * 지훈: "스캔시작을 하고 원하는거에 들어가서 중지를 하고 방향을 찾고 다시 나가면 스캔이
     * 시작되어 있어서 좀 불편해".
     *
     * Stopped is stopped: opening the finder does not start the radios, leaving it does not
     * start them either, and neither does coming back to the app afterwards.
     */
    @Test
    fun the_finder_never_starts_the_scan_again_after_a_stop() {
        grantScanPermissions()
        val controller = Robolectric.buildActivity(V2RadarActivity::class.java).setup()
        val activity = controller.get()
        val repo = repoOf(activity)
        repo.upsert(listOf(bleSignal()))
        refresh()

        val button = activity.findViewById<TextView>(R.id.btnScan)
        button.performClick()
        assertEquals("■ 스캔 중지", button.text.toString())
        button.performClick()
        assertEquals("▶ 스캔 시작", button.text.toString())

        // Into the finder from the top callout: the switch does not move.
        activity.findViewById<View>(R.id.v2Callout).performClick()
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.orbPage).visibility)
        assertEquals("opening the finder must not start the scan", "▶ 스캔 시작", button.text.toString())
        assertEquals("▶ 스캔", activity.findViewById<TextView>(R.id.orbScan).text.toString())
        assertTrue(
            "the finder must say the scan is off: ${activity.findViewById<TextView>(R.id.orbGuide).text}",
            activity.findViewById<TextView>(R.id.orbGuide).text.contains("스캔이 꺼져 있어요")
        )

        // Out again with 닫기: still stopped.
        activity.findViewById<View>(R.id.orbClose).performClick()
        assertEquals(View.GONE, activity.findViewById<View>(R.id.orbPage).visibility)
        assertEquals("닫기 must not start the scan", "▶ 스캔 시작", button.text.toString())

        // Another app and back (onStart/onResume): still stopped.
        controller.pause().stop().start().resume()
        refresh()
        assertEquals("resuming the app must not start the scan", "▶ 스캔 시작", button.text.toString())
        controller.pause().stop().destroy()
    }

    /** A scan that is running keeps running through the finder, and 닫기 leaves it running. */
    @Test
    fun a_running_scan_survives_a_trip_through_the_finder() {
        grantScanPermissions()
        val controller = Robolectric.buildActivity(V2RadarActivity::class.java).setup()
        val activity = controller.get()
        repoOf(activity).upsert(listOf(bleSignal()))
        refresh()

        val button = activity.findViewById<TextView>(R.id.btnScan)
        button.performClick()
        assertEquals("■ 스캔 중지", button.text.toString())

        activity.findViewById<View>(R.id.v2Callout).performClick()
        assertEquals("■ 스캔 중지", button.text.toString())
        assertEquals("■ 중지", activity.findViewById<TextView>(R.id.orbScan).text.toString())

        activity.findViewById<View>(R.id.orbClose).performClick()
        assertEquals("■ 스캔 중지", button.text.toString())
        controller.pause().stop().destroy()
    }

    /**
     * 지훈's own path: 중지 → 방향 찾기 → 기기 상세 → 닫기 · 나가기. The detail card inside the
     * finder must not wake the radios either.
     */
    @Test
    fun the_detail_card_inside_the_finder_leaves_a_stopped_scan_stopped() {
        grantScanPermissions()
        val controller = Robolectric.buildActivity(V2RadarActivity::class.java).setup()
        val activity = controller.get()
        repoOf(activity).upsert(listOf(bleSignal()))
        refresh()

        val button = activity.findViewById<TextView>(R.id.btnScan)
        button.performClick()
        button.performClick()
        assertEquals("▶ 스캔 시작", button.text.toString())

        activity.findViewById<View>(R.id.v2Callout).performClick()
        activity.findViewById<View>(R.id.orbDetail).performClick()
        val detail = ShadowDialog.getLatestDialog()
        assertNotNull("기기 상세 must open its card", detail)
        assertEquals("▶ 스캔 시작", button.text.toString())

        detail.dismiss()
        activity.findViewById<View>(R.id.orbClose).performClick()
        assertEquals("▶ 스캔 시작", button.text.toString())
        controller.pause().stop().destroy()
    }

    /** The finder's own pill flips the one switch the main screen uses, both ways. */    @Test
    fun the_finder_scan_pill_is_the_only_way_to_start_or_stop_from_the_finder() {
        grantScanPermissions()
        val controller = Robolectric.buildActivity(V2RadarActivity::class.java).setup()
        val activity = controller.get()
        repoOf(activity).upsert(listOf(bleSignal()))
        refresh()

        val button = activity.findViewById<TextView>(R.id.btnScan)
        activity.findViewById<View>(R.id.v2Callout).performClick()
        val pill = activity.findViewById<TextView>(R.id.orbScan)
        assertEquals("▶ 스캔", pill.text.toString())

        pill.performClick()
        assertEquals("■ 스캔 중지", button.text.toString())
        assertEquals("■ 중지", pill.text.toString())
        assertFalse(
            "the finder must stop saying the scan is off",
            activity.findViewById<TextView>(R.id.orbGuide).text.contains("스캔이 꺼져 있어요")
        )

        pill.performClick()
        assertEquals("▶ 스캔 시작", button.text.toString())
        assertEquals("▶ 스캔", pill.text.toString())

        activity.findViewById<View>(R.id.orbClose).performClick()
        assertEquals("▶ 스캔 시작", button.text.toString())
        controller.pause().stop().destroy()
    }

    // ------------------------------------------------------------- helpers

    private fun measure(root: View, activity: android.app.Activity, widthDp: Int, heightDp: Int) {
        ShadowLooper.idleMainLooper()
        val w = View.MeasureSpec.makeMeasureSpec(dp(activity, widthDp), View.MeasureSpec.EXACTLY)
        val h = View.MeasureSpec.makeMeasureSpec(dp(activity, heightDp), View.MeasureSpec.EXACTLY)
        root.measure(w, h)
        root.layout(0, 0, dp(activity, widthDp), dp(activity, heightDp))
        ShadowLooper.idleMainLooper()
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
