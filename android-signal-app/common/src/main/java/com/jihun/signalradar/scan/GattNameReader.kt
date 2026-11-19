package com.jihun.signalradar.scan

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.jihun.signalradar.identify.DeviceName
import java.util.UUID

/**
 * Reads a BLE device's own name, once, only when the user asks for it.
 *
 * Most of the time an advertisement already carries a name, but some earphones and watches
 * only answer the standard GATT characteristic Device Name (0x2A00) under Generic Access
 * (0x1800). Connecting is a visible act to the other device, so this never runs on its own —
 * the detail screen calls [read] from a button, and the connection is closed on every path
 * (success, refusal, timeout, cancel) by [finish].
 *
 * Only that one characteristic is read: Manufacturer Name (0x2A29) was a second round trip
 * that cost time while the manufacturer is already known from the OUI / Bluetooth SIG tables.
 *
 * Two budgets keep the button from feeling broken: [TIMEOUT_MS] for the whole attempt, and
 * [READ_TIMEOUT_MS] once the services are discovered, because many modern phones, AirPods and
 * trackers accept the connection and then simply never answer a read that wants pairing.
 * The read budget is short on purpose: a device that publishes Device Name after discovery
 * answers in 100–300 ms, so anything still silent after 0.7 s is refusing rather than slow —
 * waiting longer only made 지훈 stare at "이름 읽는 중…". The whole budget stays 3.5 s because
 * the connect itself can be slow on older earphones.
 * The result reaches the screen before the connection is torn down: `disconnect()` and
 * `close()` take the BLE stack's synchronous lock, so they run on their own thread.
 * [cancel] settles the attempt from the button ("⏹ 읽기 멈추기") without reporting a result.
 */
class GattNameReader(
    private val context: Context,
    private val onStatus: (String) -> Unit,
    private val onResult: (Result) -> Unit
) {

    sealed class Result {
        data class Found(val deviceName: String?, val manufacturer: String?) : Result()
        data class Failed(val message: String) : Result()
    }

    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var gatt: BluetoothGatt? = null

    @Volatile
    private var finished = false

    private var deviceName: String? = null

    /** Whole-attempt budget: connect + discovery + the single name read. */
    private val timeout = Runnable {
        finish(if (deviceName != null) found() else Result.Failed(TIMEOUT_MESSAGE))
    }

    /** Budget for the one read request after the services are known. */
    private val readTimeout = Runnable {
        finish(if (deviceName != null) found() else Result.Failed(READ_TIMEOUT_MESSAGE))
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            if (finished) return
            // A refusal (133 and friends) or a hang-up answers the question right away.
            // Waiting out the timeout after that is what made the button feel stuck.
            if (shouldStop(status, newState)) {
                finish(if (deviceName != null) found() else Result.Failed(DeviceName.BLOCKED_MESSAGE))
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                postStatus("연결됨 · 이름 항목을 찾는 중…")
                val started = try {
                    gatt?.discoverServices() == true
                } catch (e: SecurityException) {
                    false
                } catch (e: Exception) {
                    Log.w(TAG, "discoverServices failed", e)
                    false
                }
                if (!started) fail(DeviceName.BLOCKED_MESSAGE)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (finished) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail(DeviceName.BLOCKED_MESSAGE)
                return
            }
            val characteristic = characteristic(gatt, GENERIC_ACCESS, DEVICE_NAME)
            if (characteristic == null) {
                fail("이 기기는 이름 항목을 공개하지 않아요. ${DeviceName.BLOCKED_MESSAGE}")
                return
            }
            postStatus(READING_MESSAGE)
            // The device is connected but silent from here on out on many phones and
            // earphones, so the read gets a short budget of its own.
            handler.removeCallbacks(readTimeout)
            handler.postDelayed(readTimeout, READ_TIMEOUT_MS)
            val started = try {
                gatt?.readCharacteristic(characteristic) == true
            } catch (e: SecurityException) {
                false
            } catch (e: Exception) {
                false
            }
            if (!started) fail(DeviceName.BLOCKED_MESSAGE)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            onRead(characteristic.uuid, if (status == BluetoothGatt.GATT_SUCCESS) characteristic.value else null)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            onRead(characteristic.uuid, if (status == BluetoothGatt.GATT_SUCCESS) value else null)
        }
    }

    /** Ask [device] for its stored name. Does nothing when the permission is missing. */
    fun read(device: BluetoothDevice) {
        if (finished) return
        val missing = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) !=
                PackageManager.PERMISSION_GRANTED
        } else {
            false
        }
        if (missing) {
            fail("블루투스 권한이 없어 이름을 읽을 수 없어요. 권한을 허용한 뒤 다시 눌러 주세요.")
            return
        }
        postStatus("GATT 연결 중… (최대 3.5초)")
        handler.postDelayed(timeout, TIMEOUT_MS)
        try {
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            if (gatt == null) fail(DeviceName.BLOCKED_MESSAGE)
        } catch (e: SecurityException) {
            fail("블루투스 권한이 없어 이름을 읽을 수 없어요. 권한을 허용한 뒤 다시 눌러 주세요.")
        } catch (e: Exception) {
            Log.w(TAG, "connectGatt failed", e)
            fail(DeviceName.BLOCKED_MESSAGE)
        }
    }

    /** Give up cleanly: a lingering GATT connection drains the battery. */
    fun cancel() {
        finish(null)
    }

    private fun characteristic(
        gatt: BluetoothGatt?,
        serviceUuid: UUID,
        characteristicUuid: UUID
    ): BluetoothGattCharacteristic? = try {
        gatt?.getService(serviceUuid)?.getCharacteristic(characteristicUuid)
    } catch (e: Exception) {
        null
    }

    private fun onRead(uuid: UUID, value: ByteArray?) {
        if (finished) return
        if (uuid == DEVICE_NAME) {
            deviceName = DeviceName.decode(value) ?: deviceName
        }
        handler.removeCallbacks(readTimeout)
        finish(if (deviceName != null) found() else Result.Failed(DeviceName.BLOCKED_MESSAGE))
    }

    private fun found(): Result = Result.Found(deviceName, null)

    /** Every status goes through the main thread, so a callback thread never touches a View. */
    private fun postStatus(text: String) {
        handler.post { onStatus(text) }
    }

    private fun fail(message: String) {
        finish(Result.Failed(message))
    }

    /**
     * Settle the attempt once: the UI gets the answer first, and the BLE teardown that
     * blocks on the stack's lock happens afterwards on a thread of its own.
     */
    private fun finish(result: Result?) {
        if (finished) return
        finished = true
        handler.removeCallbacks(timeout)
        handler.removeCallbacks(readTimeout)
        val connection = gatt
        gatt = null
        if (result != null) handler.post { onResult(result) }
        if (connection == null) return
        Thread {
            try {
                connection.disconnect()
            } catch (e: SecurityException) {
                Log.w(TAG, "disconnect needs BLUETOOTH_CONNECT", e)
            } catch (e: Exception) {
                Log.w(TAG, "disconnect failed", e)
            }
            try {
                connection.close()
            } catch (e: SecurityException) {
                Log.w(TAG, "close needs BLUETOOTH_CONNECT", e)
            } catch (e: Exception) {
                Log.w(TAG, "close failed", e)
            }
        }.apply {
            name = "gatt-close"
            isDaemon = true
        }.start()
    }

    companion object {
        private const val TAG = "GattNameReader"

        /** Budget for connect + service discovery + the single name read, all told. */
        internal const val TIMEOUT_MS = 3_500L

        /**
         * Budget for the one read once the device has accepted the connection. A device that
         * answers at all does so in a few hundred milliseconds here.
         */
        internal const val READ_TIMEOUT_MS = 700L

        internal const val READING_MESSAGE = "기기 이름 읽는 중…"

        internal const val TIMEOUT_MESSAGE =
            "3.5초 동안 응답이 없었어요. ${DeviceName.BLOCKED_MESSAGE}"

        internal const val READ_TIMEOUT_MESSAGE =
            "이름을 읽는 데 응답이 없었어요. ${DeviceName.BLOCKED_MESSAGE}"

        /**
         * True when this connection callback already settles the attempt: the stack
         * reported a failure status, or the device hung up. Both mean "no name from this
         * one", so the caller closes the connection instead of waiting for the timeout.
         */
        internal fun shouldStop(status: Int, newState: Int): Boolean =
            status != BluetoothGatt.GATT_SUCCESS ||
                newState == BluetoothProfile.STATE_DISCONNECTED

        private val GENERIC_ACCESS = uuid("1800")
        private val DEVICE_NAME = uuid("2A00")

        private fun uuid(short: String): UUID =
            UUID.fromString("0000$short-0000-1000-8000-00805f9b34fb")
    }
}
