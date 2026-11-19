package com.jihun.signalradar

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.jihun.signalradar.model.Signal
import com.jihun.signalradar.model.SignalSource
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

/** Draws the real detail card into a PNG so the new name/badge layout can be checked. */

@Ignore("Visual preview only: remove this to render a PNG into build/previews")
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE], qualifiers = "w411dp-h891dp-xxhdpi")
class DetailDialogScreenshotTest {

    private fun render(signal: Signal, name: String) {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val method = MainActivity::class.java.getDeclaredMethod("showDetails", Signal::class.java)
        method.isAccessible = true
        method.invoke(activity, signal)
        val dialog = ShadowDialog.getLatestDialog()
        val decor = dialog.window?.decorView ?: return
        decor.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(2400, android.view.View.MeasureSpec.AT_MOST)
        )
        decor.layout(0, 0, 1080, decor.measuredHeight)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        decor.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(2400, android.view.View.MeasureSpec.AT_MOST)
        )
        decor.layout(0, 0, 1080, decor.measuredHeight)

        val bitmap = Bitmap.createBitmap(1080, decor.measuredHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.parseColor("#070B10"))
        decor.draw(canvas)

        val dir = File(System.getProperty("signal.preview.dir") ?: "build/previews")
        dir.mkdirs()
        val out = File(dir, name)
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("wrote ${out.absolutePath} height=${decor.measuredHeight}")
        dialog.dismiss()
    }

    @Test
    fun render_named_connected_device() {
        render(
            Signal(
                id = "ble:F8:1E:DF:12:34:56",
                source = SignalSource.BLE,
                title = "지훈의 AirPods",
                vendor = "애플",
                kind = "에어팟",
                detail = "에어팟 · 서비스 배터리",
                address = "F8:1E:DF:12:34:56",
                rssi = -48,
                firstSeen = System.currentTimeMillis() - 60_000,
                lastSeen = System.currentTimeMillis(),
                smoothedRssi = -50.0f,
                txPower = -52,
                broadcastName = "지훈의 AirPods",
                isConnected = true,
                isBonded = true,
                gattName = "지훈의 AirPods",
                gattManufacturer = "Apple Inc."
            ),
            "detail-connected.png"
        )
    }

    @Test
    fun render_unnamed_device() {
        render(
            Signal(
                id = "ble:02:00:00:00:00:00",
                source = SignalSource.BLE,
                title = "이름 없는 블루투스 기기",
                vendor = "제조사 미확인",
                kind = "타일(Tile)",
                detail = "타일(Tile) · 제조사 정보 없음",
                address = "02:00:00:00:00:00",
                rssi = -81,
                firstSeen = System.currentTimeMillis() - 5_000,
                lastSeen = System.currentTimeMillis(),
                smoothedRssi = -80.5f
            ),
            "detail-unnamed.png"
        )
    }
}
