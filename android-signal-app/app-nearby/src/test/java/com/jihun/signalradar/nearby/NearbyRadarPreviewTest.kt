package com.jihun.signalradar.nearby

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.view.View
import android.widget.TextView
import com.jihun.signalradar.R
import com.jihun.signalradar.SignalRadarActivity
import com.jihun.signalradar.SignalRepository
import com.jihun.signalradar.model.Signal
import com.jihun.signalradar.model.SignalSource
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File

/**
 * Draws both tabs into PNGs so the bottom buttons, the radar and its labels can be checked
 * by eye without a phone. Uses Robolectric's native graphics.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE], qualifiers = "w411dp-h891dp-xxhdpi")
class NearbyRadarPreviewTest {

    private val width = 1080
    private val height = 2100

    private fun sample(
        id: String,
        source: SignalSource,
        title: String,
        vendor: String?,
        kind: String,
        address: String,
        rssi: Int,
        txPower: Int? = null,
        frequency: Int? = null,
        broadcastName: String? = null,
        connected: Boolean = false,
        bonded: Boolean = false
    ) = Signal(
        id = id,
        source = source,
        title = title,
        vendor = vendor,
        kind = kind,
        detail = kind,
        address = address,
        rssi = rssi,
        firstSeen = System.currentTimeMillis(),
        lastSeen = System.currentTimeMillis(),
        txPower = txPower,
        frequencyMhz = frequency,
        broadcastName = broadcastName,
        isConnected = connected,
        isBonded = bonded
    )

    private fun capture(activity: android.app.Activity, name: String) {
        val root = activity.findViewById<View>(android.R.id.content)
        val specW = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
        val specH = View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        root.measure(specW, specH)
        root.layout(0, 0, width, height)
        ShadowLooper.idleMainLooper()
        root.measure(specW, specH)
        root.layout(0, 0, width, height)
        ShadowLooper.idleMainLooper()

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))

        val dir = File(System.getProperty("signal.preview.dir") ?: "build/previews")
        dir.mkdirs()
        val out = File(dir, name)
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("wrote ${out.absolutePath} ${width}x$height")
    }

    @Test
    fun render_both_tabs() {
        val controller = Robolectric.buildActivity(NearbyRadarActivity::class.java).setup()
        val activity = controller.get()

        // Put the same kind of readings a phone would see into the repository.
        val repoField = SignalRadarActivity::class.java.getDeclaredField("repo")
        repoField.isAccessible = true
        val repo = repoField.get(activity) as SignalRepository
        repo.upsert(
            listOf(
                sample(
                    "ble:airpods", SignalSource.BLE, "지훈의 AirPods", "애플", "에어팟·비츠",
                    "F8:1E:DF:12:34:56", -48, txPower = -52, broadcastName = "지훈의 AirPods",
                    connected = true, bonded = true
                ),
                sample(
                    "ble:buds", SignalSource.BLE, "민수의 Galaxy Buds", "삼성전자",
                    "삼성 기기(갤럭시·버즈·워치)", "AA:BB:CC:DD:EE:02", -74,
                    broadcastName = "민수의 Galaxy Buds", bonded = true
                ),
                sample(
                    "wifi:5g", SignalSource.WIFI, "iptime_5G", "ipTIME(EFM)",
                    "ipTIME 공유기 · 5GHz 149ch · WPA3", "00:08:9F:00:00:01", -62, frequency = 5745
                ),
                sample(
                    "ble:unknown", SignalSource.BLE, "이름 없는 블루투스 기기", "제조사 미확인",
                    "타일(Tile) · 서비스 없음", "02:00:00:00:00:00", -78
                ),
                sample(
                    "wifi:2g", SignalSource.WIFI, "KT_GIGA_2G", "KT",
                    "KT 공유기 · 2.4GHz 6ch · WPA2", "11:22:33:44:55:66", -70, frequency = 2437
                ),
                sample(
                    "cell:1", SignalSource.CELL, "LTE 450-3", "SK텔레콤",
                    "LTE · PCI 3 · B3", "cell-12345", -85
                )
            )
        )
        // Two directions were swept with 방향 찾기; the other two cannot have one yet.
        repo.setBearing("ble:airpods", 38f)
        repo.setBearing("wifi:5g", 232f)

        val refresh = SignalRadarActivity::class.java.getDeclaredMethod("refresh")
        refresh.isAccessible = true
        refresh.invoke(activity)

        // The preview is about the layout, not the permission prompt of the test runtime.
        activity.findViewById<View>(R.id.noticeCard).visibility = View.GONE
        val statusDetail = activity.findViewById<TextView>(R.id.statusDetail)
        statusDetail.text = "지금 6개 신호 · ‘기준 저장’을 누르면 새 신호를 알려드려요"
        activity.findViewById<TextView>(R.id.statusChip).text = "● 스캔 중"
        capture(activity, "tab-distance.png")

        activity.findViewById<View>(R.id.tabNearby).performClick()
        ShadowLooper.idleMainLooper()

        val radar = activity.findViewById<NearbyRadarView>(R.id.nearbyRadar)
        radar.scanning = true
        radar.heading = 12f
        radar.invalidate()
        activity.findViewById<TextView>(R.id.nearbyHeading).text = "폰이 보는 방향: 북동쪽 12°"
        activity.findViewById<TextView>(R.id.statusChip).text = "● 스캔 중"

        val specW = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
        val specH = View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        val root = activity.findViewById<View>(android.R.id.content)
        root.measure(specW, specH)
        root.layout(0, 0, width, height)
        ShadowLooper.idleMainLooper()
        capture(activity, "tab-nearby.png")

        controller.pause().stop().destroy()
    }
}
