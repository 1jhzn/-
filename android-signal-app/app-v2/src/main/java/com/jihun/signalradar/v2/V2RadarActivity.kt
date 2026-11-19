package com.jihun.signalradar.v2

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.jihun.signalradar.R
import com.jihun.signalradar.SignalRadarActivity
import com.jihun.signalradar.direction.Compass
import com.jihun.signalradar.direction.SignalDirectionTracker
import com.jihun.signalradar.identify.Classifier
import com.jihun.signalradar.model.Signal
import com.jihun.signalradar.model.SignalSource
import com.jihun.signalradar.nearby.NearbyRadarMath
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 전파 감지기 v2.1 — the "나의 찾기" (Find My) style screens, still one separately installable
 * app (`com.jihun.signalradar.v2`) that leaves 전파 감지기 and 내 주변 untouched.
 *
 * What this class adds over the shared screen: the pastel top area with the nearest-signal
 * callout or the bright 10 m radar, the white rounded sheet with its grab handle, the big
 * title and the round scan button, the pill tab bar (신호 · 내 주변 · 기준), and the 신호 찾기
 * (orb) screen that starts dark and turns emerald when the signal is in reach.
 *
 * Everything that reads radios, keeps the list, spots new signals, folds the MAC address,
 * reads the device name over GATT (0.7 s + ⏹ 읽기 멈추기) and measures the direction is
 * inherited, so those behaviours are exactly the ones 지훈 already has.
 */
class V2RadarActivity : SignalRadarActivity() {

    private enum class Tab { SIGNALS, NEARBY, RECORD }

    override val contentLayout: Int = R.layout.activity_v2
    override val itemRowLayout: Int = R.layout.item_signal_v2
    override val detailLayoutId: Int = R.layout.dialog_detail_v2

    /** v2 folds the address behind a toggle, and the body reads steps, not metres. */
    override val showAddressInDetail: Boolean = false

    private val handler = Handler(Looper.getMainLooper())

    private var tab = Tab.SIGNALS
    private var radarFullscreen = false
    private var orbOpen = false
    private var orbSignalId: String? = null

    private val orb = OrbFinderState()
    private var compass: Compass? = null
    private var smoothHeading: Float? = null
    private var compassRunning = false

    private var lastOrbSeen = 0L
    private var lastOrbRssi = Int.MIN_VALUE
    private var lastOrbBuzz = 0L

    private var nearest: Signal? = null
    private var listKey: String? = null
    private var nearbyKey: String? = null

    private val v2Adapter = V2SignalAdapter { openOrbFinder(it) }
    private val nearbyAdapter = V2SignalAdapter { openOrbFinder(it) }

    // ---------------------------------------------------------------- views

    private lateinit var hero: View
    private lateinit var sheet: View
    private lateinit var sheetTitle: TextView

    private lateinit var callout: View
    private lateinit var calloutIconBox: View
    private lateinit var calloutIcon: ImageView
    private lateinit var calloutTitle: TextView
    private lateinit var calloutSub: TextView
    private lateinit var calloutStep: TextView

    private lateinit var radarBox: View
    private lateinit var radar: V2RadarDialView
    private lateinit var radarFullscreenToggle: TextView
    private lateinit var radarHeading: TextView
    private lateinit var radarLegend: View

    private lateinit var recordHero: View
    private lateinit var recordCount: TextView
    private lateinit var recordSavedAt: TextView
    private lateinit var recordSummary: TextView
    private lateinit var vibrateRow: View
    private lateinit var vibrateState: TextView

    private lateinit var signalPage: View
    private lateinit var nearbyPage: View
    private lateinit var recordPage: View
    private lateinit var nearbyCount: TextView
    private lateinit var nearbyEmpty: View

    private lateinit var tabSignal: TextView
    private lateinit var tabNearby: TextView
    private lateinit var tabRecord: TextView

    private lateinit var orbPage: View
    private lateinit var orbWash: View
    private lateinit var orbTitle: TextView
    private lateinit var orbSubtitle: TextView
    private lateinit var orbStep: TextView
    private lateinit var orbGuide: TextView
    private lateinit var orbProgress: TextView
    private lateinit var orbView: V2OrbView
    private lateinit var orbVibrate: TextView

    /** Back closes the orb first, then leaves radar fullscreen, then leaves the app. */
    private val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            when {
                orbOpen -> closeOrb()
                radarFullscreen -> setRadarFullscreen(false)
            }
        }
    }

    private val compassTick = object : Runnable {
        override fun run() {
            readHeading()
            if (tab == Tab.NEARBY) renderRadarHeading()
            handler.postDelayed(this, HEADING_MS)
        }
    }

    private val orbTick = object : Runnable {
        override fun run() {
            if (!orbOpen) return
            renderOrb()
            handler.postDelayed(this, ORB_MS)
        }
    }

    // ------------------------------------------------------------- set-up

    override fun onScreenReady() {
        hero = findViewById(R.id.v2Hero)
        sheet = findViewById(R.id.v2Sheet)
        sheetTitle = findViewById(R.id.v2SheetTitle)

        callout = findViewById(R.id.v2Callout)
        calloutIconBox = findViewById(R.id.v2CalloutIconBox)
        calloutIcon = findViewById(R.id.v2CalloutIcon)
        calloutTitle = findViewById(R.id.v2CalloutTitle)
        calloutSub = findViewById(R.id.v2CalloutSub)
        calloutStep = findViewById(R.id.v2CalloutStep)

        radarBox = findViewById(R.id.v2RadarBox)
        radar = findViewById(R.id.nearbyRadar)
        radarFullscreenToggle = findViewById(R.id.nearbyFullscreen)
        radarHeading = findViewById(R.id.nearbyHeading)
        radarLegend = findViewById(R.id.nearbyLegend)

        recordHero = findViewById(R.id.v2RecordHero)
        recordCount = findViewById(R.id.v2RecordCount)
        recordSavedAt = findViewById(R.id.v2RecordSavedAt)
        recordSummary = findViewById(R.id.recordSummary)
        vibrateRow = findViewById(R.id.v2VibrateRow)
        vibrateState = findViewById(R.id.v2VibrateState)

        signalPage = findViewById(R.id.signalPage)
        nearbyPage = findViewById(R.id.nearbyPage)
        recordPage = findViewById(R.id.recordPage)
        nearbyCount = findViewById(R.id.nearbyCount)
        nearbyEmpty = findViewById(R.id.nearbyEmpty)

        tabSignal = findViewById(R.id.tabSignal)
        tabNearby = findViewById(R.id.tabNearby)
        tabRecord = findViewById(R.id.tabRecord)

        orbPage = findViewById(R.id.orbPage)
        orbWash = findViewById(R.id.orbWash)
        orbTitle = findViewById(R.id.orbTitle)
        orbSubtitle = findViewById(R.id.orbSubtitle)
        orbStep = findViewById(R.id.orbStep)
        orbGuide = findViewById(R.id.orbGuide)
        orbProgress = findViewById(R.id.orbProgress)
        orbView = findViewById(R.id.orbView)
        orbVibrate = findViewById(R.id.orbVibrate)

        findViewById<RecyclerView>(R.id.v2SignalList).apply {
            layoutManager = LinearLayoutManager(this@V2RadarActivity)
            adapter = v2Adapter
        }
        findViewById<RecyclerView>(R.id.nearbyList).apply {
            layoutManager = LinearLayoutManager(this@V2RadarActivity)
            adapter = nearbyAdapter
        }

        callout.setOnClickListener {
            val signal = nearest
            if (signal == null) {
                toast("먼저 ‘스캔 시작’을 눌러 주세요")
            } else {
                openOrbFinder(signal)
            }
        }
        radar.onBlipClick = { openOrbFinder(it) }
        radarFullscreenToggle.setOnClickListener { setRadarFullscreen(!radarFullscreen) }

        tabSignal.setOnClickListener { selectTab(Tab.SIGNALS) }
        tabNearby.setOnClickListener { selectTab(Tab.NEARBY) }
        tabRecord.setOnClickListener { selectTab(Tab.RECORD) }

        vibrateRow.setOnClickListener { toggleVibrate() }

        findViewById<View>(R.id.orbClose).setOnClickListener { closeOrb() }
        orbVibrate.setOnClickListener { toggleVibrate() }
        findViewById<View>(R.id.orbDetail).setOnClickListener {
            val live = orbSignalId?.let { repo.find(it) }
            if (live != null) showDetails(live)
        }
        findViewById<View>(R.id.orbReset).setOnClickListener {
            orb.reset()
            lastOrbSeen = 0L
            lastOrbRssi = Int.MIN_VALUE
            renderOrb()
        }

        onBackPressedDispatcher.addCallback(this, back)
        selectTab(Tab.SIGNALS)
    }

    override fun onStart() {
        super.onStart()
        if (orbOpen || tab == Tab.NEARBY) startCompass()
        if (orbOpen) restart(orbTick, ORB_MS)
    }

    override fun onStop() {
        handler.removeCallbacks(orbTick)
        stopCompass()
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacks(compassTick)
        handler.removeCallbacks(orbTick)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- tabs

    private fun selectTab(next: Tab) {
        if (radarFullscreen && next != Tab.NEARBY) setRadarFullscreen(false)
        tab = next
        signalPage.visibility = if (next == Tab.SIGNALS) View.VISIBLE else View.GONE
        nearbyPage.visibility = if (next == Tab.NEARBY) View.VISIBLE else View.GONE
        recordPage.visibility = if (next == Tab.RECORD) View.VISIBLE else View.GONE

        callout.visibility = if (next == Tab.SIGNALS) View.VISIBLE else View.GONE
        radarBox.visibility = if (next == Tab.NEARBY) View.VISIBLE else View.GONE
        recordHero.visibility = if (next == Tab.RECORD) View.VISIBLE else View.GONE
        radarLegend.visibility = if (next == Tab.NEARBY) View.VISIBLE else View.GONE

        sheetTitle.text = when (next) {
            Tab.SIGNALS -> "주변 신호"
            Tab.NEARBY -> "내 주변 레이더"
            Tab.RECORD -> "기준 기록"
        }
        renderTabs()
        if (next == Tab.NEARBY) startCompass() else if (!orbOpen) stopCompass()
        if (next == Tab.NEARBY) {
            radar.invalidate()
            renderRadarHeading()
        }
    }

    private fun renderTabs() {
        for ((view, selected) in listOf(
            tabSignal to (tab == Tab.SIGNALS),
            tabNearby to (tab == Tab.NEARBY),
            tabRecord to (tab == Tab.RECORD)
        )) {
            view.background = if (selected) {
                ContextCompat.getDrawable(this, R.drawable.bg_v2_tab_on)
            } else {
                null
            }
            view.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (selected) R.color.v2_ink else R.color.v2_ink_faint
                )
            )
            view.setTypeface(view.typeface, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
    }

    // ------------------------------------------------------------- scanning

    override fun onScanStateChanged(scanning: Boolean) {
        val button = findViewById<TextView>(R.id.btnScan)
        button.text = if (scanning) "■ 스캔 중지" else "▶ 스캔 시작"
        button.setBackgroundResource(
            if (scanning) R.drawable.bg_v2_scan_stop else R.drawable.bg_v2_scan_start
        )
        button.setTextColor(
            ContextCompat.getColor(
                this,
                if (scanning) R.color.v2_blue else R.color.v2_sheet
            )
        )
        radar.scanning = scanning
    }

    override fun onSignalsChanged(all: List<Signal>, newOnes: List<Signal>) {
        val visible = visible(all)
        val key = signature(visible)
        if (key != listKey) {
            listKey = key
            v2Adapter.submit(visible)
        }
        nearest = nearestOf(visible)
        renderCallout(nearest)

        val blips = NearbyRadarMath.nearbyBlips(all) { id -> repo.bearing(id) }
        val nearKey = blips.joinToString("|") { "${it.signal.id}:${it.signal.rssi}:${it.metres}:${it.bearing}" }
        if (nearKey != nearbyKey) {
            nearbyKey = nearKey
            radar.blips = blips
            nearbyAdapter.submit(blips.map { it.signal })
        }
        nearbyCount.text = "10m 안에 잡힌 기기 ${blips.size}개"
        nearbyEmpty.visibility = if (blips.isEmpty()) View.VISIBLE else View.GONE
        radar.scanning = isScanning

        countChips(all)
        styleChips()
        styleStatus(newOnes.size)
        renderVibrate(orbOpen && orb.emeraldProgress(System.currentTimeMillis()) > 0.5f)
        renderRecord()
    }

    /**
     * The per-radio counts live in the filter chips (`블루투스 3`), so the sheet keeps its
     * room for the list. The 🚨 chip is worded by the shared screen.
     */
    private fun countChips(all: List<Signal>) {
        findViewById<TextView>(R.id.chipBle)?.text =
            "블루투스 " + all.count { it.source == SignalSource.BLE }
        findViewById<TextView>(R.id.chipWifi)?.text =
            "와이파이 " + all.count { it.source == SignalSource.WIFI }
        findViewById<TextView>(R.id.chipCell)?.text =
            "기지국 " + all.count { it.source == SignalSource.CELL }
    }

    private fun styleChips() {
        val selected = activeFilter
        val map = listOf(
            Filter.ALL to R.id.chipAll,
            Filter.NEW to R.id.chipNew,
            Filter.BLE to R.id.chipBle,
            Filter.WIFI to R.id.chipWifi,
            Filter.CELL to R.id.chipCell
        )
        for ((filter, id) in map) {
            val chip = findViewById<TextView>(id) ?: continue
            val on = filter == selected
            chip.setBackgroundResource(
                if (on) R.drawable.bg_v2_chip_on else R.drawable.bg_v2_chip
            )
            chip.setTextColor(
                ContextCompat.getColor(this, if (on) R.color.v2_sheet else R.color.v2_ink_soft)
            )
        }
        findViewById<TextView>(R.id.statNew)?.let {
            it.setTextColor(
                ContextCompat.getColor(this, R.color.v2_ink)
            )
        }
    }

    private fun styleStatus(newCount: Int) {
        val chip = findViewById<TextView>(R.id.statusChip)
        chip.setTextColor(
            ContextCompat.getColor(
                this,
                if (isScanning) R.color.v2_emerald_deep else R.color.v2_ink_soft
            )
        )
        if (newCount > 0) {
            chip.setTextColor(ContextCompat.getColor(this, R.color.v2_red))
        }
        // The 🔔/🔕 switch is drawn by the shared screen; its emoji is kept in step here so
        // the orb's 진동 button and the header never disagree.
        val bell = findViewById<TextView>(R.id.bellToggle)
        val on = repo.vibrateOnNewSignal()
        bell.text = if (on) "🔔" else "🔕"
        bell.alpha = if (on) 1f else 0.55f
    }

    private fun renderCallout(signal: Signal?) {
        if (signal == null) {
            calloutIconBox.setBackgroundResource(R.drawable.bg_v2_icon_ble)
            calloutIcon.setImageResource(R.drawable.ic_radar)
            calloutTitle.text = "아직 잡힌 신호가 없어요"
            calloutSub.text = if (isScanning) "조금 기다리면 가장 가까운 신호가 여기에 보여요" else "‘스캔 시작’을 누르면 가장 가까운 신호를 찾아요"
            calloutStep.text = "찾기 ›"
            calloutStep.setBackgroundResource(R.drawable.bg_v2_step_far)
            calloutStep.setTextColor(ContextCompat.getColor(this, R.color.v2_ink_faint))
            return
        }
        val step = V2Look.stepOf(signal)
        val style = V2Look.stepStyle(step)
        calloutIconBox.setBackgroundResource(V2Look.iconBadgeFor(signal.source))
        calloutIcon.setImageResource(V2Look.iconFor(signal.source))
        calloutTitle.text = signal.title
        calloutSub.text = V2Look.summaryLine(signal)
        calloutStep.text = step ?: "찾기 ›"
        calloutStep.setBackgroundResource(style.background)
        calloutStep.setTextColor(ContextCompat.getColor(this, style.textColor))
    }

    private fun renderRecord() {
        val saved = repo.baselineSize()
        val at = repo.baselineSavedAt()
        recordCount.text = "기준 $saved 개"
        recordSavedAt.text = if (at > 0L) {
            "${clock(at)} 저장 · 그 뒤 처음 나타난 신호를 알려드려요"
        } else {
            "아직 기준을 저장하지 않았어요"
        }
        recordSummary.text = if (at > 0L) {
            "저장된 기준: $saved 개 · ${clock(at)}"
        } else {
            "저장된 기준: 0개"
        }
    }

    /** [emerald] keeps the 진동 button readable once the finder screen turns green. */
    private fun renderVibrate(emerald: Boolean = false) {
        val on = repo.vibrateOnNewSignal()
        vibrateState.text = if (on) "🔔 켜짐" else "🔕 꺼짐"
        vibrateState.setBackgroundResource(
            if (on) R.drawable.bg_v2_step_very else R.drawable.bg_v2_step_far
        )
        vibrateState.setTextColor(
            ContextCompat.getColor(
                this,
                if (on) R.color.v2_emerald_deep else R.color.v2_ink_soft
            )
        )
        orbVibrate.text = if (on) "🔔 진동 켜짐" else "🔕 진동 꺼짐"
        when {
            !on -> {
                orbVibrate.setBackgroundResource(R.drawable.bg_v2_orb_button)
                orbVibrate.setTextColor(Color.parseColor("#F2F6F4"))
            }
            emerald -> {
                // On the emerald screen a green button would disappear into the background.
                orbVibrate.setBackgroundResource(R.drawable.bg_v2_callout)
                orbVibrate.setTextColor(ContextCompat.getColor(this, R.color.v2_emerald_deep))
            }
            else -> {
                orbVibrate.setBackgroundResource(R.drawable.bg_v2_orb_button_on)
                orbVibrate.setTextColor(Color.parseColor("#0A2B18"))
            }
        }
    }

    private fun toggleVibrate() {
        val enabled = !repo.vibrateOnNewSignal()
        repo.setVibrateOnNewSignal(enabled)
        renderVibrate()
        val bell = findViewById<TextView>(R.id.bellToggle)
        bell.text = if (enabled) "🔔" else "🔕"
        bell.alpha = if (enabled) 1f else 0.55f
        toast(if (enabled) "진동을 켰어요 · 가까워지면 아주 약하게 울려요" else "진동을 껐어요")
    }

    /** What the list shows for the chosen filter, in the same order the v1 screens use. */
    private fun visible(all: List<Signal>): List<Signal> {
        val filtered = when (activeFilter) {
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

    private fun nearestOf(list: List<Signal>): Signal? = list
        .filter { it.source != SignalSource.CELL }
        .maxByOrNull { it.smoothedRssi ?: it.rssi.toFloat() }

    private fun signature(list: List<Signal>): String = buildString {
        for (signal in list) {
            append(signal.id).append(':').append(signal.rssi)
                .append(signal.isNew).append(signal.isBoosted).append('|')
        }
    }

    /** Words the empty list for the v2 wording, and says what the scan is doing. */
    override fun emptyMessage(): String = when {
        activeFilter != Filter.ALL -> super.emptyMessage()
        isScanning -> "스캔 중이에요 · 조금 기다리면 주변 신호가 여기에 나타나요"
        else -> "아직 잡힌 신호가 없어요.\n‘▶ 스캔 시작’을 눌러 주세요."
    }

    /** One step word everywhere in v2: the list, the callout, the detail card, the orb. */
    override fun distanceTextFor(signal: Signal): String? = V2Look.stepOf(signal)

    override fun directionDistanceText(signal: Signal, rssi: Float): String? =
        Classifier.distanceStepLabel(rssi, signal.source, signal.txPower, signal.frequencyMhz)

    override fun detailSubtitle(signal: Signal): String = V2Look.summaryLine(signal)

    // ---------------------------------------------------------------- radar

    private fun setRadarFullscreen(on: Boolean) {
        if (on && tab != Tab.NEARBY) selectTab(Tab.NEARBY)
        radarFullscreen = on
        sheet.visibility = if (on) View.GONE else View.VISIBLE
        radarFullscreenToggle.text = if (on) "✕ 축소" else "⛶ 전체화면"
        radarLegend.visibility = if (on) View.GONE else View.VISIBLE
        back.isEnabled = on || orbOpen
        radar.invalidate()
    }

    private fun startCompass() {
        if (compassRunning) return
        compassRunning = true
        val sensor = compass ?: Compass(this).also { compass = it }
        if (sensor.isSupported) sensor.start()
        handler.removeCallbacks(compassTick)
        handler.post(compassTick)
    }

    private fun stopCompass() {
        compassRunning = false
        handler.removeCallbacks(compassTick)
        compass?.stop()
        smoothHeading = null
    }

    /** The compass bearing the phone points at, lightly filtered; null without a sensor. */
    private fun readHeading(): Float? {
        val sensor = compass ?: return null
        val raw = sensor.heading ?: return smoothHeading
        val previous = smoothHeading
        smoothHeading = if (previous == null) {
            raw
        } else {
            SignalDirectionTracker.normalize(
                previous + SignalDirectionTracker.signedDifference(previous, raw) * HEADING_SMOOTHING
            )
        }
        return smoothHeading
    }

    private fun renderRadarHeading() {
        val supported = compass?.isSupported ?: false
        val heading = smoothHeading
        radar.compassAvailable = supported
        heading?.let { radar.heading = it }
        radarHeading.text = when {
            !supported -> "이 폰에는 방향 센서가 없어요 · 기기를 눌러 찾기 화면을 열 수 있어요"
            heading == null -> "폰이 보는 방향: 읽는 중…"
            else -> "폰이 보는 방향: ${SignalDirectionTracker.bearingText(heading)}"
        }
    }

    // ----------------------------------------------------------------- orb

    internal fun openOrbFinder(signal: Signal) {
        if (!isScanning) toggleScan()
        orbSignalId = signal.id
        orbTitle.text = signal.title
        orbSubtitle.text = V2Look.summaryLine(signal)
        orb.reset()
        lastOrbSeen = 0L
        lastOrbRssi = Int.MIN_VALUE
        orbPage.visibility = View.VISIBLE
        orbOpen = true
        back.isEnabled = true
        startCompass()
        renderVibrate()
        renderOrb()
        restart(orbTick, ORB_MS)
    }

    internal fun closeOrb() {
        orbOpen = false
        orbPage.visibility = View.GONE
        orbWash.alpha = 0f
        handler.removeCallbacks(orbTick)
        back.isEnabled = radarFullscreen
        if (tab != Tab.NEARBY) stopCompass()
    }

    private fun renderOrb() {
        val id = orbSignalId ?: return
        val live = repo.find(id) ?: return
        val now = System.currentTimeMillis()
        val heading = readHeading()

        val stepOf: (Int) -> String? = { rssi ->
            Classifier.distanceStepLabel(rssi, live.source, live.txPower, live.frequencyMhz)
        }
        if (live.lastSeen != lastOrbSeen || live.rssi != lastOrbRssi) {
            lastOrbSeen = live.lastSeen
            lastOrbRssi = live.rssi
            orb.onSample(live.rssi, heading, now, stepOf)
        }

        val step = orb.step ?: stepOf(live.rssi)
        val progress = orb.emeraldProgress(now)

        orbTitle.text = live.title
        orbSubtitle.text = V2Look.summaryLine(live)
        orbWash.alpha = progress * 0.94f
        orbStep.text = step ?: "–"
        orbGuide.text = guidance(heading)
        orbProgress.text = orb.levelText()

        renderVibrate(progress > 0.5f)
        orbView.scale = orb.orbScale()
        orbView.emerald = progress
        orbView.pulse = ((now % PULSE_MS).toFloat() / PULSE_MS)
        orbView.heading = heading ?: 0f
        orbView.peakAzimuth = orb.peakAzimuth
        orbView.compassAvailable = compass?.isSupported ?: false
        orbView.aligned = orb.aligned(heading)
        orbView.sectorValues = FloatArray(SignalDirectionTracker.SECTORS) { index ->
            orb.sectorValue(index) ?: Float.NaN
        }

        // A signal in reach taps the phone every couple of seconds, like a metal detector:
        // this is how "매우 가까움" is felt without looking at the screen.
        if (step == V2Look.VERY_NEAR && repo.vibrateOnNewSignal() && now - lastOrbBuzz >= ORB_BUZZ_GAP_MS) {
            lastOrbBuzz = now
            buzz(ORB_BUZZ_MS, ORB_BUZZ_AMPLITUDE)
        }
    }

    /**
     * Preview only: paint the orb for a given state without waiting for packets, so the
     * screenshots (dark orb, emerald arrival) can be checked without walking around a room.
     * The live path is [openOrbFinder] + [renderOrb], which read the scan and the compass.
     */
    internal fun previewOrb(
        signal: Signal,
        step: String,
        emerald: Float,
        scale: Float,
        heading: Float,
        peak: Float?
    ) {
        handler.removeCallbacks(orbTick)
        orbSignalId = signal.id
        orbTitle.text = signal.title
        orbSubtitle.text = V2Look.summaryLine(signal)
        orbPage.visibility = View.VISIBLE
        orbOpen = true
        back.isEnabled = true
        orbWash.alpha = emerald * 0.94f
        renderVibrate(emerald > 0.5f)
        orbStep.text = step
        orbGuide.text = if (peak != null) {
            "${SignalDirectionTracker.bearingText(peak)} 방향이 가장 세요 · 그쪽으로 돌아 보세요"
        } else {
            "폰을 평평하게 들고 천천히 한 바퀴 돌려 보세요"
        }
        orbProgress.text = "측정 진행: 7/12 방향 · 최고 -45 · 현재 -48 dBm"
        orbView.scale = scale
        orbView.emerald = emerald
        orbView.pulse = 0.35f
        orbView.heading = heading
        orbView.peakAzimuth = peak
        orbView.compassAvailable = true
        orbView.aligned = false
        orbView.sectorValues = FloatArray(SignalDirectionTracker.SECTORS) { index ->
            when {
                index % 4 == 0 -> Float.NaN
                index == 6 -> -42f
                else -> -52f - (index % 3) * 5f
            }
        }
    }

    private fun guidance(heading: Float?): String {
        val supported = compass?.isSupported ?: false
        val peak = orb.peakAzimuth
        return when {
            !supported -> "이 폰에는 방향 센서가 없어요 · 원이 커지는 곳으로 가까워지고 있어요"
            peak == null -> "폰을 평평하게 들고 천천히 한 바퀴 돌려 보세요"
            orb.aligned(heading) -> "바로 앞이에요! 지금 이 방향이 가장 세요"
            else -> "${SignalDirectionTracker.bearingText(peak)} 방향이 가장 세요 · 그쪽으로 돌아 보세요"
        }
    }

    private fun buzz(millis: Long, amplitude: Int) {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        try {
            vibrator.vibrate(VibrationEffect.createOneShot(millis, amplitude))
        } catch (e: Exception) {
            // A missing motor must never break the finder.
        }
    }

    // -------------------------------------------------------- detail card

    /**
     * The detail card keeps the folded MAC address 지훈 asked for, and its 🎯 button opens
     * the orb finder instead of the v1 direction dialog, so a device is always hunted on the
     * same dark-to-emerald screen.
     */
    override fun onDetailDialogReady(dialog: Dialog, signal: Signal) {
        val toggle = dialog.findViewById<TextView>(R.id.detailAddressToggle)
        val value = dialog.findViewById<TextView>(R.id.detailAddress)
        if (toggle != null && value != null) {
            value.text = macText(signal.address)
            value.visibility = View.GONE
            toggle.text = ADDRESS_HIDDEN
            toggle.setOnClickListener {
                val expand = value.visibility != View.VISIBLE
                value.visibility = if (expand) View.VISIBLE else View.GONE
                toggle.text = if (expand) ADDRESS_SHOWN else ADDRESS_HIDDEN
            }
        }
        dialog.findViewById<TextView>(R.id.detailDirection)?.setOnClickListener {
            dialog.dismiss()
            val live = repo.find(signal.id) ?: signal
            openOrbFinder(live)
        }
    }

    private fun macText(address: String): String =
        if (MAC_PATTERN.matches(address.uppercase())) address.uppercase() else address

    // --------------------------------------------------------------- utils

    private fun restart(runnable: Runnable, delay: Long) {
        handler.removeCallbacks(runnable)
        handler.postDelayed(runnable, delay)
    }

    private fun clock(millis: Long): String =
        SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date(millis))

    companion object {
        internal const val ADDRESS_HIDDEN = "세부 주소 보기 ▾"
        internal const val ADDRESS_SHOWN = "세부 주소 접기 ▴"

        private const val HEADING_MS = 250L
        private const val ORB_MS = 160L
        private const val PULSE_MS = 2_400f
        private const val HEADING_SMOOTHING = 0.25f

        /** A tap every two seconds while the signal is in reach. */
        private const val ORB_BUZZ_GAP_MS = 2_000L
        private const val ORB_BUZZ_MS = 30L

        /** Out of 255: felt, but nothing like the buzz 지훈 asked to tone down. */
        private const val ORB_BUZZ_AMPLITUDE = 60

        private val MAC_PATTERN = Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$")
    }
}
