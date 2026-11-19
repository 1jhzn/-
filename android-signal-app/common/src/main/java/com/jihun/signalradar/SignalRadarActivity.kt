package com.jihun.signalradar

import android.Manifest
import android.app.Dialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
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
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.jihun.signalradar.direction.DirectionFinderDialog
import com.jihun.signalradar.direction.SignalDirectionTracker
import com.jihun.signalradar.identify.Classifier
import com.jihun.signalradar.identify.DeviceName
import com.jihun.signalradar.model.Signal
import com.jihun.signalradar.model.SignalSource
import com.jihun.signalradar.scan.BleScanner
import com.jihun.signalradar.scan.CellScanner
import com.jihun.signalradar.scan.GattNameReader
import com.jihun.signalradar.scan.WifiScanner
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Everything the "distance" screen does: permissions, scanning, the signal list, the
 * detail card, the direction finder and the new-signal alerts.
 *
 * The app that only shows the distance list and the app that adds the 10 m "내 주변"
 * radar are two different packages, but they must behave exactly the same while scanning,
 * so both of them extend this class and only supply their own layout. [contentLayout]
 * therefore has to contain the same ids as `activity_main.xml`; a subclass may add more
 * views around them and react through [onScreenReady], [onScanStateChanged] and
 * [onSignalsChanged].
 */
abstract class SignalRadarActivity : AppCompatActivity() {

    protected enum class Filter { ALL, NEW, BLE, WIFI, CELL }

    /** The screen layout for this app; it must contain the distance screen's ids. */
    protected abstract val contentLayout: Int

    /**
     * How a list row is written. CLASSIC is the v1 card (maker, description, address, metres);
     * SUMMARY is the v2 card (one summary line, no address, a distance step).
     */
    protected open val itemRowStyle: SignalAdapter.RowStyle = SignalAdapter.RowStyle.CLASSIC

    /** The card layout the list inflates; a subclass may hand in its own. */
    protected open val itemRowLayout: Int = R.layout.item_signal

    /** The detail card layout; a subclass may hand in its own (v2 folds the address away). */
    protected open val detailLayoutId: Int = R.layout.dialog_detail

    /** Whether the detail body prints the raw address line itself (v2 shows it behind a toggle). */
    protected open val showAddressInDetail: Boolean = true

    protected lateinit var repo: SignalRepository
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
    private var finderDialog: DirectionFinderDialog? = null

    /**
     * The new-signal buzz is faint and at most once every ten seconds, however many devices
     * arrive at once: 지훈's complaint was "방향도 너무 안 맞고 너무 진동이 심하니까 줄여주고".
     */
    private val newBuzz = NewSignalBuzz()

    private lateinit var statusChip: TextView
    private lateinit var bellToggle: TextView
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

    /** True while the radios are being read; the scan button toggles exactly this. */
    protected val isScanning: Boolean
        get() = scanning

    /** The filter the list is showing, so a subclass can word its empty state for it. */
    protected val activeFilter: Filter
        get() = filter

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
        setContentView(contentLayout)

        repo = SignalRepository(this)
        adapter = SignalAdapter(itemRowLayout, itemRowStyle) { showDetails(it) }

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
        bellToggle = findViewById(R.id.bellToggle)
        bellToggle.setOnClickListener { toggleNewSignalBuzz() }
        renderBuzzSwitch()

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
        btnScan.setOnClickListener { toggleScan() }
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

        onScreenReady()
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
        finderDialog?.dismiss()
        ble.stop()
        wifi.stop()
    }

    // ---------------------------------------------------------------- subclass hooks

    /** Called once the distance screen's views are bound, before the first refresh. */
    protected open fun onScreenReady() {}

    /** Called whenever the scan state changes, and once at startup. */
    protected open fun onScanStateChanged(scanning: Boolean) {}

    /** Called on every refresh with the whole snapshot, so a subclass can draw from it. */
    protected open fun onSignalsChanged(all: List<Signal>, newOnes: List<Signal>) {}

    /**
     * How a distance reads: the v1 metre estimate by default, a step ("매우 가까움") in v2.
     * Used by the new-signal banner, the detail body and the list rows together, so one screen
     * never mixes "약 1.7m" with "매우 가까움".
     */
    protected open fun distanceTextFor(signal: Signal): String? = Classifier.distanceLabel(
        signal.smoothedRssi ?: signal.rssi.toFloat(),
        signal.source,
        signal.txPower,
        signal.frequencyMhz
    )

    /** How the distance under the direction dial is written; v2 keeps the step wording there too. */
    protected open fun directionDistanceText(signal: Signal, rssi: Float): String? =
        Classifier.distanceLabel(rssi, signal.source, signal.txPower, signal.frequencyMhz)

    /** The line under the detail title. */
    protected open fun detailSubtitle(signal: Signal): String = listOfNotNull(
        signal.source.label,
        signal.vendor ?: signal.kind,
        if (showAddressInDetail) signal.address else null
    ).joinToString(" · ")

    /**
     * Last chance for a subclass to wire views of its own detail layout (v2's address toggle)
     * before the card is shown.
     */
    protected open fun onDetailDialogReady(dialog: Dialog, signal: Signal) {}

    // ------------------------------------------------------------ scanning

    /** Start or stop reading radios; the top and the tab bar's buttons both land here. */
    protected fun toggleScan() {
        if (scanning) stopScanning() else startScanning()
    }

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
        onSignalsChanged(all, newOnes)
    }

    private fun describeNew(newOnes: List<Signal>): String {
        val parts = ArrayList<String>(3)
        for (signal in newOnes.take(3)) {
            val where = distanceTextFor(signal)
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
        if (fresh) vibrateNewSignal()
    }

    /**
     * One faint tap for a new signal, not a 170 ms buzz per device.
     *
     * The banner and the 🚨 counter still report every new signal; only the motor is held
     * back, once per [NewSignalBuzz.MIN_GAP_MS]. Turned off entirely with the 🔕 switch.
     */
    private fun vibrateNewSignal() {
        if (!repo.vibrateOnNewSignal()) return
        val now = System.currentTimeMillis()
        if (!newBuzz.allow(now)) return
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        try {
            vibrator.vibrate(
                VibrationEffect.createOneShot(
                    NewSignalBuzz.DURATION_MS,
                    NewSignalBuzz.AMPLITUDE
                )
            )
        } catch (e: Exception) {
            // vibration is a nicety; never let it break the scan
        }
    }

    private fun toggleNewSignalBuzz() {
        val enabled = !repo.vibrateOnNewSignal()
        repo.setVibrateOnNewSignal(enabled)
        renderBuzzSwitch()
        toast(if (enabled) "새 신호 진동을 켰어요 · 아주 약하게 한 번만 울려요" else "새 신호 진동을 껐어요")
    }

    private fun renderBuzzSwitch() {
        val on = repo.vibrateOnNewSignal()
        bellToggle.text = if (on) "🔔" else "🔕"
        bellToggle.alpha = if (on) 1f else 0.5f
        bellToggle.contentDescription =
            if (on) "새 신호 진동 켜짐 · 눌러서 끄기" else "새 신호 진동 꺼짐 · 눌러서 켜기"
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

    /** What the list says when nothing matches; a subclass may word it differently. */
    protected open fun emptyMessage(): String = when (filter) {
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
        onScanStateChanged(scanning)
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
        // The user asked for a fresh start, so the next new signal may tap again at once.
        newBuzz.reset()
        lastSignature = null
        if (count == 0) {
            toast("아직 잡힌 신호가 없어요. 스캔을 먼저 시작해 주세요.")
        } else {
            toast("지금 잡힌 신호 ${count}개를 기준으로 저장했어요")
        }
        refresh()
    }

    protected fun showDetails(signal: Signal) {
        val dialog = Dialog(this, R.style.Theme_SignalRadar_Dialog)
        dialog.setContentView(detailLayoutId)

        val title = dialog.findViewById<TextView>(R.id.detailTitle)
        val subtitle = dialog.findViewById<TextView>(R.id.detailSubtitle)
        val body = dialog.findViewById<TextView>(R.id.detailBody)
        val badges = dialog.findViewById<TextView>(R.id.detailBadges)
        val gattResult = dialog.findViewById<TextView>(R.id.detailGattResult)
        val gattButton = dialog.findViewById<TextView>(R.id.detailGattButton)
        val direction = dialog.findViewById<TextView>(R.id.detailDirection)
        val ignore = dialog.findViewById<TextView>(R.id.detailIgnore)
        val close = dialog.findViewById<TextView>(R.id.detailClose)

        var current = signal
        fun render(view: Signal) {
            title.text = view.title
            subtitle.text = detailSubtitle(view)
            body.text = detailText(view)
            val mine = if (view.source == SignalSource.BLE) {
                DeviceName.badgeText(view.isConnected, view.isBonded)
            } else {
                null
            }
            if (mine != null) {
                badges.text = mine
                badges.visibility = View.VISIBLE
            } else {
                badges.visibility = View.GONE
            }
        }
        render(current)

        var reader: GattNameReader? = null
        var reading = false
        val canReadName = signal.source == SignalSource.BLE && MAC_PATTERN.matches(signal.address.uppercase())
        if (signal.source == SignalSource.BLE) {
            gattButton.visibility = View.VISIBLE
            if (!canReadName) {
                gattButton.text = READ_LABEL
            }
        } else {
            gattButton.visibility = View.GONE
        }

        gattButton.setOnClickListener {
            // While a read is running the same button is the stop button: 지훈 asked to be
            // able to end it himself instead of waiting out the timeouts ("이름 읽는 시간을
            // 내가 멈출수있게 해줘"). cancel() closes the connection on its own thread and
            // reports nothing, so the screen is reset here.
            if (reading) {
                reader?.cancel()
                reader = null
                reading = false
                gattButton.isEnabled = true
                gattButton.text = READ_LABEL
                gattResult.visibility = View.VISIBLE
                gattResult.text = STOPPED_MESSAGE
                return@setOnClickListener
            }
            val device = if (canReadName) bluetoothDevice(current.address) else null
            if (device == null) {
                gattResult.visibility = View.VISIBLE
                gattResult.text = if (signal.source != SignalSource.BLE) {
                    "블루투스 기기에서만 이름을 더 읽어올 수 있어요."
                } else {
                    "이 기기는 주소가 숨겨져 있어 폰에서 직접 연결할 수 없어요."
                }
                return@setOnClickListener
            }
            reading = true
            // Stays clickable on purpose: the label now says how to stop.
            gattButton.isEnabled = true
            gattButton.text = STOP_READ_LABEL
            gattResult.visibility = View.VISIBLE
            gattResult.text = "GATT 연결 중…"
            reader?.cancel()
            reader = GattNameReader(
                this,
                // A status that arrives after the user stopped must not overwrite the
                // "읽기를 멈췄어요" line.
                onStatus = { status -> if (reading) gattResult.text = status },
                onResult = { result ->
                    reading = false
                    gattButton.isEnabled = true
                    when (result) {
                        is GattNameReader.Result.Found -> {
                            repo.updateGattNames(signal.id, result.deviceName, result.manufacturer)
                            current = repo.find(signal.id) ?: current
                            render(current)
                            gattResult.text = buildString {
                                append("기기가 직접 알려 준 정보\n")
                                append("· 기기 이름(0x2A00): ")
                                append(result.deviceName ?: "공개 안 함")
                                if (result.manufacturer != null) {
                                    append('\n').append("· 제조사(0x2A29): ")
                                        .append(result.manufacturer)
                                }
                            }
                            gattButton.text = READ_AGAIN_LABEL
                        }
                        is GattNameReader.Result.Failed -> {
                            gattResult.text = result.message
                            gattButton.text = READ_RETRY_LABEL
                        }
                    }
                }
            ).also { it.read(device) }
        }

        if (signal.source != SignalSource.CELL) {
            direction.visibility = View.VISIBLE
            direction.setOnClickListener {
                dialog.dismiss()
                openDirectionFinder(current)
            }
        }

        ignore.setOnClickListener {
            val live = repo.find(signal.id) ?: current
            repo.addToBaseline(signal.id, live.rssi)
            newAlerted.remove(signal.id)
            lastSignature = null
            toast("‘${signal.title}’을(를) 기준에 넣었어요")
            refresh()
            dialog.dismiss()
        }

        close.setOnClickListener { dialog.dismiss() }
        dialog.findViewById<TextView>(R.id.detailCloseTop).setOnClickListener { dialog.dismiss() }
        onDetailDialogReady(dialog, signal)

        dialog.setOnDismissListener { reader?.cancel() }
        dialog.setOnShowListener { shown ->
            val window = (shown as? Dialog)?.window ?: return@setOnShowListener
            val metrics = resources.displayMetrics
            val margin = (14 * metrics.density).toInt()
            val width = (metrics.widthPixels - margin * 2)
                .coerceAtMost((metrics.widthPixels * 0.96f).toInt())
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            window.decorView.post {
                // A long detail list must scroll inside the card instead of pushing the
                // buttons off the bottom of the screen.
                val scroll = dialog.findViewById<View>(R.id.detailScroll)
                val limit = (metrics.heightPixels * 0.40f).toInt()
                if (scroll.height > limit) {
                    scroll.layoutParams = scroll.layoutParams.apply { height = limit }
                    scroll.requestLayout()
                }
            }
        }
        dialog.show()
    }

    /** Everything the detail card says, in the order a person reads it. */
    private fun detailText(signal: Signal): String {
        val text = StringBuilder()
        text.append("종류: ").append(signal.source.label).append('\n')

        if (signal.source == SignalSource.BLE) {
            val ownName = signal.broadcastName
            val cachedName = signal.systemName
            when {
                ownName != null -> {
                    text.append("📢 방송명: ").append(ownName).append('\n')
                    DeviceName.ownerHint(ownName)?.let { text.append("   ").append(it).append('\n') }
                }
                cachedName != null -> {
                    text.append("📌 폰에 저장된 이름: ").append(cachedName)
                        .append(" (기기는 이름을 방송하지 않아요)\n")
                }
                else -> text.append("이름을 방송하지 않아요 — 폰에 저장된 이름도 없어요.\n")
            }
            if (signal.gattName != null && signal.gattName != ownName && signal.gattName != cachedName) {
                text.append("기기에서 읽은 이름: ").append(signal.gattName).append('\n')
            }
            if (signal.gattManufacturer != null) {
                text.append("기기에서 읽은 제조사: ").append(signal.gattManufacturer).append('\n')
            }
        }

        signal.vendor?.let { text.append("제조사: ").append(it).append('\n') }
        signal.kind?.let { text.append("추정: ").append(it).append('\n') }
        val level = signal.smoothedRssi?.roundToInt() ?: signal.rssi
        text.append("신호 세기: ").append(level).append(" dBm")
        if (level != signal.rssi) text.append(" (순간 ").append(signal.rssi).append(" dBm)")
        text.append('\n')
        signal.txPower?.let {
            text.append(
                if (Classifier.isOneMetreTxPower(it)) {
                    "송신 세기(1m 기준): $it dBm\n"
                } else {
                    // Apple and some chips advertise the antenna's output here (+12 dBm),
                    // not the level at 1 m, so saying "1m 기준" would just be wrong.
                    "광고된 송신 출력: " + (if (it > 0) "+$it" else "$it") +
                        " dBm (1m 기준 세기가 아니라 거리 계산에는 쓰지 않아요)\n"
                }
            )
        }
        distanceTextFor(signal)?.let {
            text.append("대략 거리: ").append(it)
                .append(" (신호 세기로 계산한 어림값 · 벽이나 사람에 따라 달라져요)\n")
        }
        if (showAddressInDetail) text.append("주소: ").append(signal.address).append('\n')
        text.append("처음 잡힘: ").append(timeText(signal.firstSeen)).append('\n')
        text.append("마지막: ").append(timeText(signal.lastSeen))
        signal.baselineRssi?.let {
            text.append('\n').append("기준 세기: ").append(it).append(" dBm")
        }
        val bearing = repo.bearing(signal.id)
        if (bearing != null) {
            text.append('\n').append("측정된 방향: ")
                .append(SignalDirectionTracker.bearingText(bearing))
        }
        if (signal.isNew) {
            text.append("\n\n평소 기준에 없던 신호예요. 처음 나타난 신호라면 ‘이 신호는 무시’를 눌러 기준에 넣을 수 있어요.")
        }
        return text.toString()
    }

    /** The BluetoothDevice behind an address, or null when the address is hidden. */
    private fun bluetoothDevice(address: String): BluetoothDevice? {
        val upper = address.uppercase()
        if (!MAC_PATTERN.matches(upper)) return null
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return null
        return try {
            manager.adapter?.getRemoteDevice(upper)
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: SecurityException) {
            null
        } catch (e: Exception) {
            null
        }
    }

    protected fun openDirectionFinder(signal: Signal) {
        if (!scanning) {
            startScanning()
            if (!scanning) {
                toast("방향을 재려면 주변 신호를 먼저 읽어야 해요. 권한을 허용해 주세요.")
                return
            }
        }
        val dialog = DirectionFinderDialog(
            this,
            signal,
            { repo.find(signal.id) },
            { wifi.requestImmediateScan() },
            // A measured bearing is what puts a device on the 10 m radar with a real
            // direction instead of a placeholder angle, so it is kept per signal id.
            { azimuth -> repo.setBearing(signal.id, azimuth) },
            { s, rssi -> directionDistanceText(s, rssi) }
        )
        dialog.setOnDismissListener {
            if (finderDialog === dialog) finderDialog = null
            refresh()
        }
        finderDialog?.dismiss()
        finderDialog = dialog
        dialog.show()
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

    protected fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val REFRESH_MS = 800L
        private const val CELL_MS = 5_000L

        /** The name-read button's labels, so the stop state is one place to change. */
        internal const val READ_LABEL = "🔍  기기 이름 더 읽어오기"
        internal const val STOP_READ_LABEL = "⏹  읽기 멈추기"
        internal const val READ_AGAIN_LABEL = "🔍  다시 읽어오기"
        internal const val READ_RETRY_LABEL = "🔍  다시 시도"

        /** Said when the user stops a name read with the ⏹ button. */
        internal const val STOPPED_MESSAGE = "읽기를 멈췄어요"

        /** AA:BB:CC:DD:EE:FF — a real address, not "주소 비공개". */
        private val MAC_PATTERN = Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$")
    }
}
