package com.jihun.signalradar.v2

import android.app.Dialog
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
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
import kotlin.math.roundToInt

/**
 * 전파 감지기 v2.3 — the "나의 찾기" (Find My) style screens, still one separately installable
 * app (`com.jihun.signalradar.v2`) that leaves 전파 감지기 and 내 주변 untouched.
 *
 * What this class adds over the shared screen: the pastel top area with the nearest-signal
 * callout or the bright 10 m radar, the white rounded sheet with its grab handle, the big
 * title and the round scan button, the pill tab bar (신호 · 내 주변 · 기준), and the 신호 찾기
 * (orb) screen that starts dark and turns emerald when the signal is in reach.
 *
 * v2.2 adds the 기준 tab's list ([V2BaselineStore]): several saved baselines under their own
 * names, one of them in use, each renameable and deletable, and `🧹 기준 초기화` to drop them
 * all. The 기준 in use is what the 🚨 새 신호 check compares against, so switching one is felt
 * immediately.
 *
 * v2.3 answers two more requests from 지훈:
 *
 *  - "와이파이랑 기지국 안보게해주고": this screen is Bluetooth only. [keepsSource] narrows
 *    the shared scan, so the list, the chips, the 10 m radar, the 기준 and the finder never
 *    see Wi-Fi or a base station — and those two radios are not even read.
 *  - "진동만 크게해줘": the finder buzzes as a metal detector does, harder and faster the
 *    closer the signal is ([OrbHaptics]), with its own on/off button. Nothing is played out
 *    loud.
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

    /** v2.3: the finder's nearness feedback — vibration only, no sound at all. */
    private var haptics: OrbHaptics? = null
    private var orbHapticsOn = true
    private var orbHapticBand = 0

    /** `📌 내 폰 연결`: a v2.3 chip that narrows the list to the devices paired to this phone. */
    private var mineOnly = false
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
    private lateinit var recordName: TextView
    private lateinit var recordCount: TextView
    private lateinit var recordSavedAt: TextView
    private lateinit var recordSummary: TextView
    private lateinit var recordCards: LinearLayout
    private lateinit var recordEmpty: TextView
    private lateinit var recordReset: TextView
    private lateinit var vibrateRow: View
    private lateinit var vibrateState: TextView

    /** The saved 기준 (v2.2): several named baselines, one of them in use. */
    private lateinit var store: V2BaselineStore

    /** What the 기준 tab was last drawn for, so 0.8 s ticks do not rebuild its cards. */
    private var recordKey: String? = null

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
        recordName = findViewById(R.id.v2RecordName)
        recordCount = findViewById(R.id.v2RecordCount)
        recordSavedAt = findViewById(R.id.v2RecordSavedAt)
        recordSummary = findViewById(R.id.recordSummary)
        recordCards = findViewById(R.id.v2RecordList)
        recordEmpty = findViewById(R.id.v2RecordEmpty)
        recordReset = findViewById(R.id.v2RecordReset)
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

        // v2.2: the saved 기준 list. The shared screen wires `btnBaseline` to a nameless save;
        // here the same button asks for a name first and keeps the result in the store.
        findViewById<TextView>(R.id.btnBaseline).setOnClickListener { promptNewBaseline() }
        recordReset.setOnClickListener { confirmResetBaselines() }

        store = V2BaselineStore(this)
        // A 기준 saved by v2.1 becomes a normal named one, and the repository is made to match
        // whatever this list says is in use.
        store.adoptLegacy()
        store.applyActiveTo(repo)
        recordKey = null

        // v2.3: the finder's vibration, remembered across launches (on by default).
        orbHapticsOn = prefs().getBoolean(KEY_ORB_HAPTICS, true)
        haptics = OrbHaptics.from(this)
        renderOrbHaptics(null)

        tabSignal.setOnClickListener { selectTab(Tab.SIGNALS) }
        tabNearby.setOnClickListener { selectTab(Tab.NEARBY) }
        tabRecord.setOnClickListener { selectTab(Tab.RECORD) }

        // The chips are Bluetooth-only now: 전체 · 🚨 새 신호 · 내 폰 연결.
        findViewById<TextView>(R.id.chipAll).setOnClickListener { choose(Filter.ALL) }
        findViewById<TextView>(R.id.chipNew).setOnClickListener { choose(Filter.NEW) }
        findViewById<TextView>(R.id.chipBle).setOnClickListener { toggleMineOnly() }

        vibrateRow.setOnClickListener { toggleVibrate() }

        findViewById<View>(R.id.orbClose).setOnClickListener { closeOrb() }
        orbVibrate.setOnClickListener { toggleOrbHaptics() }
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
        stopOrbHaptics()
        stopCompass()
        super.onStop()
    }

    /** Leaving the finder by any route — home, another app, the lock screen — is silent. */
    override fun onPause() {
        stopOrbHaptics()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(compassTick)
        handler.removeCallbacks(orbTick)
        stopOrbHaptics()
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
        if (next == Tab.RECORD) {
            recordKey = null
            renderRecord()
        }
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
        renderVibrate()
        renderRecord()
        renderStatusLine(all)
    }

    /**
     * v2.3 is Bluetooth only (지훈: "와이파이랑 기지국 안보게해주고"). The shared screen reads
     * every count, the banner, the list, the radar and the 기준 from the list this narrows,
     * so Wi-Fi and base stations disappear everywhere at once — and are not scanned either.
     */
    override fun keepsSource(source: SignalSource): Boolean = source == SignalSource.BLE

    /** The permission card, worded for a phone that only ever reads Bluetooth. */
    override fun permissionNoticeText(): String =
        "주변의 블루투스 신호를 읽으려면 ‘근처 기기’와 ‘위치’ 권한이 필요해요.\n" +
            "읽은 신호는 폰 밖으로 나가지 않고 이 앱 안에서만 씁니다."

    /**
     * The per-radio counts live in the filter chips, so the sheet keeps its room for the list.
     * With one radio there is one count; the 🚨 chip is worded by the shared screen.
     */
    private fun countChips(all: List<Signal>) {
        val ble = all.count { it.source == SignalSource.BLE }
        findViewById<TextView>(R.id.chipAll)?.text = "전체 $ble"
        findViewById<TextView>(R.id.chipBle)?.text =
            "📌 내 폰 연결 " + all.count { it.isConnected || it.isBonded }
    }

    private fun styleChips() {
        val selected = activeFilter
        val chips = listOf(
            R.id.chipAll to (selected == Filter.ALL && !mineOnly),
            R.id.chipNew to (selected == Filter.NEW),
            R.id.chipBle to mineOnly
        )
        for ((id, on) in chips) {
            val chip = findViewById<TextView>(id) ?: continue
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

    /** 전체 · 🚨 새 신호: the shared filter, and the 내 폰 연결 chip steps aside. */
    private fun choose(next: Filter) {
        mineOnly = false
        setFilter(next)
        listKey = null
        redraw()
    }

    private fun toggleMineOnly() {
        mineOnly = !mineOnly
        listKey = null
        redraw()
        toast(
            if (mineOnly) "내 폰에 연결·등록된 블루투스만 보여요" else "블루투스 신호를 모두 보여요"
        )
    }

    /** Redraw the list and the chips without waiting for the next 0.8 s tick. */
    private fun redraw() {
        val all = repo.snapshot().filter { keepsSource(it.source) }
        onSignalsChanged(all, all.filter { it.isNew })
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
        val active = store.active()
        val key = recordSignature(active)
        if (key == recordKey) return
        recordKey = key

        recordName.text = active?.name ?: NO_BASELINE
        recordCount.text = if (active == null) {
            "아직 기준을 저장하지 않았어요"
        } else {
            "기기 ${active.size}개"
        }
        recordSavedAt.text = active?.let { "${clock(it.savedAt)} 저장" } ?: ""
        recordSummary.text = if (active == null) {
            "저장된 기준"
        } else {
            "저장된 기준 ${store.count()}개 · 적용 중 ‘${active.name}’"
        }
        recordEmpty.visibility = if (store.isEmpty()) View.VISIBLE else View.GONE
        recordReset.visibility = if (store.isEmpty()) View.GONE else View.VISIBLE
        renderBaselineCards(active)
    }

    /** Everything the 기준 tab draws, in one string, so a tick with nothing new is free. */
    private fun recordSignature(active: V2BaselineStore.Baseline?): String = buildString {
        append(active?.id ?: "-").append('|')
        for (item in store.all()) {
            append(item.id).append(':').append(item.name).append(':')
                .append(item.savedAt).append(':').append(item.size).append('|')
        }
    }

    /** One card per saved 기준: 이름 · 적용 중 · 기기 수 · 저장 시각, then 이름 바꾸기 / 삭제. */
    private fun renderBaselineCards(active: V2BaselineStore.Baseline?) {
        recordCards.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (item in store.all()) {
            val card = inflater.inflate(R.layout.item_baseline_v2, recordCards, false)
            val applied = item.id == active?.id
            card.findViewById<TextView>(R.id.baselineName).text = item.name
            card.findViewById<TextView>(R.id.baselineActive).visibility =
                if (applied) View.VISIBLE else View.GONE
            card.findViewById<TextView>(R.id.baselineMeta).text =
                "기기 ${item.size}개 · ${clock(item.savedAt)} 저장"
            card.setBackgroundResource(
                if (applied) R.drawable.bg_v2_row_on else R.drawable.bg_v2_row
            )
            card.setOnClickListener { activateBaseline(item.id) }
            card.findViewById<View>(R.id.baselineRename).setOnClickListener {
                promptRenameBaseline(item)
            }
            card.findViewById<View>(R.id.baselineDelete).setOnClickListener {
                confirmDeleteBaseline(item)
            }
            recordCards.addView(card)
        }
    }

    // ------------------------------------------------------- 기준 actions (v2.2)

    /** The Bluetooth devices heard right now with their level — the shape a 기준 is saved in. */
    private fun liveEntries(): Map<String, Int> {
        val map = LinkedHashMap<String, Int>()
        for (signal in repo.snapshot()) {
            // v2.3 keeps Bluetooth only, so a Wi-Fi or base-station level never enters a 기준.
            if (!keepsSource(signal.source)) continue
            map[signal.id] = (signal.smoothedRssi ?: signal.rssi.toFloat()).roundToInt()
        }
        return map
    }

    /** `📌 지금 신호를 새 기준으로 저장`: ask for a name, then save and start using it. */
    private fun promptNewBaseline() {
        val entries = liveEntries()
        if (entries.isEmpty()) {
            toast("아직 잡힌 신호가 없어요 · ‘스캔 시작’을 먼저 눌러 주세요")
            return
        }
        showNameDialog(
            title = "새 기준 이름",
            hint = "예: 우리 집 · 2학년 1반 · 복도",
            initial = "",
            okLabel = "저장"
        ) { name ->
            val item = store.add(name, entries)
            store.applyActiveTo(repo)
            onBaselinesChanged()
            toast("‘${item.name}’ 기준으로 기기 ${item.size}개를 저장했어요")
        }
    }

    private fun promptRenameBaseline(item: V2BaselineStore.Baseline) {
        showNameDialog(
            title = "기준 이름 바꾸기",
            hint = "지금 이름: ${item.name}",
            initial = item.name,
            okLabel = "바꾸기"
        ) { name ->
            val renamed = store.rename(item.id, name) ?: return@showNameDialog
            onBaselinesChanged()
            toast(
                if (renamed.name == item.name) {
                    "이름을 그대로 두었어요"
                } else {
                    "‘${renamed.name}’ 이름으로 바꿨어요"
                }
            )
        }
    }

    private fun confirmDeleteBaseline(item: V2BaselineStore.Baseline) {
        showConfirmDialog(
            title = "‘${item.name}’ 기준을 지울까요?",
            message = "기기 ${item.size}개를 담은 이 기준이 사라져요. 다른 기준은 그대로 남아요.",
            okLabel = "지우기"
        ) {
            val wasActive = store.activeId() == item.id
            store.delete(item.id)
            store.applyActiveTo(repo)
            onBaselinesChanged()
            val next = store.active()
            toast(
                if (wasActive && next != null) {
                    "‘${item.name}’을 지웠어요 · 이제 ‘${next.name}’ 기준을 써요"
                } else {
                    "‘${item.name}’ 기준을 지웠어요"
                }
            )
        }
    }

    /** `🧹 기준 초기화`: every saved 기준 and the 🚨 새 신호 state go back to the start. */
    private fun confirmResetBaselines() {
        val count = store.count()
        if (count == 0) return
        showConfirmDialog(
            title = "기준을 모두 지울까요?",
            message = "저장한 기준 ${count}개와 이름이 모두 사라지고, 🚨 새 신호 표시도 처음 상태로 돌아가요.",
            okLabel = "모두 지우기"
        ) {
            store.clearAll()
            store.applyActiveTo(repo)
            onBaselinesChanged()
            toast("기준을 초기화했어요 · 다시 저장하면 새 신호를 알려드려요")
        }
    }

    /** Tapping a 기준 card makes it the one the 🚨 새 신호 check compares against. */
    private fun activateBaseline(id: String) {
        val item = store.setActive(id) ?: return
        store.applyActiveTo(repo)
        onBaselinesChanged()
        toast("‘${item.name}’ 기준으로 바꿨어요 · 이 기준에 없던 신호만 새 신호로 알려드려요")
    }

    /** Redraw the 기준 tab and the lists at once, without waiting for the next shared tick. */
    private fun onBaselinesChanged() {
        recordKey = null
        renderRecord()
        redraw()
    }

    /** The 🔔 row of the 기준 tab: the new-signal buzz, which is a separate switch. */
    private fun renderVibrate() {
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
    }

    private fun toggleVibrate() {
        val enabled = !repo.vibrateOnNewSignal()
        repo.setVibrateOnNewSignal(enabled)
        renderVibrate()
        val bell = findViewById<TextView>(R.id.bellToggle)
        bell.text = if (enabled) "🔔" else "🔕"
        bell.alpha = if (enabled) 1f else 0.55f
        toast(if (enabled) "새 신호 진동을 켰어요 · 아주 약하게 한 번만 울려요" else "새 신호 진동을 껐어요")
    }

    // ------------------------------------------------- finder haptics (v2.3)

    /**
     * The finder's own switch: the metal-detector buzz that grows with the signal.
     * It is separate from the 🔔 new-signal switch on the 기준 tab, so turning off the hunt
     * feedback cannot silence the alert that a device arrived.
     */
    private fun toggleOrbHaptics() {
        orbHapticsOn = !orbHapticsOn
        prefs().edit().putBoolean(KEY_ORB_HAPTICS, orbHapticsOn).apply()
        if (orbHapticsOn) {
            // Pick the rhythm up on this very tick instead of waiting out a gap.
            haptics?.stop()
            renderOrb()
        } else {
            stopOrbHaptics()
            renderOrbHaptics(null)
        }
        toast(
            if (orbHapticsOn) "진동을 켰어요 · 가까워질수록 세고 잦게 울려요" else "진동을 껐어요"
        )
    }

    /** Stops the motor and forgets the rhythm; called on pause, on close and on destroy. */
    private fun stopOrbHaptics() {
        try {
            haptics?.stop()
        } catch (e: Exception) {
            // A motor that will not stop must not break the screen.
        }
        orbHapticBand = 0
    }

    /**
     * The finder's 진동 button: whether it is on, and which of the five bands the signal is
     * in right now, so the screen says both "가까워지고 있어" and "진동으로 느낄 수 있어".
     */
    private fun renderOrbHaptics(pulse: OrbPulse?) {
        val emerald = orbOpen && orb.emeraldProgress(System.currentTimeMillis()) > 0.5f
        orbVibrate.text = when {
            !orbHapticsOn -> "🔕 진동"
            pulse == null -> "📳 진동"
            else -> "📳 진동 ${pulse.band}/${OrbHapticsMap.BANDS}"
        }
        when {
            !orbHapticsOn -> {
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

    private fun prefs(): android.content.SharedPreferences =
        getSharedPreferences("signal_radar", MODE_PRIVATE)

    /** How many proximity taps the finder has played since it opened (v2.3). */
    internal fun orbHapticsForTest(): OrbHaptics? = haptics

    /** What the list shows for the chosen filter, in the same order the v1 screens use. */
    private fun visible(all: List<Signal>): List<Signal> {
        val filtered = when (activeFilter) {
            Filter.ALL -> all
            Filter.NEW -> all.filter { it.isNew || it.isBoosted }
            Filter.BLE -> all.filter { it.source == SignalSource.BLE }
            Filter.WIFI -> all.filter { it.source == SignalSource.WIFI }
            Filter.CELL -> all.filter { it.source == SignalSource.CELL }
        }.filter { keepsSource(it.source) }
        // The 📌 내 폰 연결 chip narrows whatever the shared filter already chose.
        val narrowed = if (mineOnly) {
            filtered.filter { it.source == SignalSource.BLE && (it.isConnected || it.isBonded) }
        } else {
            filtered
        }
        return narrowed.sortedWith(
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
        mineOnly -> "내 폰에 연결되거나 등록된 블루투스 기기가 없어요.\n‘📌 내 폰 연결’을 다시 누르면 모든 신호를 보여드려요."
        activeFilter != Filter.ALL -> super.emptyMessage()
        isScanning -> "스캔 중이에요 · 조금 기다리면 주변 블루투스 신호가 여기에 나타나요"
        else -> "아직 잡힌 블루투스 신호가 없어요.\n‘▶ 스캔 시작’을 눌러 주세요."
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
        renderOrbHaptics(null)
        renderOrb()
        restart(orbTick, ORB_MS)
    }

    internal fun closeOrb() {
        orbOpen = false
        orbPage.visibility = View.GONE
        orbWash.alpha = 0f
        handler.removeCallbacks(orbTick)
        // Leaving the finder must be silent at once, not after the last tap has played out.
        stopOrbHaptics()
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

        // v2.3: the nearness feedback is vibration (지훈: "진동만 크게해줘").
        // One call per tick: it answers the band for the button and taps the motor only when
        // this band's gap has passed, so getting closer is felt as stronger and more often.
        val pulse = if (orbHapticsOn) haptics?.onLevel(live.smoothedRssi ?: live.rssi.toFloat()) else null
        orbHapticBand = pulse?.band ?: 0
        renderOrbHaptics(pulse)

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
        peak: Float?,
        hapticBand: Int = if (emerald > 0.5f) OrbHapticsMap.BANDS else 3
    ) {
        handler.removeCallbacks(orbTick)
        orbSignalId = signal.id
        orbTitle.text = signal.title
        orbSubtitle.text = V2Look.summaryLine(signal)
        orbPage.visibility = View.VISIBLE
        orbOpen = true
        back.isEnabled = true
        orbWash.alpha = emerald * 0.94f
        orbHapticBand = hapticBand
        renderOrbHaptics(previewPulse(hapticBand))
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

    /** The pulse a preview paints for a band, so a screenshot shows real numbers. */
    private fun previewPulse(band: Int): OrbPulse? {
        val level = when (band) {
            1 -> -88f
            2 -> -80f
            3 -> -70f
            4 -> -62f
            5 -> -46f
            else -> return null
        }
        return OrbHapticsMap.pulseFor(level)
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

    /**
     * The 기준 name card: one text box, used both for a new 기준 and for renaming one.
     * A blank answer is passed through as "" — the store turns that into `기준 1` when saving
     * and keeps the old name when renaming.
     */
    private fun showNameDialog(
        title: String,
        hint: String,
        initial: String,
        okLabel: String,
        onDone: (String) -> Unit
    ) {
        val dialog = Dialog(this, R.style.Theme_SignalRadar_Dialog)
        dialog.setContentView(R.layout.dialog_baseline_name_v2)
        dialog.findViewById<TextView>(R.id.nameDialogTitle).text = title
        dialog.findViewById<TextView>(R.id.nameDialogHint).text = hint
        dialog.findViewById<TextView>(R.id.nameDialogOk).text = okLabel
        val input = dialog.findViewById<EditText>(R.id.nameDialogInput)
        input.setText(initial)
        if (initial.isNotEmpty()) input.setSelection(initial.length)
        dialog.findViewById<View>(R.id.nameDialogCancel).setOnClickListener { dialog.dismiss() }
        dialog.findViewById<View>(R.id.nameDialogOk).setOnClickListener {
            val name = input.text?.toString().orEmpty()
            dialog.dismiss()
            onDone(name)
        }
        sizeDialog(dialog)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    }

    /** The one question a destructive tap has to answer: `취소` or the red button. */
    private fun showConfirmDialog(
        title: String,
        message: String,
        okLabel: String,
        onConfirm: () -> Unit
    ) {
        val dialog = Dialog(this, R.style.Theme_SignalRadar_Dialog)
        dialog.setContentView(R.layout.dialog_baseline_confirm_v2)
        dialog.findViewById<TextView>(R.id.confirmTitle).text = title
        dialog.findViewById<TextView>(R.id.confirmMessage).text = message
        dialog.findViewById<TextView>(R.id.confirmOk).text = okLabel
        dialog.findViewById<View>(R.id.confirmCancel).setOnClickListener { dialog.dismiss() }
        dialog.findViewById<View>(R.id.confirmOk).setOnClickListener {
            dialog.dismiss()
            onConfirm()
        }
        sizeDialog(dialog)
    }

    /** Show a v2 card dialog at the width the shared detail card uses. */
    private fun sizeDialog(dialog: Dialog) {
        dialog.show()
        val metrics = resources.displayMetrics
        val margin = (16 * metrics.density).toInt()
        val width = (metrics.widthPixels - margin * 2)
            .coerceAtMost((metrics.widthPixels * 0.92f).toInt())
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    /**
     * The line under the sheet title, kept true to the 기준 in use. The shared screen writes it
     * with the repository's own saved-at stamp, which a 기준 loaded from the list does not have;
     * only that "지금 …" line is replaced, so a permission notice is never overwritten.
     */
    private fun renderStatusLine(all: List<Signal>) {
        val active = store.active() ?: return
        val line = findViewById<TextView>(R.id.statusDetail)
        if (!line.text.startsWith("지금 ")) return
        line.text = "지금 ${all.size}개 신호 · 기준 ‘${active.name}’ ${active.size}개"
    }

    private fun restart(runnable: Runnable, delay: Long) {
        handler.removeCallbacks(runnable)
        handler.postDelayed(runnable, delay)
    }

    private fun clock(millis: Long): String =
        SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date(millis))

    companion object {
        internal const val ADDRESS_HIDDEN = "세부 주소 보기 ▾"
        internal const val ADDRESS_SHOWN = "세부 주소 접기 ▴"

        /** What the top card and the 기준 tab say while nothing is saved. */
        internal const val NO_BASELINE = "저장된 기준 없음"

        private const val HEADING_MS = 250L
        private const val ORB_MS = 160L
        private const val PULSE_MS = 2_400f
        private const val HEADING_SMOOTHING = 0.25f

        /** v2.3: the finder's proximity vibration is on by default (지훈: "진동만 크게해줘"). */
        internal const val KEY_ORB_HAPTICS = "orb_haptics_on"

        private val MAC_PATTERN = Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$")
    }
}
