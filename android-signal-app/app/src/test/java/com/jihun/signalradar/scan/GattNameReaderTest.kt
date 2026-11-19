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
 * long wait: a refusal or a hang-up settles it immediately, and the whole budget is 2.5 s.
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
    fun the_whole_attempt_is_over_in_two_and_a_half_seconds() {
        assertEquals(2_500L, GattNameReader.TIMEOUT_MS)
    }
}
