package com.jihun.signalradar

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.view.ContextThemeWrapper
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.jihun.signalradar.model.Signal
import com.jihun.signalradar.model.SignalSource
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Renders list rows into a PNG so the distance column can be checked by eye. */
@Ignore("Visual preview only: remove this to render a PNG into build/previews")
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE], qualifiers = "w411dp-h891dp-xxhdpi")
class SignalListScreenshotTest {

    @Test
    fun render_list_rows() {
        val context = ContextThemeWrapper(
            ApplicationProvider.getApplicationContext<android.app.Application>(),
            R.style.Theme_SignalRadar
        )
        val list = RecyclerView(context)
        list.layoutManager = LinearLayoutManager(context)
        list.setBackgroundColor(android.graphics.Color.parseColor("#070B10"))
        val adapter = SignalAdapter { }
        list.adapter = adapter

        fun signal(
            id: String,
            source: SignalSource,
            title: String,
            vendor: String,
            detail: String,
            address: String,
            rssi: Int,
            smoothed: Float,
            txPower: Int? = null,
            frequency: Int? = null
        ) = Signal(
            id = id,
            source = source,
            title = title,
            vendor = vendor,
            kind = detail,
            detail = detail,
            address = address,
            rssi = rssi,
            firstSeen = 0L,
            lastSeen = 0L,
            smoothedRssi = smoothed,
            txPower = txPower,
            frequencyMhz = frequency
        )

        adapter.submit(
            listOf(
                signal(
                    "ble:1", SignalSource.BLE, "AirPods Pro", "애플",
                    "에어팟 · 서비스 배터리", "F8:1E:DF:12:34:56", -48, -50.2f, txPower = -52
                ),
                signal(
                    "wifi:1", SignalSource.WIFI, "iptime_5G", "ipTIME(EFM)",
                    "ipTIME 공유기 · 5GHz 149ch · WPA3", "00:08:9F:00:00:01", -62, -63.0f, frequency = 5745
                ),
                signal(
                    "ble:2", SignalSource.BLE, "Galaxy Buds2", "삼성전자",
                    "삼성 기기(갤럭시·버즈·워치)", "AA:BB:CC:DD:EE:FF", -74, -73.4f
                ),
                signal(
                    "wifi:2", SignalSource.WIFI, "KT_GIGA_2G", "KT",
                    "KT 공유기 · 2.4GHz 6ch · WPA2", "11:22:33:44:55:66", -84, -83.1f, frequency = 2437
                ),
                signal(
                    "cell:1", SignalSource.CELL, "LTE 450-3", "SK텔레콤",
                    "LTE · PCI 3 · B3", "cell-12345", -95, -95.0f
                )
            )
        )

        list.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1400, View.MeasureSpec.EXACTLY)
        )
        list.layout(0, 0, 1080, 1400)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()

        val bitmap = Bitmap.createBitmap(1080, 900, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.parseColor("#070B10"))
        list.draw(canvas)

        val dir = File(System.getProperty("signal.preview.dir") ?: "build/previews")
        dir.mkdirs()
        val out = File(dir, "list-rows.png")
        out.parentFile?.mkdirs()
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("wrote ${out.absolutePath} children=${list.childCount}")
    }
}
