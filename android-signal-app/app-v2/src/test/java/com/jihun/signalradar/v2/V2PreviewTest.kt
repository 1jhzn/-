package com.jihun.signalradar.v2

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Looper
import android.view.View
import android.widget.TextView
import com.jihun.signalradar.R
import com.jihun.signalradar.SignalRadarActivity
import com.jihun.signalradar.SignalRepository
import com.jihun.signalradar.identify.Classifier
import com.jihun.signalradar.model.Signal
import com.jihun.signalradar.model.SignalSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Draws 전파 감지기 v2.3 into PNGs so the screens can be checked by eye without a phone:
 *
 *  - `v23-home-ble-only.png` — the main screen of v2.3: Bluetooth rows only, and the chips
 *                              전체 · 🚨 새 신호 · 📌 내 폰 연결 (no 와이파이 / 기지국 anywhere)
 *  - `v23-empty-scanning.png` — empty state at the top of the list, `■ 스캔 중지`
 *  - `v23-nearby-radar.png`  — the bright 10 m radar with one Bluetooth legend
 *  - `v23-orb-haptic.png`    — the finder at 매우 가까움 with the 📳 진동버튼 at 5/5단계
 *  - `v23-orb-dark.png`      — the finder while the signal is still "보통"
 *  - `v23-orb-emerald.png`   — the arrival screen once 매우 가까움 is held
 *  - `v23-record.png`        — the 기준 tab
 *  - `v23-detail-folded.png` — the white detail card with the MAC folded away
 *
 * v2.4 (지훈: "거리도 버튼을 누르면 예상을 볼수있게"):
 *
 *  - `v24-distance-on.png`   — the main screen with `📏 예상 거리` pressed: the top card and
 *                              every row write the estimate beside the step (`매우 가까움 · 50cm 이내`)
 *  - `v24-orb-distance.png`  — the finder showing `📏 예상 50cm 이내` under the step word
 *
 * v2.5 — `v25-home-title.png` (the header reads 전파).
 *
 * v2.6 (지훈: "방향다시 위치와 기기상세위치 바꿔줘"):
 *
 *  - `v26-orb-buttons.png`    — the finder's bottom row: 방향 다시 · 기기 상세 · 📏 예상 거리
 *
 * v2.7 (지훈: "오픈소스라이선스로 해줄수있어?"):
 *
 *  - `v27-license-dialog.png` — the 📄 오픈소스 라이선스 card
 *
 * v2.8 (지훈: "전파 2.7 거리가 너무 안 맞아" → "멀어"):
 *
 *  - `v28-distance-list.png`   — the list with 📏 on, every row writing the tuned indoor
 *                                estimate (`매우 가까움 · 50cm 이내` instead of `약 1.8m`)
 *  - `v28-distance-finder.png` — the finder's 📏 예상 line with the same tuned number
 *
 * v2.9 (지훈's own measurement: a tablet 0.8–1 m away read -86 dBm · 약 4.1m):
 *
 *  - `v29-distance-list.png`   — the list with 📏 on, the Galaxy tablet he measured at -86 dBm
 *                                now reading `가까움 · 약 1.1m`
 *  - `v29-distance-finder.png` — the finder on that same tablet: `가까움 / 📏 예상 약 1.1m`
 *
 * Each also asserts it produced a real PNG, so a normal test run keeps them up to date.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE], qualifiers = "w411dp-h891dp-xxhdpi")
class V2PreviewTest {

    private fun dir(): File {
        val dir = File(System.getProperty("signal.preview.dir") ?: "build/previews")
        dir.mkdirs()
        return dir
    }

    private fun grantScanPermissions() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        Shadows.shadowOf(app).grantPermissions(
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            "android.permission.NEARBY_WIFI_DEVICES"
        )
    }

    private fun repoOf(activity: V2RadarActivity): SignalRepository {
        val field = SignalRadarActivity::class.java.getDeclaredField("repo")
        field.isAccessible = true
        return field.get(activity) as SignalRepository
    }

    private fun signal(
        id: String,
        source: SignalSource,
        title: String,
        vendor: String?,
        kind: String,
        address: String,
        rssi: Int,
        txPower: Int? = null,
        frequency: Int? = null,
        connected: Boolean = false,
        bonded: Boolean = false,
        isNew: Boolean = false
    ) = Signal(
        id = id,
        source = source,
        title = title,
        vendor = vendor,
        kind = kind,
        detail = kind,
        address = address,
        rssi = rssi,
        firstSeen = System.currentTimeMillis() - 30_000,
        lastSeen = System.currentTimeMillis(),
        isNew = isNew,
        smoothedRssi = rssi.toFloat(),
        txPower = txPower,
        frequencyMhz = frequency,
        broadcastName = if (source == SignalSource.BLE) title else null,
        isConnected = connected,
        isBonded = bonded
    )

    private fun samples() = listOf(
        signal(
            "ble:1", SignalSource.BLE, "지훈의 AirPods", "애플", "에어팟",
            "F8:1E:DF:12:34:56", -48, txPower = -52, connected = true, bonded = true
        ),
        signal(
            "wifi:1", SignalSource.WIFI, "iptime_5G", "ipTIME(EFM)", "ipTIME 공유기",
            "00:08:9F:00:00:01", -62, frequency = 5745
        ),
        signal(
            "ble:2", SignalSource.BLE, "민수의 Galaxy Buds", "삼성전자", "삼성 기기(갤럭시·버즈·워치)",
            "AA:BB:CC:DD:EE:FF", -74, bonded = true, isNew = true
        ),
        signal(
            "ble:3", SignalSource.BLE, "이름 없는 블루투스 기기", "제조사 미확인", "타일(Tile)",
            "02:00:00:00:00:00", -81
        ),
        signal(
            "wifi:2", SignalSource.WIFI, "KT_GIGA_2G", "KT", "KT 공유기",
            "11:22:33:44:55:66", -88, frequency = 2437
        )
    )

    private fun capture(activity: android.app.Activity, name: String, width: Int, height: Int) {
        val root = activity.findViewById<View>(android.R.id.content)
        ShadowLooper.idleMainLooper()
        val specW = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
        val specH = View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        root.measure(specW, specH)
        root.layout(0, 0, width, height)
        ShadowLooper.idleMainLooper()
        root.measure(specW, specH)
        root.layout(0, 0, width, height)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val out = File(dir(), name)
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("wrote ${out.absolutePath} ${width}x$height")
        assertTrue("preview $name must not be empty", out.length() > 10_000)
    }

    private fun refresh(seconds: Long = 1L) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(seconds, TimeUnit.SECONDS)
    }

    @Test
    fun render_home_with_the_sheet_and_the_rows() {
        grantScanPermissions()
        val controller = Robolectric.buildActivity(V2RadarActivity::class.java).setup()
        val activity = controller.get()
        repoOf(activity).upsert(samples())
        refresh()
        val first = signal(
            "ble:focus", SignalSource.BLE, "지훈의 AirPods", "애플", "에어팟",
            "F8:1E:DF:12:34:56", -41, txPower = -52, connected = true, bonded = true
        )
        activity.findViewById<TextView>(R.id.v2CalloutTitle).text = first.title
        capture(activity, "v23-home-sheet.png", 1233, 2673)
        controller.pause().stop().destroy()
    }

    /**
     * The brief's `v23-home-ble-only.png`: the main screen of v2.3. The scanner is fed the same
     * mixed list a real room gives (Bluetooth · Wi-Fi · Bluetooth · Bluetooth · Wi-Fi), and the
     * screen has to show the Bluetooth rows only, with no 와이파이 or 기지국 chip to press.
     */
    @Test
    fun render_the_ble_only_home_without_wifi_or_cell() {
        grantScanPermissions()
        val controller = Robolectric.buildActivity(V2RadarActivity::class.java).setup()
        val activity = controller.get()
        repoOf(activity).upsert(samples())
        refresh()
        capture(activity, "v23-home-ble-only.png", 1233, 2673)

        val list = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.v2SignalList)
        assertEquals("every row is a Bluetooth device", 3, list.adapter?.itemCount)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.chipWifi).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.chipCell).visibility)
        assertEquals("전체 3", activity.findViewById<TextView>(R.id.chipAll).text.toString())
        controller.pause().stop().destroy()
    }

    /**
     * The brief's `v23-orb-haptic.png`: the finder in reach, with the 📳 switch on and the band
     * it is tapping at, so the screenshot shows both the toggle and the nearness state.
     */
    @Test
    fun render_the_orb_with_the_haptic_switch() {
        val controller = Robolectric.buildActivity(V2RadarActivity::class.java).setup()
        val activity = controller.get()
        val target = samples().first()
        activity.previewOrb(
            signal = target,
            step = "매우 가까움",
            emerald = 1f,
            scale = 0.96f,
            heading = 150f,
            peak = 150f
        )
        capture(activity, "v23-orb-haptic.png", 1233, 2673)
        assertEquals(
            "📳 진동 5/5",
            activity.findViewById<TextView>(R.id.orbVibrate).text.toString()
        )
        controller.pause().stop().destroy()
    }

    @Test
    fun render_the_home_with_the_estimate_switched_on() {
        grantScanPermissions()
        val controller = Robolectric.buildActivity(V2RadarActivity::class.java).setup()
        val activity = controller.get()
        repoOf(activity).upsert(samples())
        refresh()
        activity.findViewById<View>(R.id.chipDistance).performClick()
        refresh()
        capture(activity, "v24-distance-on.png", 1233, 2673)

        // The switch is drawn as pressed, and the top card writes a real estimate.
        assertEquals(
            activity.getColor(R.color.v2_sheet),
            activity.findViewById<TextView>(R.id.chipDistance).currentTextColor
        )
        val step = activity.findViewById<TextView>(R.id.v2CalloutStep).text.toString()
        assertTrue("the top card must carry the estimate: $step", step.contains(" · "))
        assertTrue(step, step.contains("매우 가까움"))
        controller.pause().stop().destroy()
    }

    @Test
    fun render_the_finder_with_the_estimate() {
        val controller = Robolectric.buildActivity(V2RadarActivity::class.java).setup()
        val activity = controller.get()
        val target = samples().first()
        activity.findViewById<View>(R.id.chipDistance).performClick()
        activity.previewOrb(
            signal = target,
            step = "매우 가까움",
            emerald = 1f,
            scale = 0.96f,
            heading = 150f,
            peak = 150f,
            metres = "50cm 이내"
        )
        capture(activity, "v24-orb-distance.png", 1233, 2673)
        assertEquals(
            "📏 예상 50cm 이내",
            activity.findViewById<TextView>(R.id.orbDistance).text.toString()
        )
        assertEquals(
            View.VISIBLE,
            activity.findViewById<View>(R.id.orbDistance).visibility
        )
        controller.pause().stop().destroy()
    }

    /**
     * v2.5 (지훈: "앱이름을 전파로 바꿔줘"): the header reads `전파` where it used to say
     * `전파 감지기 v2`, and the bell and status chip still sit on the same line.
     */
    @Test
    fun render_the_home_under_the_name_전파() {
        grantScanPermissions()
        val controller = Robolectric.buildActivity(V2RadarActivity::class.java).setup()
        val activity = controller.get()
        repoOf(activity).upsert(samples())
        refresh()
        val title = activity.findViewById<TextView>(R.id.v2AppTitle)
        assertEquals("전파", title.text.toString())
        capture(activity, "v25-home-title.png", 1233, 2673)

        // The shorter name must not push the bell or the 상태 chip off the header bar.
        val bar = activity.findViewById<View>(R.id.v2HeaderBar)
        val bell = activity.findViewById<View>(R.id.bellToggle)
        val chip = activity.findViewById<View>(R.id.statusChip)
        assertEquals(View.VISIBLE, bell.visibility)
        assertEquals(View.VISIBLE, chip.visibility)
        assertTrue("the chip must stay inside the header: chip.right=${chip.right} bar.width=${bar.width}", chip.right <= bar.width)
        assertTrue("the title must keep its own space: title.width=${title.width}", title.width > 0)
        controller.pause().stop().destroy()
    }

    /**
     * v2.8 (지훈: "전파 2.7 거리가 너무 안 맞아" → "멀어"): the brief's screenshot — the real
     * list with `📏 예상 거리` on, so every row writes the tuned indoor estimate beside its step
     * (the earbuds on the desk read `매우 가까움 · 50cm 이내`, not `약 1.8m`).
     */
    @Test
    fun render_the_v28_indoor_distance_on_the_list() {
        grantScanPermissions()
        val controller = Robolectric.buildActivity(V2RadarActivity::class.java).setup()
        val activity = controller.get()
        repoOf(activity).upsert(samples())
        refresh()
        activity.findViewById<View>(R.id.chipDistance).performClick()
        refresh()
        capture(activity, "v28-distance-list.png", 1233, 2673)

        // The top card and the first row carry the tuned estimate, and it is shorter than the
        // free-space number v2.7 wrote for the same level.
        val callout = activity.findViewById<TextView>(R.id.v2CalloutStep).text.toString()
        assertTrue("the top card must carry the estimate: $callout", callout.contains(" · "))
        val row = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(
            R.id.v2SignalList
        ).getChildAt(0)
        val badge = row.findViewById<TextView>(R.id.itemDistance).text.toString()
        assertTrue("the row must carry the estimate: $badge", badge.contains(" · "))
        val before = Classifier.distanceLabel(-48, SignalSource.BLE, -52, null) ?: ""
        assertTrue(
            "v2.8 ($badge) must be shorter than v2.7 ($before)",
            badge.substringAfter(" · ") != before && badge.contains("50cm")
        )
        controller.pause().stop().destroy()
    }

    /** The same tuned number inside the finder: `📏 예상 약 2.4m` under the step word. */
    @Test
    fun render_the_v28_indoor_distance_in_the_finder() {
        val controller = Robolectric.buildActivity(V2RadarActivity::class.java).setup()
        val activity = controller.get()
        val target = samples().drop(2).first()   // 민수의 Galaxy Buds, -74 dBm
        val metres = V2Calibration.metresText(target)
        activity.findViewById<View>(R.id.chipDistance).performClick()
        activity.previewOrb(
            signal = target,
            step = V2Calibration.stepLabel(target) ?: "가까움",
            emerald = 0f,
            scale = 0.55f,
            heading = 150f,
            peak = 150f,
            metres = metres
        )
        capture(activity, "v28-distance-finder.png", 1233, 2673)
        assertEquals(
            "📏 예상 $metres",
            activity.findViewById<TextView>(R.id.orbDistance).text.toString()
        )
        controller.pause().stop().destroy()
    }

    /**
     * v2.9 — the measurement the version exists for: the Galaxy tablet on 지훈's desk, 0.8–1 m
     * away, heard at -86 dBm. v2.8 wrote `보통 · 약 4.1m`; on the measured -85 dBm scale it is
     * `가까움 · 약 1.1m`.
     */
    @Test
    fun render_the_v29_measured_distance_on_the_list() {
        grantScanPermissions()
        val controller = Robolectric.buildActivity(V2RadarActivity::class.java).setup()
        val activity = controller.get()
        val room = listOf(
            signal(
                "ble:tablet", SignalSource.BLE, "삼성 기기(갤럭시…", "삼성전자",
                "삼성 기기(갤럭시·버즈·워치)", "5C:0A:5B:00:00:01", -86, bonded = true
            ),
            signal(
                "ble:buds", SignalSource.BLE, "민수의 Galaxy Buds", "삼성전자",
                "삼성 기기(갤럭시·버즈·워치)", "AA:BB:CC:DD:EE:FF", -74, bonded = true, isNew = true
            ),
            signal(
                "ble:tile", SignalSource.BLE, "이름 없는 블루투스 기기", "제조사 미확인", "타일(Tile)",
                "02:00:00:00:00:00", -98
            )
        )
        repoOf(activity).upsert(room)
        refresh()
        activity.findViewById<View>(R.id.chipDistance).performClick()
        refresh()
        capture(activity, "v29-distance-list.png", 1233, 2673)

        // The tablet he measured sits about a metre away, not four.
        assertEquals("가까움 · 약 1.1m", V2Distance.badge(room.first(), true))
        assertEquals("매우 가까움 · 50cm 이내", V2Distance.badge(room[1], true))
        assertEquals("보통 · 약 2.7m", V2Distance.badge(room[2], true))
        val list = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.v2SignalList)
        assertEquals("전체 3", activity.findViewById<TextView>(R.id.chipAll).text.toString())
        val drawn = (0 until list.childCount).map { list.getChildAt(it) }
        val tabletRow = drawn.first { row ->
            row.findViewById<TextView>(R.id.itemTitle)?.text?.toString()?.startsWith("삼성 기기") == true
        }
        assertEquals(
            "가까움 · 약 1.1m",
            tabletRow.findViewById<TextView>(R.id.itemDistance).text.toString()
        )
        controller.pause().stop().destroy()
    }

    /** The same tablet inside the finder: `가까움` with `📏 예상 약 1.1m` under it. */
    @Test
    fun render_the_v29_measured_distance_in_the_finder() {
        val controller = Robolectric.buildActivity(V2RadarActivity::class.java).setup()
        val activity = controller.get()
        val tablet = signal(
            "ble:tablet", SignalSource.BLE, "삼성 기기(갤럭시…", "삼성전자",
            "삼성 기기(갤럭시·버즈·워치)", "5C:0A:5B:00:00:01", -86, bonded = true
        )
        val metres = V2Calibration.metresText(tablet)
        activity.findViewById<View>(R.id.chipDistance).performClick()
        activity.previewOrb(
            signal = tablet,
            step = V2Calibration.stepLabel(tablet) ?: "가까움",
            emerald = 0f,
            scale = 0.62f,
            heading = 150f,
            peak = 150f,
            metres = metres
        )
        capture(activity, "v29-distance-finder.png", 1233, 2673)
        assertEquals("약 1.1m", metres)
        assertEquals(
            "📏 예상 약 1.1m",
            activity.findViewById<TextView>(R.id.orbDistance).text.toString()
        )
        assertEquals("가까움", activity.findViewById<TextView>(R.id.orbStep).text.toString())
        controller.pause().stop().destroy()
    }

    /**
     * v2.6 (지훈: "방향다시 위치와 기기상세위치 바꿔줘"): the finder's bottom row from left to
     * right is 방향 다시 — 기기 상세 — 📏 예상 거리. This is the screenshot the brief asks for.
     */
    @Test
    fun render_the_orb_with_방향다시_first() {
        val controller = Robolectric.buildActivity(V2RadarActivity::class.java).setup()
        val activity = controller.get()
        val target = samples().first()
        activity.previewOrb(
            signal = target,
            step = "매우 가까움",
            emerald = 1f,
            scale = 0.96f,
            heading = 150f,
            peak = 150f,
            metres = "50cm 이내"
        )
        capture(activity, "v26-orb-buttons.png", 1233, 2673)

        val reset = activity.findViewById<TextView>(R.id.orbReset)
        val detail = activity.findViewById<TextView>(R.id.orbDetail)
        val distance = activity.findViewById<TextView>(R.id.orbDistanceToggle)
        val row = reset.parent as android.view.ViewGroup
        val drawn = (0 until row.childCount).map { row.getChildAt(it) }.filterIsInstance<TextView>()
        assertEquals(
            listOf("방향 다시", "기기 상세", "📏 예상 거리"),
            drawn.map { it.text.toString() }
        )
        assertTrue(
            "방향 다시 must sit left of 기기 상세: reset.right=${reset.right} detail.left=${detail.left}",
            reset.right <= detail.left
        )
        assertTrue(
            "기기 상세 must sit left of 예상 거리: detail.right=${detail.right} distance.left=${distance.left}",
            detail.right <= distance.left
        )
        controller.pause().stop().destroy()
    }

    /**
     * v2.7 (지훈: "오픈소스라이선스로 해줄수있어?"): the licence card — 앱 이름·버전·제작자,
     * MIT with its summary and full text, and the libraries the APK carries. This is the
     * screenshot the brief asks for.
     */
    @Test
    fun render_the_licence_card() {
        val controller = Robolectric.buildActivity(V2RadarActivity::class.java).setup()
        val activity = controller.get()
        activity.findViewById<View>(R.id.v2LicenseInfo).performClick()
        val dialog = ShadowDialog.getLatestDialog()
        assertTrue("the licence card must open", dialog != null)
        renderDialog(dialog, "v27-license-dialog.png")

        // The card must actually show the four things it exists for, not just be a panel.
        assertEquals("전파 v2.9", dialog.findViewById<TextView>(R.id.licenseApp).text.toString())
        assertEquals(
            "Copyright © 2026 이지훈 (@1jhzn)",
            dialog.findViewById<TextView>(R.id.licenseCopyright).text.toString()
        )
        assertEquals("MIT License", dialog.findViewById<TextView>(R.id.licenseName).text.toString())
        assertTrue(
            dialog.findViewById<TextView>(R.id.licenseLibraries).text.contains("Apache License 2.0")
        )
        // The whole card fits a 411dp-wide phone without its text being clipped away.
        val card = dialog.findViewById<View>(R.id.licenseClose)
        assertTrue("the close button must be on the card: bottom=${card.bottom}", card.bottom > 0)
        dialog.dismiss()
        controller.pause().stop().destroy()
    }

    @Test
    fun render_the_empty_state_while_scanning() {
        grantScanPermissions()
        val controller = Robolectric.buildActivity(V2RadarActivity::class.java).setup()
        val activity = controller.get()
        activity.findViewById<TextView>(R.id.btnScan).performClick()
        refresh()
        capture(activity, "v23-empty-scanning.png", 1233, 2673)
        controller.pause().stop().destroy()
    }

    @Test
    fun render_the_bright_radar_tab() {
        grantScanPermissions()
        val controller = Robolectric.buildActivity(V2RadarActivity::class.java).setup()
        val activity = controller.get()
        repoOf(activity).upsert(samples())
        refresh()
        activity.findViewById<View>(R.id.tabNearby).performClick()
        refresh()
        capture(activity, "v23-nearby-radar.png", 1233, 2673)
        controller.pause().stop().destroy()
    }

    @Test
    fun render_the_orb_while_it_is_still_dark() {
        val controller = Robolectric.buildActivity(V2RadarActivity::class.java).setup()
        val activity = controller.get()
        val target = samples().first()
        activity.previewOrb(
            signal = target,
            step = "보통",
            emerald = 0f,
            scale = 0.58f,
            heading = 40f,
            peak = 150f
        )
        capture(activity, "v23-orb-dark.png", 1233, 2673)
        controller.pause().stop().destroy()
    }

    @Test
    fun render_the_orb_once_the_signal_is_in_reach() {
        val controller = Robolectric.buildActivity(V2RadarActivity::class.java).setup()
        val activity = controller.get()
        val target = samples().first()
        activity.previewOrb(
            signal = target,
            step = "매우 가까움",
            emerald = 1f,
            scale = 0.96f,
            heading = 150f,
            peak = 150f
        )
        capture(activity, "v23-orb-emerald.png", 1233, 2673)
        controller.pause().stop().destroy()
    }

    @Test
    fun render_the_record_tab() {
        grantScanPermissions()
        val controller = Robolectric.buildActivity(V2RadarActivity::class.java).setup()
        val activity = controller.get()
        // Three named 기준, saved one after another as 지훈 would: the newest is in use.
        val devices = samples().filter { it.source == SignalSource.BLE }
        repoOf(activity).upsert(devices.take(1))
        saveBaseline(activity, "우리 집")
        repoOf(activity).upsert(devices.take(2))
        saveBaseline(activity, "2학년 1반")
        repoOf(activity).upsert(devices)
        saveBaseline(activity, "복도")
        refresh()
        activity.findViewById<View>(R.id.tabRecord).performClick()
        refresh()
        capture(activity, "v23-record.png", 1233, 2673)

        // The brief's screenshot has to show the reset button, so the three cards plus the
        // keep/wipe buttons must fit the sheet without scrolling.
        val page = activity.findViewById<View>(R.id.recordPage)
        val reset = activity.findViewById<View>(R.id.v2RecordReset)
        assertEquals(View.VISIBLE, reset.visibility)
        assertTrue(
            "기준 초기화 must be inside the sheet: reset.bottom=${reset.bottom} page.height=${page.height}",
            reset.bottom <= page.height
        )
        controller.pause().stop().destroy()
    }


    @Test
    fun render_the_record_tab_before_anything_is_saved() {
        grantScanPermissions()
        val controller = Robolectric.buildActivity(V2RadarActivity::class.java).setup()
        val activity = controller.get()
        repoOf(activity).upsert(samples())
        refresh()
        activity.findViewById<View>(R.id.tabRecord).performClick()
        refresh()
        capture(activity, "v23-record-empty.png", 1233, 2673)
        // Nothing saved: the reset button is away and the save button is the thing to press.
        assertEquals(View.GONE, activity.findViewById<View>(R.id.v2RecordReset).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.btnBaseline).visibility)
        controller.pause().stop().destroy()
    }

    @Test
    fun render_the_name_card_that_asks_for_a_baseline_name() {
        grantScanPermissions()
        val controller = Robolectric.buildActivity(V2RadarActivity::class.java).setup()
        val activity = controller.get()
        repoOf(activity).upsert(samples())
        refresh()
        activity.findViewById<View>(R.id.btnBaseline).performClick()
        val dialog = ShadowDialog.getLatestDialog()
        renderDialog(dialog, "v23-name-card.png")
        dialog.dismiss()
        controller.pause().stop().destroy()
    }

    /** The 기준 tab's save button asks for a name: 버튼 → 이름 → 저장. */
    private fun saveBaseline(activity: V2RadarActivity, name: String) {
        activity.findViewById<View>(R.id.btnBaseline).performClick()
        val dialog = ShadowDialog.getLatestDialog()
        dialog.findViewById<android.widget.EditText>(R.id.nameDialogInput).setText(name)
        dialog.findViewById<View>(R.id.nameDialogOk).performClick()
        refresh()
    }

    @Test
    fun render_the_white_detail_card_with_the_mac_folded() {        val controller = Robolectric.buildActivity(V2RadarActivity::class.java).setup()
        val activity = controller.get()
        val method = SignalRadarActivity::class.java
            .getDeclaredMethod("showDetails", Signal::class.java)
        method.isAccessible = true
        method.invoke(activity, samples().first())
        val dialog = ShadowDialog.getLatestDialog()
        renderDialog(dialog, "v23-detail-folded.png")
        dialog.dismiss()
        controller.pause().stop().destroy()
    }

    /** v2.4: the detail card with its own `📏 예상 거리 접기` pill and the estimate written in. */
    @Test
    fun render_the_detail_card_with_the_estimate() {
        val controller = Robolectric.buildActivity(V2RadarActivity::class.java).setup()
        val activity = controller.get()
        val signal = samples().first()
        repoOf(activity).upsert(listOf(signal))
        activity.findViewById<View>(R.id.chipDistance).performClick()
        val method = SignalRadarActivity::class.java
            .getDeclaredMethod("showDetails", Signal::class.java)
        method.isAccessible = true
        method.invoke(activity, signal)
        val dialog = ShadowDialog.getLatestDialog()
        renderDialog(dialog, "v24-detail-distance.png")
        assertTrue(
            dialog.findViewById<TextView>(R.id.detailBody).text
                .contains("대략 거리: 매우 가까움 · 50cm 이내")
        )
        dialog.dismiss()
        controller.pause().stop().destroy()
    }

    private fun renderDialog(dialog: android.app.Dialog, name: String) {
        val decor = dialog.window?.decorView
        assertTrue("the dialog must have a window", decor != null)
        val width = 1233
        val specW = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
        val specH = View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.AT_MOST)
        decor!!.measure(specW, specH)
        decor.layout(0, 0, width, decor.measuredHeight)
        ShadowLooper.idleMainLooper()
        decor.measure(specW, specH)
        decor.layout(0, 0, width, decor.measuredHeight)

        val bitmap = Bitmap.createBitmap(width, decor.measuredHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.parseColor("#E9F2E4"))
        decor.draw(canvas)
        val out = File(dir(), name)
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("wrote ${out.absolutePath} height=${decor.measuredHeight}")
        assertTrue("preview $name must not be empty", out.length() > 10_000)
    }
}
