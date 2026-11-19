package com.jihun.signalradar.nearby

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.jihun.signalradar.R
import com.jihun.signalradar.SignalAdapter
import com.jihun.signalradar.SignalRadarActivity
import com.jihun.signalradar.direction.Compass
import com.jihun.signalradar.direction.SignalDirectionTracker
import com.jihun.signalradar.model.Signal

/**
 * The "내 주변" screen: the distance list (unchanged) and a 10 m radar, switched by the two
 * buttons on the bottom bar.
 *
 * Scanning, the detail card and the direction finder all come from [SignalRadarActivity];
 * this class adds the radar, its list, and the compass that turns the radar dial.
 */
class NearbyRadarActivity : SignalRadarActivity() {

    private enum class Tab { DISTANCE, NEARBY }

    override val contentLayout: Int = R.layout.activity_nearby

    private val handler = Handler(Looper.getMainLooper())
    private var compass: Compass? = null
    private var smoothHeading: Float? = null
    private var tab = Tab.DISTANCE

    private lateinit var distancePage: View
    private lateinit var nearbyPage: View
    private lateinit var radar: NearbyRadarView
    private lateinit var nearbyList: RecyclerView
    private lateinit var nearbyEmpty: View
    private lateinit var nearbyTitle: TextView
    private lateinit var nearbyHeading: TextView
    private lateinit var nearbyCount: TextView
    private lateinit var nearbyScan: TextView

    private lateinit var tabDistanceIcon: TextView
    private lateinit var tabDistanceLabel: TextView
    private lateinit var tabDistanceIndicator: View
    private lateinit var tabNearbyIcon: TextView
    private lateinit var tabNearbyLabel: TextView
    private lateinit var tabNearbyIndicator: View

    private lateinit var adapter: SignalAdapter
    private var listSignature: String? = null
    private var compassRunning = false

    private val compassTick = object : Runnable {
        override fun run() {
            renderHeading()
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onScreenReady() {
        distancePage = findViewById(R.id.distancePage)
        nearbyPage = findViewById(R.id.nearbyPage)
        radar = findViewById(R.id.nearbyRadar)
        nearbyList = findViewById(R.id.nearbyList)
        nearbyEmpty = findViewById(R.id.nearbyEmpty)
        nearbyTitle = findViewById(R.id.nearbyTitle)
        nearbyHeading = findViewById(R.id.nearbyHeading)
        nearbyCount = findViewById(R.id.nearbyCount)
        nearbyScan = findViewById(R.id.nearbyScan)

        tabDistanceIcon = findViewById(R.id.tabDistanceIcon)
        tabDistanceLabel = findViewById(R.id.tabDistanceLabel)
        tabDistanceIndicator = findViewById(R.id.tabDistanceIndicator)
        tabNearbyIcon = findViewById(R.id.tabNearbyIcon)
        tabNearbyLabel = findViewById(R.id.tabNearbyLabel)
        tabNearbyIndicator = findViewById(R.id.tabNearbyIndicator)

        adapter = SignalAdapter { showDetails(it) }
        nearbyList.layoutManager = LinearLayoutManager(this)
        nearbyList.adapter = adapter

        // A dot on the radar opens the same card the list opens, so "🧭 방향 찾기" for a
        // device is always one tap away.
        radar.onBlipClick = { showDetails(it) }
        nearbyScan.setOnClickListener { toggleScan() }

        findViewById<View>(R.id.tabDistance).setOnClickListener { selectTab(Tab.DISTANCE) }
        findViewById<View>(R.id.tabNearby).setOnClickListener { selectTab(Tab.NEARBY) }

        selectTab(Tab.DISTANCE)
    }

    override fun onStart() {
        super.onStart()
        if (tab == Tab.NEARBY) startCompass()
    }

    override fun onStop() {
        stopCompass()
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacks(compassTick)
        super.onDestroy()
    }

    // ------------------------------------------------------------------- tabs

    private fun selectTab(next: Tab) {
        tab = next
        distancePage.visibility = if (next == Tab.DISTANCE) View.VISIBLE else View.GONE
        nearbyPage.visibility = if (next == Tab.NEARBY) View.VISIBLE else View.GONE
        renderTabs()
        if (next == Tab.NEARBY) startCompass() else stopCompass()
    }

    private fun renderTabs() {
        val distanceSelected = tab == Tab.DISTANCE
        tabDistanceIcon.alpha = if (distanceSelected) 1f else 0.55f
        tabDistanceLabel.setTextColor(
            ContextCompat.getColor(this, if (distanceSelected) R.color.accent else R.color.text_muted)
        )
        tabDistanceLabel.setTextTypeface(distanceSelected)
        tabDistanceIndicator.visibility = if (distanceSelected) View.VISIBLE else View.INVISIBLE

        tabNearbyIcon.alpha = if (distanceSelected) 0.55f else 1f
        tabNearbyLabel.setTextColor(
            ContextCompat.getColor(this, if (distanceSelected) R.color.text_muted else R.color.accent)
        )
        tabNearbyLabel.setTextTypeface(!distanceSelected)
        tabNearbyIndicator.visibility = if (distanceSelected) View.INVISIBLE else View.VISIBLE
    }

    private fun TextView.setTextTypeface(bold: Boolean) {
        setTypeface(typeface, if (bold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
    }

    // ---------------------------------------------------------------- compass

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

    /**
     * The dial is heading-up: the top of the radar is where the phone points, so the
     * north/east/south/west marks turn as the phone turns. A light circular filter keeps
     * the dial from shaking with the raw sensor noise.
     */
    private fun renderHeading() {
        val sensor = compass ?: return
        val supported = sensor.isSupported
        val raw = sensor.heading
        smoothHeading = when {
            raw == null -> smoothHeading
            smoothHeading == null -> raw
            else -> SignalDirectionTracker.normalize(
                (smoothHeading ?: raw) +
                    SignalDirectionTracker.signedDifference(smoothHeading ?: raw, raw) * HEADING_SMOOTHING
            )
        }

        radar.compassAvailable = supported
        smoothHeading?.let { radar.heading = it }
        nearbyHeading.text = when {
            !supported -> "이 폰에는 방향 센서가 없어요 · 기기를 눌러 🧭 방향 찾기로 방향을 재요"
            smoothHeading == null -> "폰이 보는 방향: 읽는 중…"
            else -> "폰이 보는 방향: ${SignalDirectionTracker.bearingText(smoothHeading ?: 0f)}"
        }
    }

    // ------------------------------------------------------------------ radar

    override fun onScanStateChanged(scanning: Boolean) {
        nearbyScan.text = if (scanning) "■  스캔 중지" else "▶  스캔 시작"
        nearbyScan.setBackgroundResource(
            if (scanning) R.drawable.bg_pill_small_stop else R.drawable.bg_pill_small
        )
        nearbyScan.setTextColor(
            ContextCompat.getColor(
                this,
                if (scanning) R.color.accent else R.color.button_on_primary
            )
        )
        radar.scanning = scanning
    }

    override fun onSignalsChanged(all: List<Signal>, newOnes: List<Signal>) {
        val near = NearbyRadarMath.nearbyBlips(all) { id -> repo.bearing(id) }

        radar.blips = near
        radar.scanning = isScanning
        nearbyTitle.text = "내 10m 안 기기 ${near.size}개"
        nearbyCount.text = "${near.size}개"
        nearbyEmpty.visibility = if (near.isEmpty()) View.VISIBLE else View.GONE

        val signature = buildString {
            for (blip in near) {
                append(blip.signal.id).append(':').append(blip.signal.rssi)
                append(':').append((blip.metres * 10).toInt())
                append(':').append(blip.bearing?.toInt() ?: -1)
                append(':').append(blip.signal.isNew).append('|')
            }
        }
        if (signature != listSignature) {
            listSignature = signature
            adapter.submit(near.map { it.signal })
        }
    }

    companion object {
        private const val TICK_MS = 250L
        private const val HEADING_SMOOTHING = 0.25f
    }
}
