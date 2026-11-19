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
 * Most of the time an advertisement already carries a name, but many earphones and
 * watches only answer the standard GATT characteristics: Device Name (0x2A00) under
 * Generic Access (0x1800), and Manufacturer Name (0x2A29) under Device Information
 * (0x180A). Connecting is a visible act to the other device, so this never runs on its
 * own — the detail screen calls [read] from a button, and the connection is closed on
 * every path (success, refusal, timeout, cancel) by [finish].
 *
 * The 8 second budget and the honest "it blocked us" wording come from the fact that many
 * modern phones, AirPods and trackers refuse a connection from an unknown phone.
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
    private val readQueue = ArrayDeque<BluetoothGattCharacteristic>()
    private var gatt: BluetoothGatt? = null
    private var finished = false
    private var deviceName: String? = null
    private var manufacturer: String? = null

    private val timeout = Runnable {
        finish(
            if (deviceName != null || manufacturer != null) {
                found()
            } else {
                Result.Failed("8초 동안 응답이 없었어요. ${DeviceName.BLOCKED_MESSAGE}")
            }
        )
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            if (finished) return
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                onStatus("연결됨 · 이름 항목을 찾는 중…")
                val started = try {
                    gatt?.discoverServices() == true
                } catch (e: SecurityException) {
                    false
                } catch (e: Exception) {
                    Log.w(TAG, "discoverServices failed", e)
                    false
                }
                if (!started) fail(DeviceName.BLOCKED_MESSAGE)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                // Some devices answer and hang up straight away; keep whatever was read.
                finish(
                    if (deviceName != null || manufacturer != null) {
                        found()
                    } else {
                        Result.Failed(DeviceName.BLOCKED_MESSAGE)
                    }
                )
            } else if (status != BluetoothGatt.GATT_SUCCESS) {
                fail(DeviceName.BLOCKED_MESSAGE)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (finished) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail(DeviceName.BLOCKED_MESSAGE)
                return
            }
            readQueue.clear()
            val nameCharacteristic = characteristic(
                gatt,
                GENERIC_ACCESS,
                DEVICE_NAME
            )
            val manufacturerCharacteristic = characteristic(
                gatt,
                DEVICE_INFORMATION,
                MANUFACTURER_NAME
            )
            nameCharacteristic?.let { readQueue.addLast(it) }
            manufacturerCharacteristic?.let { readQueue.addLast(it) }

            if (readQueue.isEmpty()) {
                fail("이 기기는 상세 이름 항목을 공개하지 않아요. ${DeviceName.BLOCKED_MESSAGE}")
                return
            }
            onStatus("이름 항목 ${readQueue.size}개를 읽는 중…")
            readNext()
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
        onStatus("GATT 연결 중… (최대 8초)")
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

    private fun readNext() {
        if (finished) return
        val next = readQueue.removeFirstOrNull()
        if (next == null) {
            finish(
                if (deviceName != null || manufacturer != null) {
                    found()
                } else {
                    Result.Failed(DeviceName.BLOCKED_MESSAGE)
                }
            )
            return
        }
        val started = try {
            gatt?.readCharacteristic(next) == true
        } catch (e: SecurityException) {
            false
        } catch (e: Exception) {
            false
        }
        if (!started) readNext()
    }

    private fun onRead(uuid: UUID, value: ByteArray?) {
        if (finished) return
        val text = DeviceName.decode(value)
        if (text != null) {
            when (uuid) {
                DEVICE_NAME -> deviceName = text
                MANUFACTURER_NAME -> manufacturer = text
            }
        }
        readNext()
    }

    private fun found(): Result = Result.Found(deviceName, manufacturer)

    private fun fail(message: String) {
        finish(Result.Failed(message))
    }

    private fun finish(result: Result?) {
        if (finished) return
        finished = true
        handler.removeCallbacks(timeout)
        val connection = gatt
        gatt = null
        try {
            connection?.disconnect()
        } catch (e: SecurityException) {
            Log.w(TAG, "disconnect needs BLUETOOTH_CONNECT", e)
        } catch (e: Exception) {
            Log.w(TAG, "disconnect failed", e)
        }
        try {
            connection?.close()
        } catch (e: SecurityException) {
            Log.w(TAG, "close needs BLUETOOTH_CONNECT", e)
        } catch (e: Exception) {
            Log.w(TAG, "close failed", e)
        }
        if (result != null) onResult(result)
    }

    companion object {
        private const val TAG = "GattNameReader"
        private const val TIMEOUT_MS = 8_000L

        private val GENERIC_ACCESS = uuid("1800")
        private val DEVICE_INFORMATION = uuid("180A")
        private val DEVICE_NAME = uuid("2A00")
        private val MANUFACTURER_NAME = uuid("2A29")

        private fun uuid(short: String): UUID =
            UUID.fromString("0000$short-0000-1000-8000-00805f9b34fb")
    }
}
