package com.jihun.signalradar

import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.jihun.signalradar.identify.Classifier
import com.jihun.signalradar.identify.VendorDb
import com.jihun.signalradar.model.Signal
import com.jihun.signalradar.model.SignalSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class MainActivitySmokeTest {

    @Test
    fun activity_launches_without_crashing() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        assertNotNull(activity)
        assertNotNull(activity.findViewById(R.id.signalList))
        controller.pause().stop().destroy()
    }

    @Test
    fun vendor_tables_load_from_assets() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        VendorDb.ensureLoaded(context)

        assertEquals("애플", VendorDb.koreanName(VendorDb.vendorForCompany(0x004C)))
        assertEquals("삼성전자", VendorDb.koreanName(VendorDb.vendorForCompany(0x0075)))
        assertEquals("애플", VendorDb.koreanName(VendorDb.vendorForMac("F8:1E:DF:12:34:56")))
        assertEquals("ipTIME(EFM)", VendorDb.koreanName(VendorDb.vendorForMac("00:08:9F:00:00:01")))
    }

    @Test
    fun wifi_bands_and_channels_are_right() {
        assertEquals("2.4GHz", Classifier.bandOf(2437))
        assertEquals(6, Classifier.channelOf(2437))
        assertEquals("5GHz", Classifier.bandOf(5745))
        assertEquals(149, Classifier.channelOf(5745))
        assertEquals("6GHz", Classifier.bandOf(6135))
        assertEquals(31, Classifier.channelOf(6105))
    }

    @Test
    fun distance_estimate_shrinks_as_signal_gets_stronger() {
        val far = Classifier.distanceMeters(-90, SignalSource.BLE) ?: 0.0
        val near = Classifier.distanceMeters(-50, SignalSource.BLE) ?: 0.0
        assertTrue("near=$near far=$far", near < far)
        assertEquals("약 1m", Classifier.distanceLabel(-59, SignalSource.BLE))
        assertNull(Classifier.distanceLabel(-70, SignalSource.CELL))
    }

    @Test
    fun distance_labels_read_like_a_person_would_say_them() {
        // BLE: reference -59 dBm at 1 m, exponent 2.
        assertEquals("50cm 이내", Classifier.distanceLabel(-40, SignalSource.BLE))
        assertEquals("약 0.6m", Classifier.distanceLabel(-55, SignalSource.BLE))
        assertEquals("약 3.5m", Classifier.distanceLabel(-70, SignalSource.BLE))
        assertEquals("약 35m", Classifier.distanceLabel(-90, SignalSource.BLE))

        // The advertisement's own 1 m reference beats the default guess.
        assertEquals(
            "약 0.8m",
            Classifier.distanceLabel(-48, SignalSource.BLE, txPower = -50)
        )

        // Wi-Fi: each band starts from a different level at 1 m, indoor exponent 2.6.
        assertEquals("약 14m", Classifier.distanceLabel(-70, SignalSource.WIFI, frequencyMhz = 2437))
        assertEquals("약 8.4m", Classifier.distanceLabel(-70, SignalSource.WIFI, frequencyMhz = 5180))
        assertEquals("약 6.4m", Classifier.distanceLabel(-70, SignalSource.WIFI, frequencyMhz = 6135))
    }

    @Test
    fun repository_smooths_the_level_before_a_distance_is_shown() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val repo = SignalRepository(context)
        var now = 0L
        for (rssi in listOf(-60, -48, -66, -52, -64, -50, -62)) {
            now += 1_000
            repo.upsert(
                listOf(
                    Signal(
                        id = "ble:test",
                        source = SignalSource.BLE,
                        title = "테스트",
                        vendor = null,
                        kind = null,
                        detail = null,
                        address = "AA:BB",
                        rssi = rssi,
                        firstSeen = now,
                        lastSeen = now
                    )
                )
            )
        }
        val smoothed = repo.snapshot().first().smoothedRssi ?: 0f
        // Raw readings swung 18 dB; the smoothed one must stay near the middle of that band.
        assertTrue("smoothed=$smoothed", smoothed in -64f..-52f)
        assertEquals(smoothed, repo.find("ble:test")?.smoothedRssi ?: -1f, 0.01f)
    }
}
