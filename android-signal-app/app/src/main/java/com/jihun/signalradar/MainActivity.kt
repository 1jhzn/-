package com.jihun.signalradar

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.jihun.signalradar.identify.Classifier
import com.jihun.signalradar.model.Signal
import com.jihun.signalradar.model.SignalSource
import com.jihun.signalradar.scan.BleScanner
import com.jihun.signalradar.scan.CellScanner
import com.jihun.signalradar.scan.WifiScanner
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private enum class Filter { ALL, NEW, BLE, WIFI, CELL }

    private lateinit var repo: SignalRepository
    private lateinit var ble: BleScanner
    private lateinit var wifi: WifiScanner
    private lateinit var cell: CellScanner
    private lateinit var adapter: SignalAdapter

    private val handler = Handler(Looper.getMainLooper())
    private val newAlerted = HashSet<String>()

    private var scanning = false
    private var wantedScanning = false
    private var filter = Filter.ALL
    private var lastSignature: String? = null
    private var askedPermissions = false
    private var problemText: String? = null

    private lateinit var statusChip: TextView
    private lateinit var statusDetail: TextView
    private lateinit var alertBanner: View
    private lateinit var alertTitle: TextView
    private lateinit var alertBody: TextView
    private lateinit var noticeCard: View
    private lateinit var noticeText: TextView
    private lateinit var noticeAction: TextView
    private lateinit var statBle: TextView
    private lateinit var statWifi: TextView
    private lateinit var statCell: TextView
    private lateinit var statNew: TextView
    private lateinit var btnScan: TextView
    private lateinit var btnBaseline: TextView
    private lateinit var emptyState: View
    private lateinit var emptyText: TextView

    private lateinit var chips: Map<Filter, TextView>

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        askedPermissions = true
        if (missingPermissions().isEmpty()) {
            startScanning()
        } else {
            refresh()
        }
    }

    private val bluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (ble.isEnabled) startScanning() else refresh()
    }

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    private val cellPoller = object : Runnable {
        override fun run() {
            if (!scanning) return
            cell.poll()
            handler.postDelayed(this, CELL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        repo = SignalRepository(this)
        adapter = SignalAdapter { showDetails(it) }

        statusChip = findViewById(R.id.statusChip)
        statusDetail = findViewById(R.id.statusDetail)
        alertBanner = findViewById(R.id.alertBanner)
        alertTitle = findViewById(R.id.alertTitle)
        alertBody = findViewById(R.id.alertBody)
        noticeCard = findViewById(R.id.noticeCard)
        noticeText = findViewById(R.id.noticeText)
        noticeAction = findViewById(R.id.noticeAction)
        statBle = findViewById(R.id.statBle)
        statWifi = findViewById(R.id.statWifi)
        statCell = findViewById(R.id.statCell)
        statNew = findViewById(R.id.statNew)
        btnScan = findViewById(R.id.btnScan)
        btnBaseline = findViewById(R.id.btnBaseline)
        emptyState = findViewById(R.id.emptyState)
        emptyText = findViewById(R.id.emptyText)

        val list = findViewById<RecyclerView>(R.id.signalList)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        chips = mapOf(
            Filter.ALL to findViewById(R.id.chipAll),
            Filter.NEW to findViewById(R.id.chipNew),
            Filter.BLE to findViewById(R.id.chipBle),
            Filter.WIFI to findViewById(R.id.chipWifi),
            Filter.CELL to findViewById(R.id.chipCell)
        )
        for ((key, view) in chips) {
            view.setOnClickListener { setFilter(key) }
        }

        alertBanner.setOnClickListener { setFilter(Filter.NEW) }
        btnScan.setOnClickListener { if (scanning) stopScanning() else startScanning() }
        btnBaseline.setOnClickListener { saveBaseline() }

        ble = BleScanner(
            this,
            { signals -> repo.upsert(signals) },
            { problem -> reportProblem(problem) }
        )
        wifi = WifiScanner(
            this,
            { signals -> repo.upsert(signals) },
            { problem -> reportProblem(problem) }
        )
        cell = CellScanner(this, { signals -> repo.upsert(signals) })

        updateChips()
        updateScanButton()
        refresh()
    }

    override fun onStart() {
        super.onStart()
        if (wantedScanning && missingPermissions().isEmpty()) {
            startScanning()
        }
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(cellPoller)
        ble.stop()
        wifi.stop()
    }

    // ------------------------------------------------------------ scanning

    private fun startScanning() {
        val missing = missingPermissions()
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
            return
        }
        wantedScanning = true
        scanning = true
        problemText = null
        ble.start()
        wifi.start()
        handler.removeCallbacks(cellPoller)
        handler.post(cellPoller)
        updateScanButton()
        refresh()
    }

    private fun stopScanning() {
        wantedScanning = false
        scanning = false
        ble.stop()
        wifi.stop()
        handler.removeCallbacks(cellPoller)
        updateScanButton()
        refresh()
    }

    private fun reportProblem(text: String) {
        problemText = text
        refresh()
    }

    // ------------------------------------------------------------- rendering

    private fun refresh() {
        if (!::adapter.isInitialized) return
        repo.prune()
        val all = repo.snapshot()

        statBle.text = all.count { it.source == SignalSource.BLE }.toString()
        statWifi.text = all.count { it.source == SignalSource.WIFI }.toString()
        statCell.text = all.count { it.source == SignalSource.CELL }.toString()

        val newOnes = all.filter { it.isNew }.sortedByDescending { it.rssi }
        statNew.text = newOnes.size.toString()
        chips[Filter.NEW]?.text =
            if (newOnes.isEmpty()) "🚨 새 신호" else "🚨 새 신호 ${newOnes.size}"
        statNew.setTextColor(
            ContextCompat.getColor(
                this,
                if (newOnes.isEmpty()) R.color.text_primary else R.color.red
            )
        )

        if (newOnes.isEmpty()) {
            alertBanner.visibility = View.GONE
        } else {
            alertBanner.visibility = View.VISIBLE
            alertTitle.text = "🚨 평소에 없던 새 신호 ${newOnes.size}개"
            alertBody.text = describeNew(newOnes)
        }

        notifyNewSignals(newOnes)

        val visible = filterSignals(all)
        val signature = buildString {
            for (signal in visible) {
                append(signal.id).append(':').append(signal.rssi)
                    .append(signal.isNew).append(signal.isBoosted).append('|')
            }
        }
        if (signature != lastSignature) {
            lastSignature = signature
            adapter.submit(visible)
        }

        emptyState.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
        emptyText.text = emptyMessage()

        renderStatus(all)
        renderNotice()
    }

    private fun describeNew(newOnes: List<Signal>): String {
        val parts = ArrayList<String>(3)
        for (signal in newOnes.take(3)) {
            val where = Classifier.distanceLabel(signal.rssi, signal.source)
            val name = signal.vendor ?: signal.title
            parts.add(if (where != null) "$name · $where" else name)
        }
        val head = "가장 가까운 건 " + parts.joinToString(" / ")
        return if (newOnes.size > 3) "$head 외 ${newOnes.size - 3}개" else head
    }

    private fun notifyNewSignals(newOnes: List<Signal>) {
        var fresh = false
        for (signal in newOnes) {
            if (newAlerted.add(signal.id)) fresh = true
        }
        if (newAlerted.size > 400) newAlerted.clear()
        if (fresh) vibrate()
    }

    private fun vibrate() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        try {
            vibrator.vibrate(VibrationEffect.createOneShot(170, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) {
            // vibration is a nicety; never let it break the scan
        }
    }

    private fun filterSignals(all: List<Signal>): List<Signal> {
        val filtered = when (filter) {
            Filter.ALL -> all
            Filter.NEW -> all.filter { it.isNew || it.isBoosted }
            Filter.BLE -> all.filter { it.source == SignalSource.BLE }
            Filter.WIFI -> all.filter { it.source == SignalSource.WIFI }
            Filter.CELL -> all.filter { it.source == SignalSource.CELL }
        }
        return filtered.sortedWith(
            compareByDescending<Signal> { it.isNew }
                .thenByDescending { it.isBoosted }
                .thenByDescending { it.rssi }
        )
    }

    private fun emptyMessage(): String = when (filter) {
        Filter.NEW -> "새로 잡힌 신호가 없어요.\n‘기준 저장’ 뒤에 처음 나타난 신호가 여기에 보여요."
        Filter.BLE -> "지금 잡히는 블루투스 신호가 없어요."
        Filter.WIFI -> "지금 잡히는 와이파이가 없어요.\n폰의 ‘위치’가 켜져 있어야 목록이 보여요."
        Filter.CELL -> "지금 잡히는 기지국이 없어요."
        Filter.ALL -> "아직 잡힌 신호가 없어요.\n아래 ‘스캔 시작’을 눌러 주세요."
    }

    private fun renderStatus(all: List<Signal>) {
        if (scanning) {
            statusChip.text = "● 스캔 중"
            statusChip.setTextColor(ContextCompat.getColor(this, R.color.accent))
        } else {
            statusChip.text = "대기 중"
            statusChip.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        }

        statusDetail.text = when {
            missingPermissions().isNotEmpty() -> "권한을 허용하면 주변 신호를 읽을 수 있어요"
            scanning && repo.hasBaseline() ->
                "지금 ${all.size}개 신호 · 기준 ${repo.baselineSize()}개 (${timeText(repo.baselineSavedAt())} 저장)"
            scanning -> "지금 ${all.size}개 신호 · ‘기준 저장’을 누르면 새 신호를 알려드려요"
            else -> "‘스캔 시작’을 누르면 주변 전파를 읽기 시작해요"
        }
    }

    private fun renderNotice() {
        val missing = missingPermissions()
        if (missing.isNotEmpty()) {
            notice(
                "주변의 블루투스·와이파이·기지국을 읽으려면 ‘근처 기기’와 ‘위치’ 권한이 필요해요.\n" +
                    "읽은 신호는 폰 밖으로 나가지 않고 이 앱 안에서만 씁니다.",
                if (askedPermissions) "앱 권한 설정 열기" else "권한 허용"
            ) {
                if (askedPermissions) openAppSettings() else permissionLauncher.launch(missing.toTypedArray())
            }
            return
        }
        if (!ble.isSupported) {
            notice("이 폰에서는 블루투스 스캔을 쓸 수 없어요. 와이파이와 기지국은 계속 잡습니다.", null) {}
            return
        }
        if (scanning && !ble.isEnabled) {
            notice("블루투스가 꺼져 있어 와이파이·기지국만 잡고 있어요.", "블루투스 켜기") {
                requestBluetooth()
            }
            return
        }
        if (scanning && !locationEnabled()) {
            notice(
                "폰의 ‘위치’가 꺼져 있어 와이파이·기지국 목록이 비어 있을 수 있어요.\n" +
                    "위치는 신호를 읽는 용도로만 쓰고 저장하지 않습니다.",
                "위치 설정 열기"
            ) { openLocationSettings() }
            return
        }
        val problem = problemText
        if (problem != null) {
            notice(problem, "다시 시도") {
                problemText = null
                startScanning()
            }
            return
        }
        hideNotice()
    }

    private fun notice(text: String, actionLabel: String?, action: () -> Unit) {
        noticeCard.visibility = View.VISIBLE
        noticeText.text = text
        if (actionLabel == null) {
            noticeAction.visibility = View.GONE
        } else {
            noticeAction.visibility = View.VISIBLE
            noticeAction.text = actionLabel
            noticeAction.setOnClickListener { action() }
        }
    }

    private fun hideNotice() {
        noticeCard.visibility = View.GONE
    }

    private fun updateScanButton() {
        if (scanning) {
            btnScan.text = "■  스캔 중지"
            btnScan.setBackgroundResource(R.drawable.bg_button_stop)
            btnScan.setTextColor(ContextCompat.getColor(this, R.color.accent))
        } else {
            btnScan.text = "▶  스캔 시작"
            btnScan.setBackgroundResource(R.drawable.bg_button_primary)
            btnScan.setTextColor(ContextCompat.getColor(this, R.color.button_on_primary))
        }
    }

    private fun updateChips() {
        for ((key, view) in chips) {
            val selected = key == filter
            view.setBackgroundResource(
                if (selected) R.drawable.bg_chip_selected else R.drawable.bg_chip
            )
            view.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (selected) R.color.accent else R.color.text_secondary
                )
            )
        }
    }

    private fun setFilter(next: Filter) {
        filter = next
        lastSignature = null
        updateChips()
        refresh()
    }

    // ---------------------------------------------------------------- actions

    private fun saveBaseline() {
        val count = repo.saveBaseline()
        newAlerted.clear()
        lastSignature = null
        if (count == 0) {
            toast("아직 잡힌 신호가 없어요. 스캔을 먼저 시작해 주세요.")
        } else {
            toast("지금 잡힌 신호 ${count}개를 기준으로 저장했어요")
        }
        refresh()
    }

    private fun showDetails(signal: Signal) {
        val body = buildString {
            append("종류: ").append(signal.source.label).append('\n')
            signal.vendor?.let { append("제조사: ").append(it).append('\n') }
            signal.kind?.let { append("추정: ").append(it).append('\n') }
            append("신호 세기: ").append(signal.rssi).append(" dBm\n")
            Classifier.distanceLabel(signal.rssi, signal.source)?.let {
                append("대략 거리: ").append(it).append('\n')
            }
            append("주소: ").append(signal.address).append('\n')
            append("처음 잡힘: ").append(timeText(signal.firstSeen)).append('\n')
            append("마지막: ").append(timeText(signal.lastSeen)).append('\n')
            signal.baselineRssi?.let {
                append("기준 세기: ").append(it).append(" dBm")
            }
            if (signal.isNew) {
                append("\n\n평소 기준에 없던 신호예요. 처음 나타난 신호라면 ‘이 신호는 무시’를 눌러 기준에 넣을 수 있어요.")
            }
        }

        AlertDialog.Builder(this)
            .setTitle(signal.title)
            .setMessage(body)
            .setPositiveButton("이 신호는 무시") { _, _ ->
                repo.addToBaseline(signal.id, signal.rssi)
                newAlerted.remove(signal.id)
                lastSignature = null
                toast("‘${signal.title}’을(를) 기준에 넣었어요")
                refresh()
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    // ------------------------------------------------------------- permissions

    private fun requiredPermissions(): List<String> {
        val list = ArrayList<String>(4)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list.add(Manifest.permission.BLUETOOTH_SCAN)
            list.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        list.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add("android.permission.NEARBY_WIFI_DEVICES")
        }
        return list
    }

    private fun missingPermissions(): List<String> = requiredPermissions().filter {
        ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
    }

    private fun locationEnabled(): Boolean {
        val manager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return try {
            LocationManagerCompat.isLocationEnabled(manager)
        } catch (e: Exception) {
            false
        }
    }

    private fun requestBluetooth() {
        try {
            bluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } catch (e: Exception) {
            toast("블루투스를 켤 수 없어요. 폰 설정에서 직접 켜 주세요.")
        }
    }

    private fun openLocationSettings() {
        try {
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        } catch (e: Exception) {
            toast("위치 설정을 열 수 없어요.")
        }
    }

    private fun openAppSettings() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null)
                )
            )
        } catch (e: Exception) {
            toast("설정을 열 수 없어요. 폰 설정 > 앱 > 전파 감지기에서 권한을 켜 주세요.")
        }
    }

    // ------------------------------------------------------------------ utils

    private fun timeText(millis: Long): String {
        if (millis <= 0L) return "-"
        return SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date(millis))
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val REFRESH_MS = 800L
        private const val CELL_MS = 5_000L
    }
}
