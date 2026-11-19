package com.jihun.signalradar.identify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** How a Bluetooth device's own name turns into something a person can read. */
class DeviceNameTest {

    @Test
    fun a_name_from_the_advertisement_is_broadcast_and_shown_with_a_speaker() {
        assertEquals(
            "📢 방송명: 지훈의 AirPods",
            DeviceName.broadcastLabel("지훈의 AirPods")
        )
        assertNull(DeviceName.broadcastLabel(null))
        assertNull(DeviceName.broadcastLabel("   "))
    }

    @Test
    fun padding_and_nul_bytes_are_stripped_from_a_gatt_name() {
        // Devices pad the Device Name characteristic with NULs to a fixed length.
        val bytes = "민수의 Galaxy Buds".toByteArray(Charsets.UTF_8) + ByteArray(4)
        assertEquals("민수의 Galaxy Buds", DeviceName.decode(bytes))
        assertEquals("민수의 Galaxy Buds", DeviceName.clean("민수의 Galaxy Buds\u0000\u0000"))
        assertEquals("AirPods Pro", DeviceName.clean("  AirPods \n Pro  "))
        assertNull(DeviceName.decode(ByteArray(0)))
        assertNull(DeviceName.decode(null))
    }

    @Test
    fun a_device_without_a_name_is_titled_by_what_it_is() {
        assertEquals(
            "이름 없는 블루투스 기기",
            DeviceName.title(null, null, null)
        )
        assertEquals("에어팟", DeviceName.title(null, null, "에어팟"))
        assertEquals("Galaxy Buds2", DeviceName.title(null, "Galaxy Buds2", "갤럭시"))
        // The name the device broadcasts beats the one the phone has cached.
        assertEquals("지훈의 버즈", DeviceName.title("지훈의 버즈", "Galaxy Buds2", "갤럭시"))
    }

    @Test
    fun the_phone_own_devices_get_a_badge() {
        assertEquals("🔗 내 폰 연결됨", DeviceName.badgeText(true, false))
        assertEquals("📌 내 등록 기기", DeviceName.badgeText(false, true))
        assertEquals("🔗 내 폰 연결됨 · 📌 내 등록 기기", DeviceName.badgeText(true, true))
        assertNull(DeviceName.badgeText(false, false))
    }

    @Test
    fun the_broadcast_name_is_a_hint_not_a_proof() {
        val hint = DeviceName.ownerHint("지훈의 AirPods") ?: ""
        assertTrue(hint.contains("기기가 스스로 알려 준 이름"))
        assertNull(DeviceName.ownerHint(null))
        assertTrue(DeviceName.isBroadcasting("지훈의 AirPods"))
        assertFalse(DeviceName.isBroadcasting("  "))
    }

    @Test
    fun the_blocked_message_says_what_happened() {
        assertTrue(DeviceName.BLOCKED_MESSAGE.contains("보안상"))
        assertTrue(DeviceName.BLOCKED_MESSAGE.contains("막아"))
    }
}
