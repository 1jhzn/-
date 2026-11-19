package com.jihun.signalradar.nearby

import com.jihun.signalradar.identify.DeviceType
import com.jihun.signalradar.model.Signal
import com.jihun.signalradar.model.SignalSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 10 m radar's numbers: what is inside the circle, where it is drawn, and how the
 * direction is handled when nobody has measured it.
 */
class NearbyRadarMathTest {

    private fun signal(
        id: String,
        source: SignalSource,
        title: String,
        rssi: Int,
        address: String = id,
        txPower: Int? = null,
        frequencyMhz: Int? = null,
        kind: String? = null,
        smoothed: Float? = null
    ) = Signal(
        id = id,
        source = source,
        title = title,
        vendor = null,
        kind = kind,
        detail = null,
        address = address,
        rssi = rssi,
        firstSeen = 0L,
        lastSeen = 0L,
        smoothedRssi = smoothed ?: rssi.toFloat(),
        txPower = txPower,
        frequencyMhz = frequencyMhz
    )

    @Test
    fun only_bluetooth_and_wifi_within_ten_metres_get_a_blip() {
        val signals = listOf(
            signal("ble:airpods", SignalSource.BLE, "지훈의 AirPods", -48, txPower = -52),
            signal("ble:buds", SignalSource.BLE, "버즈", -74),
            signal("wifi:5g", SignalSource.WIFI, "iptime_5G", -62, frequencyMhz = 5745),
            // 2.4 GHz at -70 dBm is about 11 m indoors: outside the circle.
            signal("wifi:2g", SignalSource.WIFI, "KT_GIGA_2G", -70, frequencyMhz = 2437),
            // A base station's level says nothing about its distance.
            signal("cell:1", SignalSource.CELL, "LTE 450-3", -85)
        )

        val blips = NearbyRadarMath.nearbyBlips(signals) { null }

        // 0.7 m AirPods, ~3.4 m buds, ~3.6 m router: nearest first.
        assertEquals(
            listOf("ble:airpods", "ble:buds", "wifi:5g"),
            blips.map { it.signal.id }
        )
        // Nearest first, and all of them inside the outer ring.
        assertTrue(blips[0].metres < blips[1].metres)
        assertTrue(blips.all { it.metres <= NearbyRadarMath.MAX_METRES })
        assertTrue(blips.none { it.measured })
    }

    @Test
    fun a_measured_bearing_is_kept_and_reported_as_measured() {
        val signals = listOf(
            signal("ble:airpods", SignalSource.BLE, "지훈의 AirPods", -48, txPower = -52),
            signal("wifh:5g", SignalSource.WIFI, "iptime_5G", -62, frequencyMhz = 5745)
        )

        val blips = NearbyRadarMath.nearbyBlips(signals) { id ->
            if (id == "ble:airpods") 38f else null
        }

        val airpods = blips.first { it.signal.id == "ble:airpods" }
        assertEquals(38f, airpods.bearing ?: -1f, 0.001f)
        assertTrue(airpods.measured)
        val router = blips.first { it.signal.id == "wifh:5g" }
        assertNull(router.bearing)
        assertFalse(router.measured)
    }

    @Test
    fun the_radius_is_a_share_of_ten_metres_and_never_sits_on_the_centre() {
        assertEquals(0.2f, NearbyRadarMath.radiusFraction(2f), 0.001f)
        assertEquals(0.5f, NearbyRadarMath.radiusFraction(5f), 0.001f)
        assertEquals(1f, NearbyRadarMath.radiusFraction(10f), 0.001f)
        // Beyond the ring is clamped onto it, and "right next to me" keeps a gap.
        assertEquals(1f, NearbyRadarMath.radiusFraction(40f), 0.001f)
        assertEquals(NearbyRadarMath.MIN_FRACTION, NearbyRadarMath.radiusFraction(0f), 0.0001f)
        assertEquals(NearbyRadarMath.MIN_FRACTION, NearbyRadarMath.radiusFraction(0.1f), 0.0001f)
    }

    @Test
    fun a_placeholder_direction_is_the_same_on_every_refresh() {
        val first = NearbyRadarMath.placeholderAngles(
            listOf("AA:BB:CC:DD:EE:01", "AA:BB:CC:DD:EE:02", "11:22:33:44:55:66")
        )
        val second = NearbyRadarMath.placeholderAngles(
            listOf("AA:BB:CC:DD:EE:02", "11:22:33:44:55:66", "AA:BB:CC:DD:EE:01")
        )
        assertEquals(first, second)
        assertEquals(3, first.size)
        for (angle in first.values) {
            assertTrue("angle=$angle", angle >= 0f && angle < 360f)
        }
    }

    @Test
    fun two_devices_sharing_a_placeholder_slot_are_spread_apart() {
        // Different addresses, same 15° slot: they must not be drawn on top of each other.
        val slot = NearbyRadarMath.slotOf("AA:BB:CC:DD:EE:01")
        val twin = (0 until 400).map { "AA:BB:CC:DD:EF:%02d".format(it) }
            .first { it != "AA:BB:CC:DD:EE:01" && NearbyRadarMath.slotOf(it) == slot }
        val angles = NearbyRadarMath.placeholderAngles(
            listOf("AA:BB:CC:DD:EE:01", twin)
        )
        val first = angles.getValue("AA:BB:CC:DD:EE:01")
        val second = angles.getValue(twin)
        assertTrue("first=$first second=$second", first != second)
        // Both stay inside their slot, so a device does not wander across the radar.
        val middle = slot * NearbyRadarMath.SLOT_DEG
        assertTrue("first=$first", circularDistance(first, middle) <= NearbyRadarMath.SLOT_DEG / 2f)
        assertTrue("second=$second", circularDistance(second, middle) <= NearbyRadarMath.SLOT_DEG / 2f)
    }

    private fun circularDistance(a: Float, b: Float): Float {
        val diff = kotlin.math.abs(NearbyRadarMath.normalize(a) - NearbyRadarMath.normalize(b))
        return if (diff > 180f) 360f - diff else diff
    }

    @Test
    fun distances_read_like_something_beside_a_dot() {
        assertEquals("0.8m", NearbyRadarMath.metreText(0.8f))
        assertEquals("2.4m", NearbyRadarMath.metreText(2.44f))
        assertEquals("10m", NearbyRadarMath.metreText(10f))
        assertEquals("1m", NearbyRadarMath.metreText(1.02f))
    }

    @Test
    fun a_long_name_is_shortened_without_cutting_a_syllable_in_half() {
        assertEquals("지훈의 AirPods", NearbyRadarMath.shortName("지훈의 AirPods"))
        val long = NearbyRadarMath.shortName("지훈의 아주아주 긴 에어팟 이름입니다")
        assertTrue(long.length <= 12)
        assertTrue(long.endsWith("…"))
    }

    @Test
    fun device_types_are_short_enough_for_a_dot_label() {
        assertEquals("에어팟", DeviceType.of(signal("a", SignalSource.BLE, "지훈의 AirPods", -48, kind = "에어팟·비츠")).label)
        assertEquals("버즈", DeviceType.of(signal("b", SignalSource.BLE, "Buds2", -60, kind = "삼성 기기(갤럭시·버즈·워치)")).label)
        assertEquals("공유기", DeviceType.of(signal("c", SignalSource.WIFI, "iptime_5G", -60, kind = "ipTIME 공유기")).label)
        assertEquals("핫스팟", DeviceType.of(signal("d", SignalSource.WIFI, "지훈의 iPhone", -60, kind = "아이폰 핫스팟")).label)
        assertEquals("추적기", DeviceType.of(signal("e", SignalSource.BLE, "태그", -60, kind = "에어태그·분실물 추적기(Find My)")).label)
        // Nothing recognised still says something honest.
        assertNotNull(DeviceType.of(signal("f", SignalSource.BLE, "이름 없는 블루투스 기기", -60)).label)
    }
}
