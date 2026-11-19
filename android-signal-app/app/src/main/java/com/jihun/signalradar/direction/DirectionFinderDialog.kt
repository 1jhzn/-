package com.jihun.signalradar.direction

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.jihun.signalradar.R
import com.jihun.signalradar.identify.Classifier
import com.jihun.signalradar.model.Signal
import com.jihun.signalradar.model.SignalSource
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * "방향 찾기" — turn the phone slowly and the app remembers which bearing the signal was
 * strongest on, then points an arrow that way.
 *
 * A phone has no radio direction finder, so this is triangulation by hand: the compass
 * gives the bearing, the scan gives the level, and the tracker smooths both so the arrow
 * only moves when the signal really is stronger somewhere else.
 */
class DirectionFinderDialog(
    context: Context,
    private val signal: Signal,
    private val liveSignal: () -> Signal?,
    private val requestFreshWifiScan: () -> Boolean
) : Dialog(context, R.style.Theme_SignalRadar_Dialog) {

    private val tracker = SignalDirectionTracker()
    private val compass = Compass(context)
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var radar: RadarCompassView
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var alignedView: TextView
    private lateinit var bearing: TextView
    private lateinit var trend: TextView
    private lateinit var levels: TextView
    private lateinit var progress: TextView
    private lateinit var distance: TextView
    private lateinit var phone: TextView
    private lateinit var hint: TextView

    private var running = false
    private var lastSeenFed = 0L
    private var lastRssiFed = Int.MIN_VALUE
    private var lastWifiAsk = 0L
    private var lastHint: String? = null
    private var wasAligned = false
    private var lastBuzz = 0L
    private var smoothHeading: Float? = null

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val now = System.currentTimeMillis()
            val heading = readHeading()

            val live = liveSignal()
            if (live != null && (live.lastSeen != lastSeenFed || live.rssi != lastRssiFed)) {
                lastSeenFed = live.lastSeen
                lastRssiFed = live.rssi
                tracker.onSample(live.rssi, heading, now)
            }

            if (signal.source == SignalSource.WIFI && now - lastWifiAsk >= WIFI_ASK_MS) {
                lastWifiAsk = now
                val accepted = requestFreshWifiScan()
                wifiNote = if (accepted) {
                    null
                } else {
                    "와이파이는 폰이 정해진 횟수만 새로 읽을 수 있어서 몇 초 늦게 갱신될 수 있어요."
                }
            }

            render(heading)
            handler.postDelayed(this, TICK_MS)
        }
    }

    private var wifiNote: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_direction)

        radar = findViewById(R.id.dirRadar)
        title = findViewById(R.id.dirTitle)
        subtitle = findViewById(R.id.dirSubtitle)
        alignedView = findViewById(R.id.dirAligned)
        bearing = findViewById(R.id.dirBearing)
        trend = findViewById(R.id.dirTrend)
        levels = findViewById(R.id.dirLevels)
        progress = findViewById(R.id.dirProgress)
        distance = findViewById(R.id.dirDistance)
        phone = findViewById(R.id.dirPhone)
        hint = findViewById(R.id.dirHint)

        title.text = signal.title
        subtitle.text = listOfNotNull(
            signal.source.label,
            signal.vendor ?: signal.kind,
            signal.address
        ).joinToString(" · ")
        radar.compassAvailable = compass.isSupported

        findViewById<View>(R.id.dirReset).setOnClickListener { reset() }
        findViewById<View>(R.id.dirClose).setOnClickListener { dismiss() }
        findViewById<View>(R.id.dirCloseTop).setOnClickListener { dismiss() }
    }

    override fun onStart() {
        super.onStart()
        window?.let { window ->
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val metrics = context.resources.displayMetrics
            val margin = (14 * metrics.density).toInt()
            val width = (metrics.widthPixels - margin * 2).coerceAtMost((metrics.widthPixels * 0.96f).toInt())
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        begin()
    }

    override fun onStop() {
        end()
        super.onStop()
    }

    override fun dismiss() {
        end()
        super.dismiss()
    }

    private fun begin() {
        if (running) return
        running = true
        if (compass.isSupported) compass.start()
        render(readHeading())
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    private fun end() {
        if (!running) return
        running = false
        handler.removeCallbacks(tick)
        compass.stop()
    }

    private fun reset() {
        tracker.reset()
        lastSeenFed = 0L
        lastRssiFed = Int.MIN_VALUE
        wasAligned = false
        radar.peakAzimuth = null
        radar.sectorPeaks = null
        radar.aligned = false
        radar.invalidate()
        smoothHeading = null
        render(readHeading())
    }

    /**
     * The raw compass bearing wobbles by a few degrees even when the phone is still; a light
     * circular filter keeps the needle and the "pointing at it" check from flickering.
     */
    private fun readHeading(): Float? {
        val raw = compass.heading ?: return null
        val previous = smoothHeading
        val next = if (previous == null) {
            raw
        } else {
            SignalDirectionTracker.normalize(
                previous + SignalDirectionTracker.signedDifference(previous, raw) * HEADING_SMOOTHING
            )
        }
        smoothHeading = next
        return next
    }

    private fun render(heading: Float?) {
        val smoothed = tracker.smoothedRssi()
        val peak = tracker.peakRssi
        val peakAzimuth = tracker.peakAzimuth
        val hasCompass = compass.isSupported

        radar.heading = heading ?: 0f
        radar.peakAzimuth = peakAzimuth
        radar.sectorPeaks = FloatArray(SignalDirectionTracker.SECTORS) { index ->
            tracker.sectorValue(index) ?: Float.NaN
        }
        radar.compassAvailable = hasCompass
        radar.aligned = hasCompass &&
            peakAzimuth != null &&
            heading != null &&
            tracker.sampleCount >= MIN_SAMPLES &&
            tracker.isAligned(heading)
        radar.invalidate()

        bearing.text = when {
            !hasCompass -> "방향 센서를 읽을 수 없어요"
            peakAzimuth != null -> SignalDirectionTracker.bearingText(peakAzimuth)
            heading != null ->
                "천천히 돌려 보세요 · 지금 ${SignalDirectionTracker.bearingText(heading)}"
            else -> "나침반 값을 기다리는 중…"
        }

        // How much of the circle has been walked: a bearing cannot be trusted from one or
        // two directions, and saying so is better than a needle that points nowhere.
        progress.text = when {
            !hasCompass -> ""
            peakAzimuth != null ->
                SignalDirectionTracker.progressLabel(tracker.measuredSectors) +
                    " · 더 돌리면 더 정확해져요"
            else -> SignalDirectionTracker.progressLabel(tracker.measuredSectors) +
                " · ${SignalDirectionTracker.MIN_SECTORS_FOR_PEAK}곳 이상 재면 방향을 잡아요"
        }
        bearing.setTextColor(
            ContextCompat.getColor(
                context,
                if (radar.aligned) R.color.green else R.color.text_primary
            )
        )

        alignedView.visibility = if (radar.aligned) View.VISIBLE else View.INVISIBLE
        if (radar.aligned && !wasAligned) {
            buzz(60)
        }
        wasAligned = radar.aligned

        levels.text = buildString {
            append("최고 ")
            append(peak?.roundToInt()?.toString() ?: "–")
            append(" dBm · 현재 ")
            append(smoothed?.roundToInt()?.toString() ?: "–")
            append(" dBm")
        }

        distance.text = smoothed?.let {
            Classifier.distanceLabel(it, signal.source, signal.txPower, signal.frequencyMhz)
        } ?: "거리 계산 중"

        renderTrend()
        renderPhone(heading, hasCompass)
        renderHint()
    }

    private fun renderTrend() {
        val change = tracker.trendDb()
        when {
            change == null -> {
                trend.text = "측정 중"
                trend.setTextColor(ContextCompat.getColor(context, R.color.text_muted))
            }
            change >= TREND_DB -> {
                trend.text = "🔥 신호 강해짐"
                trend.setTextColor(ContextCompat.getColor(context, R.color.amber))
            }
            change <= -TREND_DB -> {
                trend.text = "❄️ 약해짐"
                trend.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            }
            else -> {
                trend.text = "유지"
                trend.setTextColor(ContextCompat.getColor(context, R.color.accent))
            }
        }
    }

    private fun renderPhone(heading: Float?, hasCompass: Boolean) {
        phone.text = when {
            heading == null && hasCompass -> "나침반 값을 읽는 중이에요"
            heading == null -> "이 폰에는 방향 센서가 없어 화살표를 그릴 수 없어요"
            else -> buildString {
                append("폰이 보는 방향 ")
                append(SignalDirectionTracker.bearingText(heading))
                append(" · ")
                append(compass.modeText)
                val tilt = compass.tilt
                if (tilt != null && abs(tilt) > 55f) append(" · 폰을 조금 더 평평하게")
            }
        }
    }

    private fun renderHint() {
        val text = buildString {
            if (signal.source == SignalSource.WIFI) {
                append("폰을 평평하게 들고 천천히 한 바퀴 돌려 보세요.\n")
                append("와이파이는 갱신이 느려서 한 방향마다 2~3초 멈춰 재면 더 정확해요.")
            } else {
                append("폰을 평평하게 들고 천천히 한 바퀴 돌려 보세요. ")
                append("블루투스는 광고 패킷을 바로 읽어서 반응이 빨라요.")
            }
            wifiNote?.let { append('\n').append(it) }
            append('\n').append("거리는 신호 세기로 계산한 어림값이라 벽·장애물에 따라 달라질 수 있어요.")
            if (!compass.isSupported) {
                append('\n').append("이 폰에는 나침반 센서가 없어 방향 화살표 대신 신호 세기 변화만 보여 드려요.")
            }
        }
        if (text != lastHint) {
            lastHint = text
            hint.text = text
        }
    }

    private fun buzz(millis: Long) {
        val now = System.currentTimeMillis()
        if (now - lastBuzz < BUZZ_COOLDOWN_MS) return
        lastBuzz = now
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        try {
            vibrator.vibrate(VibrationEffect.createOneShot(millis, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) {
            // A missing vibration motor must never break the direction finder.
        }
    }

    companion object {
        private const val TICK_MS = 200L
        private const val WIFI_ASK_MS = 12_000L
        private const val MIN_SAMPLES = 6
        private const val TREND_DB = 2f
        private const val BUZZ_COOLDOWN_MS = 2_200L
        private const val HEADING_SMOOTHING = 0.25f
    }
}
