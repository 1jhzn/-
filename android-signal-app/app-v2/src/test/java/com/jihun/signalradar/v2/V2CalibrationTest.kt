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
 * 전파 v2.10 — 지훈: "내 근처 50cm 안에 없는데 있다고 나와".
 *
 * v2.9 had moved the room reference to `-85 dBm` at 1 m (the level his phone showed for a tablet
 * on the desk). That made a metre a metre but swung the half metre too far: usual -59…-67 dBm
 * devices 1–3 m away, heard at -68…-76 dBm, came out under 0.5 m and the list said `50cm 이내`.
 *
 * v2.10 sits between v2.8 and v2.9 — `-74 dBm` at 1 m, n = 3.0, an advertised 1 m value 15 dB
 * below its own number — and this is that model checked without a screen:
 *
 *  - `-64 dBm` (truly in his hand) is `매우 가까움 · 50cm 이내`; `-65 dBm` is already `약 0.5m`
 *  - `-68 dBm` (the level v2.9 called `50cm 이내`) is `약 0.6m` — **not** half a metre
 *  - `-74` is 1 m, `-80` is 1.6 m, `-86` is 2.5 m (`보통`)
 *  - a device advertising its own -59 dBm 1 m power shares the one -74 dBm scale (−59 − 15)
 *  - a 0 m output value (-30 … -45, or +12) is still not read as a 1 m level
 *  - Wi-Fi and the older apps keep the numbers they had
 *  - the 10 m radar is fed the same metres
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
    fun only_a_device_truly_beside_the_phone_is_50cm_이내() {
        // The regression this version exists for: v2.9 called ordinary room devices "50cm 이내".
        assertEquals("50cm 이내", V2Calibration.metresText(ble(rssi = -64)))
        assertEquals("매우 가까움", V2Calibration.stepLabel(ble(rssi = -64)))
        assertEquals("매우 가까움 · 50cm 이내", V2Distance.badge(ble(rssi = -64), true))

        // Half a metre is -64.9 dBm on this scale, so -65 dBm is already written as metres.
        assertEquals("약 0.5m", V2Calibration.metresText(ble(rssi = -65)))
        assertEquals("매우 가까움 · 약 0.5m", V2Distance.badge(ble(rssi = -65), true))

        // A device heard at -68 dBm is not beside him: two thirds of a metre, not half.
        assertEquals("약 0.6m", V2Calibration.metresText(ble(rssi = -68)))
        assertEquals("매우 가까움", V2Calibration.stepLabel(ble(rssi = -68)))
        assertEquals("매우 가까움 · 약 0.6m", V2Distance.badge(ble(rssi = -68), true))
    }

    @Test
    fun no_ordinary_room_level_is_called_half_a_metre() {
        // The -68…-76 dBm band a usual -59…-67 dBm device shows at 1–3 m: none of it is 50cm.
        for (rssi in -66 downTo -80) {
            val text = V2Calibration.metresText(ble(rssi = rssi)) ?: ""
            assertNotEquals("$rssi dBm must not be 50cm 이내", "50cm 이내", text)
            assertTrue("$rssi dBm must still be written in metres: $text", text.startsWith("약 "))
            assertTrue(
                "$rssi dBm must be at least half a metre: $text",
                (V2Calibration.metres(ble(rssi = rssi)) ?: 0.0) >= 0.5
            )
        }
    }

    @Test
    fun the_room_levels_read_at_the_middle_scale() {
        // -74 dBm at 1 m, n = 3.0: 30 dB per tenfold distance.
        assertEquals("약 1m", V2Calibration.metresText(ble(rssi = -74)))
        assertEquals("약 1.2m", V2Calibration.metresText(ble(rssi = -76)))
        assertEquals("약 1.6m", V2Calibration.metresText(ble(rssi = -80)))
        assertEquals("약 2.5m", V2Calibration.metresText(ble(rssi = -86)))
        assertEquals("약 4m", V2Calibration.metresText(ble(rssi = -92)))
        assertEquals("약 6.3m", V2Calibration.metresText(ble(rssi = -98)))

        assertEquals("가까움", V2Calibration.stepLabel(ble(rssi = -74)))
        assertEquals("가까움", V2Calibration.stepLabel(ble(rssi = -80)))
        assertEquals("보통", V2Calibration.stepLabel(ble(rssi = -86)))
        assertEquals("보통", V2Calibration.stepLabel(ble(rssi = -92)))
        assertEquals("보통", V2Calibration.stepLabel(ble(rssi = -98)))

        val metre = V2Calibration.metres(ble(rssi = -74)) ?: 0.0
        assertEquals("1 m must be exactly 1 m", 1.0, metre, 0.001)
    }

    @Test
    fun the_tablet_he_measured_stays_between_his_two_versions() {
        // The Galaxy tablet 0.8–1 m away on his desk, heard at -86 dBm. v2.8 said `보통 · 약 4.1m`,
        // v2.9 over-corrected to `가까움 · 약 1.1m`, v2.10 writes `보통 · 약 2.5m` — closer to his
        // measurement than v2.8, and without the 50cm false alarm on ordinary devices.
        val tablet = ble(rssi = -86, address = "5C:0A:5B:00:00:01")

        assertEquals("보통", V2Calibration.stepLabel(tablet))
        assertEquals("약 2.5m", V2Calibration.metresText(tablet))
        assertEquals("보통 · 약 2.5m", V2Distance.badge(tablet, true))

        val metres = V2Calibration.metres(tablet) ?: 0.0
        assertTrue("$metres m must beat v2.8's 4.1 m", metres < 4.1)
        assertTrue("$metres m must not be v2.9's half metre", metres > 1.0)
    }

    @Test
    fun a_hand_held_device_is_still_in_your_hand() {
        // -30 … -64 dBm: right beside him, whatever the version.
        for (rssi in listOf(-30, -40, -50, -55, -60, -64)) {
            assertEquals("$rssi dBm is in your hand", V2Look.VERY_NEAR, V2Calibration.stepLabel(ble(rssi = rssi)))
            assertEquals("$rssi dBm is 50cm 이내", "50cm 이내", V2Calibration.metresText(ble(rssi = rssi)))
        }
        assertEquals("50cm 이내", V2Calibration.metresText(ble(rssi = -30)))
    }

    @Test
    fun an_advertised_one_metre_value_gets_the_same_offset_as_the_room_reference() {
        // A usual -59 dBm advertisement is exactly the room's 1 m level (-59 - 15 = -74).
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
        assertEquals("약 1m", V2Calibration.metresText(ble(rssi = -74, txPower = -59)))
        // A stronger advertisement is believed too, one scale above: -52 - 15 = -67 dBm at 1 m.
        assertEquals("50cm 이내", V2Calibration.metresText(ble(rssi = -55, txPower = -52)))
        assertEquals("약 1m", V2Calibration.metresText(ble(rssi = -67, txPower = -52)))
    }

    @Test
    fun a_zero_metre_output_is_not_read_as_a_weak_one_metre_value() {
        // Apple and some chips advertise their antenna output (0 m) in the same field.
        val bogus = V2Calibration.metres(ble(rssi = -86, txPower = -45)) ?: 0.0
        val plain = V2Calibration.metres(ble(rssi = -86)) ?: 0.0
        assertEquals("an out-of-range txPower is ignored", plain, bogus, 0.001)
        assertEquals("약 2.5m", V2Calibration.metresText(ble(rssi = -86, txPower = -45)))
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
    fun the_screens_and_the_badge_read_the_same_number() {
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
        val far = ble(rssi = -100, address = "AA:BB:CC:DD:EE:02")
        val mid = ble(rssi = -85, address = "AA:BB:CC:DD:EE:03")
        val nearby = ble(rssi = -95, address = "AA:BB:CC:DD:EE:01")

        // On the shared free-space default a -95 dBm device is pushed past the 10 m ring.
        val before = Classifier.distanceMeters(-95.0, SignalSource.BLE) ?: 0.0
        val beforeText = "%.1f".format(before)
        assertTrue("the shared default said ${beforeText}m", before > NearbyRadarMath.LIMIT_METRES)

        // v2.10 puts every one of them inside a room, in the order of their level.
        val blips = NearbyRadarMath.nearbyBlips(
            listOf(nearby, mid, far),
            { signal -> V2Calibration.metres(signal) },
            { null }
        )
        assertEquals("all three are inside the room scale", 3, blips.size)
        assertEquals(listOf(mid.id, nearby.id, far.id), blips.map { it.signal.id })
        assertEquals(2.33f, blips[0].metres, 0.05f)
        assertEquals(5.01f, blips[1].metres, 0.05f)
        assertEquals(7.36f, blips[2].metres, 0.05f)
        assertEquals("2.3m", NearbyRadarMath.metreText(blips[0].metres))

        // Without the hook the shared default still decides, exactly as before: only the
        // -85 dBm device was inside 10 m back then.
        val shared = NearbyRadarMath.nearbyBlips(listOf(nearby, mid, far)) { null }
        assertEquals(1, shared.size)
        assertEquals(mid.id, shared.first().signal.id)
    }
}
