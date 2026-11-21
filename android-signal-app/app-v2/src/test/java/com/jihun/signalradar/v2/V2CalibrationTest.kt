package com.jihun.signalradar.v2

import com.jihun.signalradar.identify.Classifier
import com.jihun.signalradar.model.Signal
import com.jihun.signalradar.model.SignalSource
import com.jihun.signalradar.nearby.NearbyRadarMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 전파 v2.9 — 지훈's own measurement: a Galaxy tablet 0.8–1 m away read `-86 dBm` and v2.8
 * called it `보통 · 약 4.1m`.
 *
 * The measured model checked on its own, without a screen:
 *
 *  - `-86 dBm` (the tablet on his desk) is now about a metre: `가까움 · 약 1.1m`
 *  - `-92 dBm` is `가까움 · 약 1.7m` and `-98 dBm` (through a wall) is `보통 · 약 2.7m`
 *  - `-55 … -80 dBm` (in his hand, right beside it) is `매우 가까움`, `50cm 이내` … `약 0.7m`
 *  - a device advertising its own 1 m power gets the same 26 dB offset, so both paths share
 *    the one -85 dBm scale (a usual -59 dBm advertisement lands exactly on the room reference)
 *  - a 0 m output value (-30 … -45, or +12) is still not read as a 1 m level
 *  - Wi-Fi and the older apps keep the numbers they had
 *  - the 10 m radar is fed the same measured metres
 */
class V2CalibrationTest {

    private fun ble(
        rssi: Int,
        txPower: Int? = null,
        address: String = "F8:1E:DF:12:34:56"
    ) = Signal(
        id = "ble:$address",
        source = SignalSource.BLE,
        title = "지훈의 AirPods",
        vendor = "애플",
        kind = "에어팟",
        detail = "에어팟",
        address = address,
        rssi = rssi,
        firstSeen = 1_000L,
        lastSeen = 2_000L,
        smoothedRssi = rssi.toFloat(),
        txPower = txPower,
        broadcastName = "지훈의 AirPods"
    )

    private fun wifi(rssi: Int = -62, frequency: Int = 5745) = Signal(
        id = "wifi:iptime",
        source = SignalSource.WIFI,
        title = "iptime_5G",
        vendor = "ipTIME(EFM)",
        kind = "ipTIME 공유기",
        detail = "5GHz",
        address = "00:08:9F:00:00:01",
        rssi = rssi,
        firstSeen = 1_000L,
        lastSeen = 2_000L,
        smoothedRssi = rssi.toFloat(),
        frequencyMhz = frequency
    )

    // ------------------------------------------------------------ the numbers

    @Test
    fun the_tablet_he_measured_at_86_dbm_is_about_a_metre_away() {
        // The regression this version exists for: 0.8–1 m on the desk, heard at -86 dBm.
        val tablet = ble(rssi = -86, address = "5C:0A:5B:00:00:01")

        assertEquals("가까움", V2Calibration.stepLabel(tablet))
        assertEquals("약 1.1m", V2Calibration.metresText(tablet))
        assertEquals("가까움 · 약 1.1m", V2Distance.badge(tablet, true))

        val metres = V2Calibration.metres(tablet) ?: 0.0
        assertTrue("$metres m must be a metre or so", metres in 0.9..1.2)
    }

    @Test
    fun the_room_levels_read_at_the_measured_scale() {
        // -85 dBm at 1 m, n = 3.0: 30 dB per tenfold distance.
        assertEquals("50cm 이내", V2Calibration.metresText(ble(rssi = -70)))
        assertEquals("약 0.7m", V2Calibration.metresText(ble(rssi = -80)))
        assertEquals("약 1m", V2Calibration.metresText(ble(rssi = -85)))
        assertEquals("약 1.1m", V2Calibration.metresText(ble(rssi = -86)))
        assertEquals("약 1.7m", V2Calibration.metresText(ble(rssi = -92)))
        assertEquals("약 2.7m", V2Calibration.metresText(ble(rssi = -98)))

        assertEquals("매우 가까움", V2Calibration.stepLabel(ble(rssi = -70)))
        assertEquals("매우 가까움", V2Calibration.stepLabel(ble(rssi = -80)))
        assertEquals("가까움", V2Calibration.stepLabel(ble(rssi = -86)))
        assertEquals("가까움", V2Calibration.stepLabel(ble(rssi = -92)))
        assertEquals("보통", V2Calibration.stepLabel(ble(rssi = -98)))
    }

    @Test
    fun the_strongest_levels_are_in_your_hand() {
        // -55 … -80 dBm: in the hand, right beside it.
        for (rssi in listOf(-55, -65, -74, -80)) {
            assertEquals("$rssi dBm is in your hand", V2Look.VERY_NEAR, V2Calibration.stepLabel(ble(rssi = rssi)))
        }
        assertEquals("50cm 이내", V2Calibration.metresText(ble(rssi = -55)))
        assertEquals("약 0.7m", V2Calibration.metresText(ble(rssi = -80)))
        assertEquals("50cm 이내", V2Calibration.metresText(ble(rssi = -40, txPower = -52)))
        assertEquals(V2Look.VERY_NEAR, V2Calibration.stepLabel(ble(rssi = -40, txPower = -52)))
        assertEquals("50cm 이내", V2Calibration.metresText(ble(rssi = -30)))
    }

    @Test
    fun an_advertised_one_metre_value_gets_the_same_offset_as_the_room_reference() {
        // A usual -59 dBm advertisement is exactly the room's 1 m level (-59 - 26 = -85).
        assertEquals("50cm 이내", V2Calibration.metresText(ble(rssi = -59, txPower = -52)))
        assertEquals("약 0.6m", V2Calibration.metresText(ble(rssi = -72, txPower = -52)))
        for (rssi in listOf(-70, -86, -95)) {
            val fromAdvertisement = V2Calibration.metres(ble(rssi = rssi, txPower = -59)) ?: 0.0
            val fromRoomReference = V2Calibration.metres(ble(rssi = rssi)) ?: 0.0
            assertEquals(
                "$rssi dBm: both paths must share one scale",
                fromRoomReference,
                fromAdvertisement,
                0.001
            )
        }
    }

    @Test
    fun a_zero_metre_output_is_not_read_as_a_weak_one_metre_value() {
        // Apple and some chips advertise their antenna output (0 m) in the same field.
        val bogus = V2Calibration.metres(ble(rssi = -86, txPower = -45)) ?: 0.0
        val plain = V2Calibration.metres(ble(rssi = -86)) ?: 0.0
        assertEquals("an out-of-range txPower is ignored", plain, bogus, 0.001)
        assertEquals("약 1.1m", V2Calibration.metresText(ble(rssi = -86, txPower = -45)))
        assertTrue("+12 dBm is still not a 1 m level", !V2Calibration.isOneMetreTxPower(12))
        assertTrue("-30 dBm is not a 1 m level", !V2Calibration.isOneMetreTxPower(-30))
        assertTrue("-52 dBm is a 1 m level", V2Calibration.isOneMetreTxPower(-52))
        assertTrue("-78 dBm is the weakest believed", V2Calibration.isOneMetreTxPower(-78))
        assertTrue("-47 dBm is already too strong", !V2Calibration.isOneMetreTxPower(-47))
    }

    @Test
    fun a_base_station_has_no_distance_and_wifi_is_untouched() {
        val cell = ble(rssi = -90).copy(source = SignalSource.CELL, address = "1234")
        assertNull(V2Calibration.stepLabel(cell))
        assertNull(V2Calibration.metresText(cell))
        assertNull(V2Calibration.metres(cell))

        // v2 only shows Bluetooth, but the Wi-Fi model must not have moved.
        assertEquals(
            Classifier.distanceStepLabel(-62, SignalSource.WIFI, null, 5745),
            V2Calibration.stepLabel(wifi())
        )
    }

    @Test
    fun the_screens_and_the_badge_read_the_same_measured_number() {
        for (rssi in listOf(-45, -59, -70, -85, -86, -95)) {
            val signal = ble(rssi = rssi, txPower = -52)
            assertEquals(V2Calibration.stepLabel(signal), V2Look.stepOf(signal))
            assertEquals(
                V2Calibration.metresText(signal),
                V2Distance.metresOf(signal)
            )
            assertEquals(
                V2Calibration.stepLabel(signal) + " · " + V2Calibration.metresText(signal),
                V2Distance.badge(signal, true)
            )
        }
        // The finder's own line, from a fresher level than the row has.
        assertEquals(
            "📏 예상 " + V2Calibration.metresText(ble(rssi = -68)),
            V2Distance.finderLine(V2Calibration.metresText(ble(rssi = -68)))
        )
        assertNotEquals(
            Classifier.distanceLabel(-86, SignalSource.BLE),
            V2Calibration.metresText(ble(rssi = -86))
        )
    }

    // --------------------------------------------------------------- the radar

    @Test
    fun the_ten_metre_radar_uses_the_measured_distance() {
        val nearby = ble(rssi = -95, address = "AA:BB:CC:DD:EE:01")
        val mid = ble(rssi = -85, address = "AA:BB:CC:DD:EE:03")
        val far = ble(rssi = -100, address = "AA:BB:CC:DD:EE:02")

        // On the shared free-space default a -95 dBm device was pushed past the 10 m ring.
        val before = Classifier.distanceMeters(-95.0, SignalSource.BLE) ?: 0.0
        assertTrue("v2.7 said ${"%.1f".format(before)}m", before > NearbyRadarMath.LIMIT_METRES)

        val blips = NearbyRadarMath.nearbyBlips(
            listOf(nearby, mid, far),
            { signal -> V2Calibration.metres(signal) },
            { null }
        )
        assertEquals("all three are inside the room scale", 3, blips.size)
        assertEquals(listOf(mid.id, nearby.id, far.id), blips.map { it.signal.id })
        assertEquals(1.0f, blips[0].metres, 0.05f)
        assertEquals(2.15f, blips[1].metres, 0.05f)
        assertEquals(3.16f, blips[2].metres, 0.05f)
        assertEquals("1m", NearbyRadarMath.metreText(blips[0].metres))

        // Without the hook the shared default still decides, exactly as before: only the
        // -85 dBm device was inside 10 m back then.
        val shared = NearbyRadarMath.nearbyBlips(listOf(nearby, mid, far)) { null }
        assertEquals(1, shared.size)
        assertEquals(mid.id, shared.first().signal.id)
    }
}
