package com.jihun.signalradar.scan

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.jihun.signalradar.identify.Classifier
import com.jihun.signalradar.identify.VendorDb
import com.jihun.signalradar.model.Signal
import com.jihun.signalradar.model.SignalSource

/**
 * Passive BLE scan. Nothing is paired or connected: the phone just listens to the
 * advertisements every device around it already broadcasts.
 */
class BleScanner(
    private val context: Context,
    private val onSignals: (List<Signal>) -> Unit,
    private val onProblem: (String) -> Unit
) {

    private val adapter: BluetoothAdapter? by lazy {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        manager?.adapter
    }

    private var scanning = false

    val isSupported: Boolean
        get() = adapter != null

    val isEnabled: Boolean
        get() = try {
            adapter?.isEnabled == true
        } catch (e: SecurityException) {
            false
        }

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val signal = result?.let { toSignal(it) } ?: return
            onSignals(listOf(signal))
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            if (results.isNullOrEmpty()) return
            val signals = results.mapNotNull { toSignal(it) }
            if (signals.isNotEmpty()) onSignals(signals)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            onProblem(failureText(errorCode))
        }
    }

    fun start(): Boolean {
        if (scanning) return true
        if (!hasScanPermission()) {
            onProblem("블루투스 권한이 없어서 주변 기기를 못 들어요")
            return false
        }
        val bluetooth = adapter
        if (bluetooth == null) {
            onProblem("이 폰에서는 블루투스를 쓸 수 없어요")
            return false
        }
        if (!isEnabled) {
            onProblem("블루투스가 꺼져 있어요")
            return false
        }
        val scanner = try {
            bluetooth.bluetoothLeScanner
        } catch (e: SecurityException) {
            null
        }
        if (scanner == null) {
            onProblem("블루투스 스캐너를 열 수 없어요")
            return false
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0L)
            .build()

        return try {
            scanner.startScan(null, settings, callback)
            scanning = true
            true
        } catch (e: SecurityException) {
            onProblem("블루투스 권한이 없어서 주변 기기를 못 들어요")
            false
        } catch (e: Exception) {
            Log.w(TAG, "startScan failed", e)
            onProblem("블루투스 스캔을 시작하지 못했어요")
            false
        }
    }

    fun stop() {
        if (!scanning) return
        scanning = false
        if (!hasScanPermission()) return
        try {
            adapter?.bluetoothLeScanner?.stopScan(callback)
        } catch (e: SecurityException) {
            Log.w(TAG, "stopScan needs bluetooth permission", e)
        } catch (e: Exception) {
            Log.w(TAG, "stopScan failed", e)
        }
    }

    private fun hasScanPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_SCAN
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        return ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun toSignal(result: ScanResult): Signal? {
        val device = result.device ?: return null

        val address = try {
            device.address
        } catch (e: SecurityException) {
            null
        }

        val record = try {
            result.scanRecord
        } catch (e: Exception) {
            null
        }

        var companyId: Int? = null
        var companyData: ByteArray? = null
        val manufacturers = record?.manufacturerSpecificData
        if (manufacturers != null && manufacturers.size() > 0) {
            companyId = manufacturers.keyAt(0)
            companyData = manufacturers.valueAt(0)
        }

        val rawVendor = VendorDb.vendorForCompany(companyId)
        val vendor = VendorDb.koreanName(rawVendor)
        val kind = Classifier.bleKind(companyId, companyData)

        val advertisedName = try {
            record?.deviceName ?: device.name
        } catch (e: SecurityException) {
            record?.deviceName
        }

        val services = try {
            record?.serviceUuids?.map { it.toString() }
        } catch (e: Exception) {
            null
        }
        val serviceHint = Classifier.bleServiceHint(services)

        val detailParts = ArrayList<String>(3)
        if (kind != null) detailParts.add(kind)
        if (serviceHint != null) detailParts.add("서비스 $serviceHint")
        if (companyId != null) {
            detailParts.add("제조사 ID 0x" + companyId.toString(16).uppercase().padStart(4, '0'))
        } else {
            detailParts.add("제조사 정보 없음")
        }

        val now = System.currentTimeMillis()
        val stableAddress = if (address.isNullOrBlank() || address == "02:00:00:00:00:00") {
            "$companyId:${advertisedName ?: "unknown"}"
        } else {
            address
        }

        return Signal(
            id = "ble:$stableAddress",
            source = SignalSource.BLE,
            title = advertisedName?.takeIf { it.isNotBlank() }
                ?: kind
                ?: "이름 없는 블루투스 기기",
            vendor = vendor,
            kind = kind,
            detail = detailParts.joinToString(" · "),
            address = address?.uppercase() ?: "주소 비공개",
            rssi = result.rssi,
            firstSeen = now,
            lastSeen = now
        )
    }

    private fun failureText(errorCode: Int): String = when (errorCode) {
        ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "블루투스 스캔이 이미 돌고 있어요"
        ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "블루투스 스캔을 등록하지 못했어요"
        ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "블루투스 스캔 오류가 났어요"
        ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "이 폰은 주변 블루투스 스캔을 지원하지 않아요"
        ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES -> "블루투스 스캔 자원이 부족해요"
        ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY -> "스캔을 너무 자주 켜서 잠시 막혔어요"
        else -> "블루투스 스캔이 멈췄어요"
    }

    companion object {
        private const val TAG = "BleScanner"
    }
}
