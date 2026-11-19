package com.jihun.signalradar.v2

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.jihun.signalradar.R
import com.jihun.signalradar.SignalAdapter
import com.jihun.signalradar.SignalRadarActivity
import com.jihun.signalradar.model.Signal
import com.jihun.signalradar.model.SignalSource
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper
import java.io.File

/**
 * Draws 전파 감지기 v2 into PNGs so the UI changes can be checked by eye without a phone:
 *
 *  - `v2-empty-scanning.png` — the empty state at the top of the list, `■ 스캔 중지` button
 *  - `v2-chips-rows.png`     — the two-row filter chips and v2 cards (one summary line, 단계)
 *  - `v2-detail-mac-folded.png` / `v2-detail-mac-open.png` — the folded / opened MAC address
 *
 * These also run as ordinary tests (each asserts it actually produced a non-empty PNG), so a
 * normal test run keeps the previews up to date in `app-v2/build/previews`.
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

    private fun signal(
        id: String,
        source: SignalSource,
        title: String,
        vendor: String?,
        kind: String,
        address: String,
        rssi: Int,
        smoothed: Float,
        txPower: Int? = null,
        frequency: Int? = null,
        broadcastName: String? = null,
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
        smoothedRssi = smoothed,
        txPower = txPower,
        frequencyMhz = frequency,
        broadcastName = broadcastName,
        isConnected = connected,
        isBonded = bonded
    )

    private fun samples() = listOf(
        signal(
            "ble:1", SignalSource.BLE, "지훈의 AirPods", "애플", "에어팟",
            "F8:1E:DF:12:34:56", -48, -50.2f, txPower = -52,
            broadcastName = "지훈의 AirPods", connected = true, bonded = true
        ),
        signal(
            "wifi:1", SignalSource.WIFI, "iptime_5G", "ipTIME(EFM)", "ipTIME 공유기",
            "00:08:9F:00:00:01", -62, -63.0f, frequency = 5745
        ),
        signal(
            "ble:2", SignalSource.BLE, "민수의 Galaxy Buds", "삼성전자", "삼성 기기(갤럭시·버즈·워치)",
            "AA:BB:CC:DD:EE:FF", -74, -73.4f, bonded = true, isNew = true
        ),
        signal(
            "ble:3", SignalSource.BLE, "이름 없는 블루투스 기기", "제조사 미확인", "타일(Tile)",
            "02:00:00:00:00:00", -81, -80.5f
        ),
        signal(
            "wifi:2", SignalSource.WIFI, "KT_GIGA_2G", "KT", "KT 공유기",
            "11:22:33:44:55:66", -88, -88.0f, frequency = 2437
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

    @Test
    fun render_empty_state_with_the_stop_button() {
        grantScanPermissions()
        val controller = Robolectric.buildActivity(V2RadarActivity::class.java).setup()
        val activity = controller.get()
        activity.findViewById<TextView>(R.id.btnScan).performClick()
        ShadowLooper.idleMainLooper()
        capture(activity, "v2-empty-scanning.png", 1080, 2100)
        controller.pause().stop().destroy()
    }

    private fun grantScanPermissions() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        org.robolectric.Shadows.shadowOf(app).grantPermissions(
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            "android.permission.NEARBY_WIFI_DEVICES"
        )
    }

    @Test
    fun render_chips_and_v2_rows() {
        grantScanPermissions()
        val controller = Robolectric.buildActivity(V2RadarActivity::class.java).setup()
        val activity = controller.get()
        // Stop the 800 ms refresh first, then hand the rows straight to the list: the v2
        // activity's own adapter is the one that draws the v2 card.
        controller.pause()
        val list = activity.findViewById<RecyclerView>(R.id.signalList)
        (list.adapter as SignalAdapter).submit(samples())
        activity.findViewById<View>(R.id.emptyState).visibility = View.GONE
        ShadowLooper.idleMainLooper()
        capture(activity, "v2-chips-rows.png", 1080, 2100)
        controller.stop().destroy()
    }

    @Test
    fun render_detail_card_with_the_mac_folded() {
        val controller = Robolectric.buildActivity(V2RadarActivity::class.java).setup()
        val activity = controller.get()
        val method = SignalRadarActivity::class.java
            .getDeclaredMethod("showDetails", Signal::class.java)
        method.isAccessible = true
        method.invoke(activity, samples().first())
        val dialog = ShadowDialog.getLatestDialog()
        renderDialog(dialog, "v2-detail-mac-folded.png")

        val toggle = dialog.findViewById<TextView>(R.id.detailAddressToggle)
        toggle.performClick()
        renderDialog(dialog, "v2-detail-mac-open.png")
        dialog.dismiss()
        controller.pause().stop().destroy()
    }

    @Test
    fun render_nearby_radar_tab() {
        grantScanPermissions()
        val controller = Robolectric.buildActivity(V2RadarActivity::class.java).setup()
        val activity = controller.get()
        activity.findViewById<View>(R.id.tabNearby).performClick()
        ShadowLooper.idleMainLooper()
        capture(activity, "v2-nearby-tab.png", 1080, 2100)
        controller.pause().stop().destroy()
    }

    private fun renderDialog(dialog: android.app.Dialog, name: String) {
        val decor = dialog.window?.decorView
        assertTrue("the dialog must have a window", decor != null)
        val width = 1080
        val specW = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
        val specH = View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.AT_MOST)
        decor!!.measure(specW, specH)
        decor.layout(0, 0, width, decor.measuredHeight)
        ShadowLooper.idleMainLooper()
        decor.measure(specW, specH)
        decor.layout(0, 0, width, decor.measuredHeight)

        val bitmap = Bitmap.createBitmap(width, decor.measuredHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.parseColor("#070B10"))
        decor.draw(canvas)
        val out = File(dir(), name)
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("wrote ${out.absolutePath} height=${decor.measuredHeight}")
        assertTrue("preview $name must not be empty", out.length() > 10_000)
    }
}
