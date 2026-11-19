package com.jihun.signalradar.scan

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.jihun.signalradar.identify.Classifier
import com.jihun.signalradar.identify.VendorDb
import com.jihun.signalradar.model.Signal
import com.jihun.signalradar.model.SignalSource
import java.util.concurrent.Executor

/**
 * Wi-Fi scan around the phone. The system owns the scan itself; we ask for fresh
 * results and read whatever the phone already has, so no network is joined or probed.
 */
class WifiScanner(
    private val context: Context,
    private val onSignals: (List<Signal>) -> Unit,
    private val onProblem: (String) -> Unit
) {

    private val appContext: Context = context.applicationContext
    private val wifi: WifiManager? =
        appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val handler = Handler(Looper.getMainLooper())
    private val executor: Executor = Executor { command -> handler.post(command) }

    private var running = false
    private var lastRequest = 0L
    private var lastRequestWorked = true
    private var callback: WifiManager.ScanResultsCallback? = null
    private var receiver: BroadcastReceiver? = null

    private val poller = object : Runnable {
        override fun run() {
            if (!running) return
            requestScanIfDue()
            emit()
            handler.postDelayed(this, POLL_MS)
        }
    }

    fun start() {
        if (running) return
        val manager = wifi
        if (manager == null) {
            onProblem("이 폰에서는 와이파이 목록을 읽을 수 없어요")
            return
        }
        running = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            registerResultsCallback(manager)
        } else {
            registerResultsReceiver()
        }
        requestScanIfDue()
        emit()
        handler.postDelayed(poller, POLL_MS)
    }

    fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacks(poller)
        val cb = callback
        if (cb != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    wifi?.unregisterScanResultsCallback(cb)
                } catch (e: Exception) {
                    Log.w(TAG, "unregister callback failed", e)
                }
            }
            callback = null
        }
        val r = receiver
        if (r != null) {
            try {
                appContext.unregisterReceiver(r)
            } catch (e: Exception) {
                Log.w(TAG, "unregister receiver failed", e)
            }
            receiver = null
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun registerResultsCallback(manager: WifiManager) {
        val cb = object : WifiManager.ScanResultsCallback() {
            override fun onScanResultsAvailable() {
                emit()
            }
        }
        callback = cb
        try {
            manager.registerScanResultsCallback(executor, cb)
        } catch (e: Exception) {
            Log.w(TAG, "registerScanResultsCallback failed", e)
        }
    }

    private fun registerResultsReceiver() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                emit()
            }
        }
        receiver = r
        try {
            ContextCompat.registerReceiver(
                appContext,
                r,
                IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (e: Exception) {
            Log.w(TAG, "registerReceiver failed", e)
        }
    }

    /**
     * Ask the system for a fresh Wi-Fi scan right now. Used while direction finding, where
     * the newest level matters most; Android still throttles this to roughly four scans per
     * two minutes for foreground apps, so a false result is normal and not an error.
     */
    fun requestImmediateScan(): Boolean {
        val manager = wifi ?: return false
        if (!hasScanPermission()) return false
        lastRequest = System.currentTimeMillis()
        return try {
            manager.startScan()
        } catch (e: SecurityException) {
            false
        } catch (e: Exception) {
            Log.w(TAG, "startScan failed", e)
            false
        }
    }

    /** Android throttles scan requests; asking more often than every ~15 s is pointless. */
    private fun requestScanIfDue() {
        val manager = wifi ?: return
        val now = System.currentTimeMillis()
        if (now - lastRequest < REQUEST_INTERVAL_MS) return
        lastRequest = now
        if (!hasScanPermission()) return
        lastRequestWorked = try {
            manager.startScan()
        } catch (e: SecurityException) {
            false
        } catch (e: Exception) {
            Log.w(TAG, "startScan failed", e)
            false
        }
        if (!lastRequestWorked) {
            // The system refused a fresh scan (throttling or Wi-Fi off). Cached results
            // are still useful, so we keep reading them instead of showing an error.
            Log.i(TAG, "startScan was throttled; using cached scan results")
        }
    }

    private fun hasScanPermission(): Boolean {
        val needed = ArrayList<String>(2)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add("android.permission.NEARBY_WIFI_DEVICES")
        }
        needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        return needed.any {
            ContextCompat.checkSelfPermission(appContext, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun emit() {
        val manager = wifi ?: return
        val results: List<ScanResult> = try {
            manager.scanResults ?: emptyList()
        } catch (e: SecurityException) {
            emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        if (results.isEmpty()) {
            onSignals(emptyList())
            return
        }
        val now = System.currentTimeMillis()
        onSignals(results.mapNotNull { toSignal(it, now) })
    }

    private fun toSignal(result: ScanResult, now: Long): Signal? {
        val bssid = try {
            result.BSSID
        } catch (e: Exception) {
            null
        } ?: return null

        val ssid = try {
            result.SSID ?: ""
        } catch (e: Exception) {
            ""
        }

        val rawVendor = VendorDb.vendorForMac(bssid)
        val vendor = VendorDb.koreanName(rawVendor)
        val kind = Classifier.wifiKind(ssid, rawVendor)
        val band = Classifier.bandOf(result.frequency)
        val channel = Classifier.channelOf(result.frequency)
        val security = Classifier.wifiSecurity(result.capabilities ?: "")

        val detail = StringBuilder()
        detail.append(kind ?: "와이파이 신호")
        detail.append(" · ").append(band)
        if (channel != null) detail.append(' ').append(channel).append("ch")
        detail.append(" · ").append(security)

        return Signal(
            id = "wifi:" + bssid.uppercase(),
            source = SignalSource.WIFI,
            title = if (ssid.isBlank()) "숨김 SSID" else ssid,
            vendor = vendor,
            kind = kind,
            detail = detail.toString(),
            address = bssid.uppercase(),
            rssi = result.level,
            firstSeen = now,
            lastSeen = now,
            frequencyMhz = result.frequency
        )
    }

    companion object {
        private const val TAG = "WifiScanner"
        private const val POLL_MS = 3_000L
        private const val REQUEST_INTERVAL_MS = 15_000L
    }
}
