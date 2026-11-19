package com.jihun.signalradar.scan

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothProfile
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The "🔍 기기 이름 더 읽어오기" attempt must end as soon as the device answers, not after a
 * long wait: a refusal or a hang-up settles it immediately, the one read gets 0.7 s of its
 * own, and the whole budget is 3.5 s — long enough for the slow earphones and watches 지훈
 * found were cut off at 1.5 s.
 *
 * 지훈: "연결시간은 빨라지고 좋은데 읽는시간이 너무 오래걸려" — the connect already settles in
 * a few hundred milliseconds, so the wait that felt long was the read step after discovery.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class GattNameReaderTest {

    @Test
    fun a_successful_connection_keeps_going() {
        assertFalse(
            GattNameReader.shouldStop(
                BluetoothGatt.GATT_SUCCESS,
                BluetoothProfile.STATE_CONNECTED
            )
        )
        assertFalse(
            GattNameReader.shouldStop(
                BluetoothGatt.GATT_SUCCESS,
                BluetoothProfile.STATE_CONNECTING
            )
        )
    }

    @Test
    fun a_refused_connection_stops_right_away() {
        // 133 is what phones return when the other side says no; some models use 257.
        assertTrue(GattNameReader.shouldStop(133, BluetoothProfile.STATE_CONNECTING))
        assertTrue(GattNameReader.shouldStop(257, BluetoothProfile.STATE_CONNECTING))
    }

    @Test
    fun a_device_that_hangs_up_stops_right_away() {
        assertTrue(
            GattNameReader.shouldStop(
                BluetoothGatt.GATT_SUCCESS,
                BluetoothProfile.STATE_DISCONNECTED
            )
        )
        assertTrue(GattNameReader.shouldStop(133, BluetoothProfile.STATE_DISCONNECTED))
    }

    @Test
    fun the_whole_attempt_is_over_in_three_and_a_half_seconds() {
        assertEquals(3_500L, GattNameReader.TIMEOUT_MS)
    }

    @Test
    fun the_single_name_read_gets_seven_hundred_milliseconds() {
        // A device that publishes Device Name answers in 100–300 ms; past 0.7 s it is
        // refusing (pairing/encryption), so the answer must not be dragged out to 2 s.
        assertEquals(700L, GattNameReader.READ_TIMEOUT_MS)
        assertTrue(GattNameReader.READ_TIMEOUT_MS < GattNameReader.TIMEOUT_MS)
    }

    @Test
    fun the_progress_line_says_what_it_is_actually_doing() {
        assertEquals("기기 이름 읽는 중…", GattNameReader.READING_MESSAGE)
        assertEquals(
            "이름을 읽는 데 응답이 없었어요. 이 기기는 보안상 상세 이름 공개를 막아 뒀어요",
            GattNameReader.READ_TIMEOUT_MESSAGE
        )
        assertTrue(GattNameReader.TIMEOUT_MESSAGE.startsWith("3.5초 동안 응답이 없었어요."))
    }
}
