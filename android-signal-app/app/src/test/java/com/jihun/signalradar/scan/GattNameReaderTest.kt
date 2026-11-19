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
 * long wait: a refusal or a hang-up settles it immediately, the one read gets 0.8 s of its
 * own, and the whole budget is 1.5 s.
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
    fun the_whole_attempt_is_over_in_a_second_and_a_half() {
        assertEquals(1_500L, GattNameReader.TIMEOUT_MS)
    }

    @Test
    fun the_single_name_read_gets_less_than_a_second() {
        // AirPods and newer phones accept the connection and then never answer the read;
        // 0.8 s is enough to tell "slow" from "silent".
        assertEquals(800L, GattNameReader.READ_TIMEOUT_MS)
        assertTrue(GattNameReader.READ_TIMEOUT_MS < GattNameReader.TIMEOUT_MS)
    }

    @Test
    fun the_progress_line_says_what_it_is_actually_doing() {
        assertEquals("기기 이름 읽는 중…", GattNameReader.READING_MESSAGE)
        assertTrue(GattNameReader.TIMEOUT_MESSAGE.startsWith("1.5초 동안 응답이 없었어요."))
    }
}
