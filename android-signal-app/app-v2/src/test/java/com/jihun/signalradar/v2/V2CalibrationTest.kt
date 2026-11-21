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
 * 전파 감지기 v2.8 — 지훈: "전파 2.7 거리가 너무 안 맞아" → "멀어".
 *
 * The tuned indoor model checked on its own, without a screen:
 *
 *  - a device heard at -70 … -95 dBm now sits at a believable room distance instead of one
 *    several metres further than it is
 *  - a device advertising its own 1 m power gets the same 8 dB indoor loss, so both paths share
 *    one scale
 *  - a 0 m output value (-30 … -45) is no longer read as "very weak at 1 m", the bug that made
 *    metres explode
 *  - Wi-Fi and the older apps keep the numbers they had
 *  - the 10 m radar is fed the same tuned metres, so a device at -95 dBm is no longer pushed
 *    off the ring it actually belongs inside
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
    fun a_room_level_now_reads_as_a_room_distance() {
        // No advertised 1 m value: the tuned room reference (-67 dBm, n = 3.1) decides.
        assertEquals("약 1.2m", V2Calibration.metresText(ble(rssi = -70)))
        assertEquals("약 2.6m", V2Calibration.metresText(ble(rssi = -80)))
        assertEquals("약 3.8m", V2Calibration.metresText(ble(rssi = -85)))
        assertEquals("약 5.5m", V2Calibration.metresText(ble(rssi = -90)))

        assertEquals("가까움", V2Calibration.stepLabel(ble(rssi = -70)))
        assertEquals("보통", V2Calibration.stepLabel(ble(rssi = -85)))
        assertEquals("보통", V2Calibration.stepLabel(ble(rssi = -90)))
    }

    @Test
    fun every_room_level_is_closer_than_the_free_space_estimate_it_replaced() {
        // What v2.7 said for the same levels, and what v2.8 says now.
        for (rssi in listOf(-60, -70, -80, -85, -90, -95)) {
            val before = Classifier.distanceMeters(rssi.toDouble(), SignalSource.BLE) ?: 0.0
            val after = V2Calibration.metres(ble(rssi = rssi)) ?: 0.0
            assertTrue(
                "$rssi dBm: v2.7 said ${"%.1f".format(before)}m, v2.8 says ${"%.1f".format(after)}m",
                after < before
            )
        }
    }

    @Test
    fun the_strongest_levels_are_in_your_hand() {
        assertEquals("50cm 이내", V2Calibration.metresText(ble(rssi = -40, txPower = -52)))
        assertEquals(V2Look.VERY_NEAR, V2Calibration.stepLabel(ble(rssi = -40, txPower = -52)))
        assertEquals("50cm 이내", V2Calibration.metresText(ble(rssi = -30)))
    }

    @Test
    fun an_advertised_one_metre_value_gets_the_same_indoor_loss_as_the_room_reference() {
        // -52 dBm at 1 m in free space is about -60 dBm at 1 m in 지훈's room.
        assertEquals("약 0.9m", V2Calibration.metresText(ble(rssi = -59, txPower = -52)))
        assertEquals("약 2.4m", V2Calibration.metresText(ble(rssi = -72, txPower = -52)))
        // Both paths agree at the middle of the range: an advertisement of -67 dBm is the
        // room's own 1 m level, so the estimate must be about a metre either way.
        val fromAdvertisement = V2Calibration.metres(ble(rssi = -70, txPower = -59)) ?: 0.0
        val fromRoomReference = V2Calibration.metres(ble(rssi = -70)) ?: 0.0
        assertTrue(
            "advertised=${"%.2f".format(fromAdvertisement)} room=${"%.2f".format(fromRoomReference)}",
            kotlin.math.abs(fromAdvertisement - fromRoomReference) < 0.1
        )
    }

    @Test
    fun a_zero_metre_output_is_not_read_as_a_weak_one_metre_value() {
        // Apple and some chips advertise their antenna output (0 m) in the same field. v2.7
        // believed -45 dBm there and pushed the device metres past where it was.
        val bogus = V2Calibration.metres(ble(rssi = -70, txPower = -45)) ?: 0.0
        val plain = V2Calibration.metres(ble(rssi = -70)) ?: 0.0
        assertEquals("an out-of-range txPower is ignored", plain, bogus, 0.001)
        assertEquals("약 1.2m", V2Calibration.metresText(ble(rssi = -70, txPower = -45)))
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
    fun the_screens_and_the_badge_read_the_same_tuned_number() {
        for (rssi in listOf(-45, -59, -70, -85, -95)) {
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
            Classifier.distanceLabel(-70, SignalSource.BLE),
            V2Calibration.metresText(ble(rssi = -70))
        )
    }

    // --------------------------------------------------------------- the radar

    @Test
    fun the_ten_metre_radar_uses_the_tuned_distance_so_room_devices_stay_on_the_ring() {
        val nearby = ble(rssi = -95, address = "AA:BB:CC:DD:EE:01")
        val mid = ble(rssi = -85, address = "AA:BB:CC:DD:EE:03")
        val far = ble(rssi = -100, address = "AA:BB:CC:DD:EE:02")

        // v2.7 pushed a -95 dBm device to 19 m, off the 10 m radar entirely.
        val before = Classifier.distanceMeters(-95.0, SignalSource.BLE) ?: 0.0
        assertTrue("v2.7 said ${"%.1f".format(before)}m", before > NearbyRadarMath.LIMIT_METRES)

        val blips = NearbyRadarMath.nearbyBlips(
            listOf(nearby, mid, far),
            { signal -> V2Calibration.metres(signal) },
            { null }
        )
        assertEquals("the two devices inside 10 m are drawn", 2, blips.size)
        assertEquals(listOf(mid.id, nearby.id), blips.map { it.signal.id })
        assertEquals(3.8f, blips.first().metres, 0.05f)
        assertEquals(8.0f, blips[1].metres, 0.05f)
        assertEquals("8m", NearbyRadarMath.metreText(blips[1].metres))

        // Without the hook the shared default still decides, exactly as before: only the
        // -85 dBm device was inside 10 m back then.
        val shared = NearbyRadarMath.nearbyBlips(listOf(nearby, mid, far)) { null }
        assertEquals(1, shared.size)
        assertEquals(mid.id, shared.first().signal.id)
    }
}
