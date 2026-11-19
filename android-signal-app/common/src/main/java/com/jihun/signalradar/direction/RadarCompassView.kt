package com.jihun.signalradar.direction

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * A dark radar dial drawn by hand on a Canvas: concentric rings and degree ticks, the four
 * cardinal points, one heat arc per 30° sector showing the strongest level heard while the
 * phone pointed that way, and a bright needle on the strongest direction found so far.
 *
 * The whole card turns with the phone (a real compass behaves like that), so a needle that
 * sits still on screen means "the signal is that way — keep turning toward it".
 */
class RadarCompassView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** Compass bearing the phone currently points at. */
    var heading: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    /** Bearing of the strongest reading so far, null until one is found. */
    var peakAzimuth: Float? = null
        set(value) {
            field = value
            invalidate()
        }

    /** Smoothed peak level per sector (12 of them); NaN where nothing was heard yet. */
    var sectorPeaks: FloatArray? = null
        set(value) {
            field = value
            invalidate()
        }

    /** True when the phone is pointing within tolerance of the peak. */
    var aligned: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var compassAvailable: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    private var shownHeading = 0f
    private var shownPeak: Float? = null
    private var pulse = 0f

    private val density = resources.displayMetrics.density

    init {
        // The soft glow around the needle is drawn with a shadow layer.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    private val dialPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = Color.parseColor("#17242F")
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = Color.parseColor("#2C3D4F")
    }
    private val majorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
        color = Color.parseColor("#42596E")
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 11f * density * 1.0f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val heatPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val beamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val hubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#0D141D")
    }
    private val hubRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = Color.parseColor("#2ED3C6")
    }
    private val phoneMarkerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#E9F1F8")
    }

    private val arcRect = RectF()
    private val needlePath = Path()
    private val beamPath = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val cx = w / 2f
        val cy = h / 2f
        val outer = min(w, h) / 2f - 4f * density
        if (outer <= 0f) return

        shownHeading = easeAngle(shownHeading, heading, 0.28f)
        val target = peakAzimuth
        shownPeak = when {
            target == null -> null
            shownPeak == null -> target
            else -> easeAngle(shownPeak ?: target, target, 0.22f)
        }

        drawDialBase(canvas, cx, cy, outer)

        canvas.save()
        canvas.rotate(-shownHeading, cx, cy)
        drawTicks(canvas, cx, cy, outer)
        drawCardinals(canvas, cx, cy, outer)
        drawHeatArcs(canvas, cx, cy, outer)
        drawNeedle(canvas, cx, cy, outer)
        canvas.restore()

        drawPhoneMarker(canvas, cx, cy, outer)
        drawHub(canvas, cx, cy, outer)

        pulse = if (aligned) (pulse + 0.12f) % 1f else 0f
    }

    private fun drawDialBase(canvas: Canvas, cx: Float, cy: Float, outer: Float) {
        dialPaint.shader = RadialGradient(
            cx, cy, outer,
            intArrayOf(
                Color.parseColor("#101A24"),
                Color.parseColor("#0A1119"),
                Color.parseColor("#070B10")
            ),
            floatArrayOf(0f, 0.75f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, outer, dialPaint)
        dialPaint.shader = null

        ringPaint.color = Color.parseColor("#22303F")
        ringPaint.strokeWidth = 1.4f * density
        canvas.drawCircle(cx, cy, outer, ringPaint)

        for (fraction in floatArrayOf(0.32f, 0.58f, 0.84f)) {
            ringPaint.color = Color.parseColor("#16222E")
            ringPaint.strokeWidth = 1f * density
            canvas.drawCircle(cx, cy, outer * fraction, ringPaint)
        }

        ringPaint.color = Color.parseColor("#22303F")
        ringPaint.strokeWidth = 1f * density
        canvas.drawCircle(cx, cy, outer * 0.12f, ringPaint)
    }

    private fun drawTicks(canvas: Canvas, cx: Float, cy: Float, outer: Float) {
        for (degree in 0 until 360 step 5) {
            val rad = Math.toRadians(degree.toDouble())
            val major = degree % 30 == 0
            val medium = degree % 15 == 0
            val length = when {
                major -> 9f * density
                medium -> 6f * density
                else -> 3.5f * density
            }
            val startR = outer - 1f * density
            val endR = startR - length
            val sinA = sin(rad).toFloat()
            val cosA = cos(rad).toFloat()
            canvas.drawLine(
                cx + sinA * startR,
                cy - cosA * startR,
                cx + sinA * endR,
                cy - cosA * endR,
                if (major) majorTickPaint else tickPaint
            )
        }
    }

    private fun drawCardinals(canvas: Canvas, cx: Float, cy: Float, outer: Float) {
        val radius = outer * 0.47f
        val labels = arrayOf("N" to "북", "E" to "동", "S" to "남", "W" to "서")
        for ((index, pair) in labels.withIndex()) {
            val degree = index * 90.0
            val rad = Math.toRadians(degree)
            val x = cx + sin(rad).toFloat() * radius
            val y = cy - cos(rad).toFloat() * radius
            labelPaint.textSize = 12f * density
            labelPaint.color = if (index == 0) {
                Color.parseColor("#2ED3C6")
            } else {
                Color.parseColor("#7C8FA5")
            }
            canvas.save()
            // Keep the letters upright while the card itself turns under them.
            canvas.rotate(shownHeading, x, y)
            canvas.drawText(pair.second, x, y + labelPaint.textSize * 0.36f, labelPaint)
            canvas.restore()
        }
    }

    private fun drawHeatArcs(canvas: Canvas, cx: Float, cy: Float, outer: Float) {
        val values = sectorPeaks ?: return
        if (!compassAvailable) return
        val band = outer * 0.70f
        heatPaint.strokeWidth = outer * 0.16f
        for (index in 0 until SignalDirectionTracker.SECTORS) {
            val value = if (index < values.size) values[index] else Float.NaN
            if (value.isNaN() || value.isInfinite()) continue
            // Canvas.drawArc measures from 3 o'clock; every bearing here is a compass
            // bearing measured from 12 o'clock, so the quarter turn has to be taken out —
            // without it each arc lands 90° away from the direction it was measured in.
            val start = index * SignalDirectionTracker.SECTOR_DEG -
                SignalDirectionTracker.SECTOR_DEG / 2f - 90f + 1.2f
            val sweep = SignalDirectionTracker.SECTOR_DEG - 2.4f
            heatPaint.color = heatColor(value)
            arcRect.set(cx - band, cy - band, cx + band, cy + band)
            canvas.drawArc(arcRect, start, sweep, false, heatPaint)
        }
    }

    private fun drawNeedle(canvas: Canvas, cx: Float, cy: Float, outer: Float) {
        if (!compassAvailable) return
        val peak = shownPeak ?: return
        val rad = Math.toRadians(peak.toDouble())
        val sinA = sin(rad).toFloat()
        val cosA = cos(rad).toFloat()
        val tipR = outer * 0.60f

        // Soft beam behind the needle so the direction reads at a glance.
        val beamLeft = peak - 9f
        val beamRight = peak + 9f
        beamPath.reset()
        beamPath.moveTo(cx, cy)
        beamPath.lineTo(
            cx + sin(Math.toRadians(beamLeft.toDouble())).toFloat() * outer * 0.88f,
            cy - cos(Math.toRadians(beamLeft.toDouble())).toFloat() * outer * 0.88f
        )
        arcRect.set(
            cx - outer * 0.88f,
            cy - outer * 0.88f,
            cx + outer * 0.88f,
            cy + outer * 0.88f
        )
        beamPath.arcTo(arcRect, beamLeft - 90f, beamRight - beamLeft, false)
        beamPath.close()
        beamPaint.shader = RadialGradient(
            cx, cy, outer * 0.9f,
            intArrayOf(
                if (aligned) Color.parseColor("#6634D399") else Color.parseColor("#552ED3C6"),
                Color.parseColor("#002ED3C6")
            ),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(beamPath, beamPaint)
        beamPaint.shader = null

        // A classic needle: a wide head that reads at a glance, then a thin tail that
        // points back through the hub.
        val headWidth = outer * 0.085f
        val tailWidth = outer * 0.026f
        val headR = outer * 0.42f
        val tailR = outer * 0.16f
        val tipX = cx + sinA * tipR
        val tipY = cy - cosA * tipR
        needlePath.reset()
        needlePath.moveTo(tipX, tipY)
        needlePath.lineTo(cx + cosA * headWidth + sinA * headR, cy + sinA * headWidth - cosA * headR)
        needlePath.lineTo(cx + cosA * tailWidth + sinA * tailR, cy + sinA * tailWidth - cosA * tailR)
        needlePath.lineTo(cx - cosA * tailWidth + sinA * tailR, cy - sinA * tailWidth - cosA * tailR)
        needlePath.lineTo(cx - cosA * headWidth + sinA * headR, cy - sinA * headWidth - cosA * headR)
        needlePath.close()

        needlePaint.shader = LinearGradient(
            cx, cy, tipX, tipY,
            if (aligned) Color.parseColor("#34D399") else Color.parseColor("#2ED3C6"),
            Color.parseColor("#E9F1F8"),
            Shader.TileMode.CLAMP
        )
        needlePaint.setShadowLayer(
            if (aligned) 14f * density else 8f * density,
            0f,
            0f,
            if (aligned) Color.parseColor("#9934D399") else Color.parseColor("#662ED3C6")
        )
        canvas.drawPath(needlePath, needlePaint)
        needlePaint.shader = null
        needlePaint.clearShadowLayer()

        if (aligned && pulse > 0f) {
            ringPaint.color = Color.argb((180 * (1f - pulse)).toInt(), 52, 211, 153)
            ringPaint.strokeWidth = 2f * density
            canvas.drawCircle(cx, cy, outer * (0.35f + 0.55f * pulse), ringPaint)
        }
    }

    private fun drawPhoneMarker(canvas: Canvas, cx: Float, cy: Float, outer: Float) {
        val top = cy - outer - 1f * density
        phoneMarkerPaint.color =
            if (compassAvailable) Color.parseColor("#E9F1F8") else Color.parseColor("#63748A")
        val half = 5f * density
        val height = 7f * density
        val path = Path()
        path.moveTo(cx, top + height)
        path.lineTo(cx - half, top)
        path.lineTo(cx + half, top)
        path.close()
        canvas.drawPath(path, phoneMarkerPaint)
    }

    private fun drawHub(canvas: Canvas, cx: Float, cy: Float, outer: Float) {
        canvas.drawCircle(cx, cy, outer * 0.065f, hubPaint)
        hubRingPaint.color = if (aligned) Color.parseColor("#34D399") else Color.parseColor("#2ED3C6")
        canvas.drawCircle(cx, cy, outer * 0.065f, hubRingPaint)
    }

    /** Blue when weak, teal through the middle, amber and red near the peak. */
    private fun heatColor(rssi: Float): Int {
        val stops = intArrayOf(
            Color.parseColor("#1E3A8A"),
            Color.parseColor("#2563EB"),
            Color.parseColor("#2ED3C6"),
            Color.parseColor("#FBBF24"),
            Color.parseColor("#F87171")
        )
        val edges = floatArrayOf(-95f, -82f, -68f, -58f, -48f)
        if (rssi <= edges[0]) return withAlpha(stops[0], 190)
        if (rssi >= edges[edges.size - 1]) return withAlpha(stops[stops.size - 1], 235)
        for (i in 0 until edges.size - 1) {
            if (rssi in edges[i]..edges[i + 1]) {
                val t = (rssi - edges[i]) / (edges[i + 1] - edges[i])
                val color = blend(stops[i], stops[i + 1], t)
                return withAlpha(color, (190 + 45 * t).toInt())
            }
        }
        return withAlpha(stops[stops.size - 1], 235)
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    private fun blend(from: Int, to: Int, t: Float): Int {
        val clamped = t.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(from) + (Color.red(to) - Color.red(from)) * clamped).toInt(),
            (Color.green(from) + (Color.green(to) - Color.green(from)) * clamped).toInt(),
            (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * clamped).toInt()
        )
    }

    private fun easeAngle(current: Float, target: Float, factor: Float): Float =
        SignalDirectionTracker.normalize(
            current + SignalDirectionTracker.signedDifference(current, target) * factor
        )

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val size = min(measuredWidth, measuredHeight)
        if (size > 0 && measuredWidth != measuredHeight) {
            setMeasuredDimension(size, size)
        }
    }
}
