package com.jihun.signalradar

import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.jihun.signalradar.identify.DeviceName
import com.jihun.signalradar.model.Signal
import com.jihun.signalradar.model.SignalSource
import com.jihun.signalradar.scan.GattNameReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * The detail card: it grew a broadcast-name line, "my device" badges and the on-demand
 * GATT name read, so all three are walked through here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class DetailDialogTest {

    private fun bleSignal(
        title: String = "지훈의 AirPods",
        broadcastName: String? = "지훈의 AirPods",
        isConnected: Boolean = true,
        isBonded: Boolean = true,
        address: String = "F8:1E:DF:12:34:56"
    ) = Signal(
        id = "ble:$address",
        source = SignalSource.BLE,
        title = title,
        vendor = "애플",
        kind = "에어팟",
        detail = "에어팟 · 서비스 배터리",
        address = address,
        rssi = -48,
        firstSeen = 0L,
        lastSeen = 1_000L,
        smoothedRssi = -50.0f,
        txPower = -52,
        broadcastName = broadcastName,
        isConnected = isConnected,
        isBonded = isBonded
    )

    private fun openDetail(signal: Signal): android.app.Dialog {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        // The card is opened by the shared screen class; MainActivity only supplies a layout.
        val method = SignalRadarActivity::class.java
            .getDeclaredMethod("showDetails", Signal::class.java)
        method.isAccessible = true
        method.invoke(activity, signal)
        return ShadowDialog.getLatestDialog()
    }

    @Test
    fun ble_detail_shows_broadcast_name_badges_and_the_name_button() {
        val dialog = openDetail(bleSignal())
        assertTrue(dialog.isShowing)

        val body = dialog.findViewById<TextView>(R.id.detailBody)
        assertNotNull(body)
        assertTrue(body.text.contains("📢 방송명: 지훈의 AirPods"))
        assertTrue(body.text.contains("기기가 스스로 알려 준 이름"))
        val badges = dialog.findViewById<TextView>(R.id.detailBadges)
        assertEquals(View.VISIBLE, badges.visibility)
        assertEquals("🔗 내 폰 연결됨 · 📌 내 등록 기기", badges.text.toString())

        val nameButton = dialog.findViewById<TextView>(R.id.detailGattButton)
        assertEquals(View.VISIBLE, nameButton.visibility)
        assertTrue(nameButton.text.toString().contains("기기 이름 더 읽어오기"))

        val direction = dialog.findViewById<TextView>(R.id.detailDirection)
        assertEquals(View.VISIBLE, direction.visibility)
        dialog.dismiss()
    }

    @Test
    fun an_unnamed_device_says_so_instead_of_inventing_a_name() {
        val dialog = openDetail(
            bleSignal(title = "이름 없는 블루투스 기기", broadcastName = null, isConnected = false, isBonded = false)
        )
        val body = dialog.findViewById<TextView>(R.id.detailBody)
        assertTrue(body.text.contains("이름을 방송하지 않아요"))
        assertTrue(!body.text.contains("📢 방송명"))
        dialog.dismiss()
    }

    @Test
    fun a_cell_signal_offers_no_direction_and_no_name_read() {
        val dialog = openDetail(
            Signal(
                id = "cell:1",
                source = SignalSource.CELL,
                title = "LTE 450-3",
                vendor = "SK텔레콤",
                kind = "LTE",
                detail = "LTE · PCI 3 · B3",
                address = "cell-12345",
                rssi = -95,
                firstSeen = 0L,
                lastSeen = 1_000L
            )
        )
        assertEquals(View.GONE, dialog.findViewById<TextView>(R.id.detailDirection).visibility)
        assertEquals(View.GONE, dialog.findViewById<TextView>(R.id.detailGattButton).visibility)
        dialog.dismiss()
    }

    @Test
    fun a_name_read_is_refused_politely_without_bluetooth_permission() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        org.robolectric.Shadows.shadowOf(context)
            .denyPermissions(android.Manifest.permission.BLUETOOTH_CONNECT)
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter
        org.junit.Assume.assumeTrue("no bluetooth adapter in this runtime", adapter != null)
        val device = adapter.getRemoteDevice("AA:BB:CC:DD:EE:FF")

        val results = ArrayList<GattNameReader.Result>()
        val reader = GattNameReader(context, { }, { results.add(it) })
        reader.read(device)
        // The answer reaches the screen through the main thread, so let the looper run it.
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        reader.cancel()

        val result = results.singleOrNull()
        assertTrue("result=$result", result is GattNameReader.Result.Failed)
        assertTrue(
            "result=$result",
            (result as GattNameReader.Result.Failed).message.contains("권한")
        )
    }

    @Test
    fun a_refused_connection_ends_with_an_honest_sentence_and_no_leak() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        org.robolectric.Shadows.shadowOf(context)
            .grantPermissions(android.Manifest.permission.BLUETOOTH_CONNECT)
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter
        org.junit.Assume.assumeTrue("no bluetooth adapter in this runtime", adapter != null)
        val device = adapter.getRemoteDevice("11:22:33:44:55:66")

        val statuses = ArrayList<String>()
        val results = ArrayList<GattNameReader.Result>()
        val reader = GattNameReader(context, { statuses.add(it) }, { results.add(it) })
        reader.read(device)
        // A device that never answers must still end with an answer inside 3.5 seconds.
        org.robolectric.shadows.ShadowLooper.idleMainLooper(
            4_000,
            java.util.concurrent.TimeUnit.MILLISECONDS
        )
        reader.cancel()
        reader.cancel()

        assertTrue(statuses.isNotEmpty())
        assertEquals("one answer only", 1, results.size)
        val result = results.first()
        assertTrue("result=$result", result is GattNameReader.Result.Failed)
        assertTrue(
            "result=$result",
            (result as GattNameReader.Result.Failed).message.contains(DeviceName.BLOCKED_MESSAGE)
        )
    }

    @Test
    fun an_unnamed_device_can_still_be_given_a_gatt_name_by_hand() {
        // The dialog re-reads the signal from the repository, so the stored name survives
        // the next advertising packet that carries no name at all.
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val repo = SignalRepository(context)
        repo.upsert(listOf(bleSignal(broadcastName = null, title = DeviceName.NO_NAME_TITLE)))
        repo.updateGattNames("ble:F8:1E:DF:12:34:56", "지훈의 버즈", "Samsung Electronics")
        repo.upsert(listOf(bleSignal(broadcastName = null, title = DeviceName.NO_NAME_TITLE)))

        val stored = repo.find("ble:F8:1E:DF:12:34:56")
        assertNotNull(stored)
        assertEquals("지훈의 버즈", stored?.gattName)
        assertEquals("Samsung Electronics", stored?.gattManufacturer)
    }
}
