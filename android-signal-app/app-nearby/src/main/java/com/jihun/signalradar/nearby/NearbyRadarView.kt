package com.jihun.signalradar.nearby

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.jihun.signalradar.R
import com.jihun.signalradar.direction.CompassGeometry
import com.jihun.signalradar.identify.DeviceType
import com.jihun.signalradar.model.Signal
import com.jihun.signalradar.model.SignalSource
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * A 10 m radar drawn by hand on a Canvas.
 *
 * - The middle is me (0 m); rings sit at 2 m, 5 m and 10 m.
 * - A dot's distance from the middle is its estimated distance, never more than the 10 m
 *   ring.
 * - The dial turns with the phone: the top of the circle is where the phone is pointing,
 *   and north/labels move around it, so "up" on screen is "in front of me".
 * - Bluetooth dots are blue, Wi-Fi dots are green.
 * - A dot whose direction was measured by a hand-held sweep sits at that real bearing and
 *   is drawn solid, with a ray from the middle. Everything else can only be placed at a
 *   placeholder angle derived from its address, and is drawn as a hollow dashed dot so
 *   nobody mistakes it for a measured direction.
 */
class NearbyRadarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** Compass bearing the top of the phone points at; the dial turns by this much. */
    var heading: Float = 0f
        set(value) {
            val next = NearbyRadarMath.normalize(value)
            if (next != field) {
                field = next
                invalidate()
            }
        }

    var compassAvailable: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var scanning: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var blips: List<NearbyBlip> = emptyList()
        set(value) {
            field = value
            anglesSignature = null
            contentDescription = "10m 레이더 · 기기 ${value.size}개"
            invalidate()
        }

    /** Tapped a dot (or its label): open the same detail card the list opens. */
    var onBlipClick: ((Signal) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private var shownHeading = 0f

    private var anglesSignature: String? = null
    private var placeholderAngles: Map<String, Float> = emptyMap()

    private val hits = ArrayList<Hit>()
    private val placedLabels = ArrayList<RectF>()
    private val rect = RectF()

    private val bleColor = ContextCompat.getColor(context, R.color.nearby_ble)
    private val wifiColor = ContextCompat.getColor(context, R.color.nearby_wifi)
    private val accentColor = ContextCompat.getColor(context, R.color.accent)
    private val textPrimary = ContextCompat.getColor(context, R.color.text_primary)
    private val textMuted = ContextCompat.getColor(context, R.color.text_muted)
    private val labelBg = ContextCompat.getColor(context, R.color.nearby_label_bg)

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(dp(2.6f), dp(2.2f)), 0f)
    }
    private val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val typePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val phoneMarker = Path()

    init {
        isClickable = true
        // The soft glow on a measured dot is drawn with a shadow layer.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    private fun dp(value: Float): Float = value * density

    // ---------------------------------------------------------------------- draw

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val cx = w / 2f
        val cy = h / 2f
        val outer = min(w, h) / 2f - dp(9f)
        if (outer <= 0f) return

        // Follow the compass smoothly: a raw heading jitters by a few degrees every 200 ms.
        shownHeading = NearbyRadarMath.normalize(
            shownHeading + signedDifference(shownHeading, heading) * 0.22f
        )

        drawBase(canvas, cx, cy, outer)
        drawRings(canvas, cx, cy, outer)
        drawTicks(canvas, cx, cy, outer)
        drawCardinals(canvas, cx, cy, outer)
        drawPhoneMarker(canvas, cx, cy, outer)
        drawBlips(canvas, cx, cy, outer)
        drawCenter(canvas, cx, cy)
        if (blips.isEmpty()) drawEmptyText(canvas, cx, cy, outer)
    }

    private fun drawBase(canvas: Canvas, cx: Float, cy: Float, outer: Float) {
        fillPaint.shader = RadialGradient(
            cx, cy, outer,
            intArrayOf(
                Color.parseColor("#101A24"),
                Color.parseColor("#0A1119"),
                Color.parseColor("#070B10")
            ),
            floatArrayOf(0f, 0.78f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, outer, fillPaint)
        fillPaint.shader = null

        strokePaint.strokeWidth = dp(1.4f)
        strokePaint.color = Color.parseColor("#22303F")
        canvas.drawCircle(cx, cy, outer, strokePaint)
    }

    /** 2 m, 5 m, 10 m rings, each labelled just inside its own circle. */
    private fun drawRings(canvas: Canvas, cx: Float, cy: Float, outer: Float) {
        val rings = floatArrayOf(2f / NearbyRadarMath.MAX_METRES, 5f / NearbyRadarMath.MAX_METRES, 1f)
        val labels = arrayOf("2m", "5m", "10m")
        smallPaint.textSize = dp(9f)
        smallPaint.textAlign = Paint.Align.LEFT
        smallPaint.color = textMuted
        for (index in rings.indices) {
            val radius = outer * rings[index]
            val last = index == rings.size - 1
            strokePaint.strokeWidth = if (last) dp(1.4f) else dp(1f)
            strokePaint.color = Color.parseColor(if (last) "#22303F" else "#16222E")
            canvas.drawCircle(cx, cy, radius, strokePaint)
            canvas.drawText(
                labels[index],
                cx + dp(5f),
                cy - radius + smallPaint.textSize + dp(2f),
                smallPaint
            )
        }
        smallPaint.textAlign = Paint.Align.CENTER
    }

    /** Degree ticks around the rim; every 30° is longer. They turn with the phone. */
    private fun drawTicks(canvas: Canvas, cx: Float, cy: Float, outer: Float) {
        for (degree in 0 until 360 step 10) {
            val rad = Math.toRadians((degree - shownHeading).toDouble())
            val major = degree % 30 == 0
            val start = outer - dp(1f)
            val end = start - if (major) dp(7f) else dp(3.5f)
            strokePaint.strokeWidth = if (major) dp(1.4f) else dp(1f)
            strokePaint.color = if (major) Color.parseColor("#42596E") else Color.parseColor("#2C3D4F")
            val sinA = sin(rad).toFloat()
            val cosA = cos(rad).toFloat()
            canvas.drawLine(
                cx + sinA * start, cy - cosA * start,
                cx + sinA * end, cy - cosA * end,
                strokePaint
            )
        }
    }

    private fun drawCardinals(canvas: Canvas, cx: Float, cy: Float, outer: Float) {
        val labels = arrayOf("북", "동", "남", "서")
        val radius = outer * 0.87f
        smallPaint.textAlign = Paint.Align.CENTER
        smallPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        smallPaint.textSize = dp(10f)
        for (index in labels.indices) {
            val rad = Math.toRadians((index * 90.0 - shownHeading))
            val x = cx + sin(rad).toFloat() * radius
            val y = cy - cos(rad).toFloat() * radius
            smallPaint.color = if (index == 0) accentColor else Color.parseColor("#7C8FA5")
            canvas.drawText(labels[index], x, y + smallPaint.textSize * 0.36f, smallPaint)
        }
        smallPaint.typeface = Typeface.DEFAULT
    }

    /** A small triangle at the top: the top of the dial is where the phone points. */
    private fun drawPhoneMarker(canvas: Canvas, cx: Float, cy: Float, outer: Float) {
        val top = cy - outer - dp(2f)
        val halfWidth = dp(4.5f)
        val height = dp(6.5f)
        phoneMarker.reset()
        phoneMarker.moveTo(cx, top + dp(1f))
        phoneMarker.lineTo(cx - halfWidth, top - height + dp(4f))
        phoneMarker.lineTo(cx + halfWidth, top - height + dp(4f))
        phoneMarker.close()
        fillPaint.color = if (compassAvailable) textPrimary else textMuted
        canvas.drawPath(phoneMarker, fillPaint)
    }

    private fun drawBlips(canvas: Canvas, cx: Float, cy: Float, outer: Float) {
        hits.clear()
        placedLabels.clear()
        // The "나" marker and its 0 m label own the middle: no device label goes there.
        placedLabels.add(
            RectF(cx - dp(15f), cy - dp(15f), cx + dp(15f), cy + dp(25f))
        )

        val angles = angles()
        val sorted = blips.sortedWith(compareBy({ it.metres }, { it.signal.address }))
        for (blip in sorted) {
            val angle = CompassGeometry.screenAngle(blip.bearing ?: angles[blip.signal.address] ?: 0f, shownHeading)
            val rad = Math.toRadians(angle.toDouble())
            val sinA = sin(rad).toFloat()
            val cosA = cos(rad).toFloat()
            // A device at 0.2 m would land on the "나" marker; keep every dot outside it.
            val radius = max(outer * NearbyRadarMath.radiusFraction(blip.metres), dp(20f))
            val x = cx + sinA * radius
            val y = cy - cosA * radius
            val color = if (blip.signal.source == SignalSource.WIFI) wifiColor else bleColor

            hits.add(Hit(blip.signal, x, y))

            if (blip.measured) {
                // A ray from me to the dot: the direction was actually measured.
                val from = outer * 0.10f
                strokePaint.strokeWidth = dp(1.3f)
                strokePaint.color = withAlpha(color, 120)
                canvas.drawLine(cx + sinA * from, cy - cosA * from, x, y, strokePaint)

                fillPaint.color = color
                fillPaint.setShadowLayer(dp(7f), 0f, 0f, withAlpha(color, 150))
                canvas.drawCircle(x, y, dp(4.8f), fillPaint)
                fillPaint.clearShadowLayer()
                strokePaint.strokeWidth = dp(1.2f)
                strokePaint.color = textPrimary
                canvas.drawCircle(x, y, dp(4.8f), strokePaint)
            } else {
                // No direction measured: hollow and dashed, never solid.
                dashPaint.strokeWidth = dp(1.6f)
                dashPaint.color = withAlpha(color, 210)
                canvas.drawCircle(x, y, dp(4.8f), dashPaint)
                fillPaint.color = withAlpha(color, 120)
                canvas.drawCircle(x, y, dp(1.5f), fillPaint)
            }

            drawLabel(canvas, blip, x, y, sinA, cosA, color)
        }
    }

    /**
     * Name, type and distance beside the dot.
     *
     * A busy radar has to stay readable: a label is only drawn where it does not cover
     * another label or the "나" marker, and when two lines do not fit the device gets a
     * compact one-line label instead of no label at all.
     */
    private fun drawLabel(
        canvas: Canvas,
        blip: NearbyBlip,
        x: Float,
        y: Float,
        sinA: Float,
        cosA: Float,
        color: Int
    ) {
        val name = NearbyRadarMath.shortName(blip.signal.title, 12)
        val type = DeviceType.of(blip.signal)
        val distance = NearbyRadarMath.metreText(blip.metres)

        namePaint.textSize = dp(9.5f)
        namePaint.color = color
        typePaint.textSize = dp(8.5f)
        typePaint.color = Color.parseColor("#93A4B8")

        val twoLines = listOf(namePaint to name, typePaint to "${type.label} · $distance")
        if (placeLabel(canvas, twoLines, x, y, sinA, cosA, color)) return
        if (placeLabel(canvas, listOf(namePaint to "$name · $distance"), x, y, sinA, cosA, color)) return
        // Crowded corner: at least say how far it is, right under the dot.
        drawDistanceUnderDot(canvas, distance, x, y, color)
    }

    private fun drawDistanceUnderDot(canvas: Canvas, text: String, x: Float, y: Float, color: Int) {
        smallPaint.textSize = dp(8.5f)
        smallPaint.textAlign = Paint.Align.CENTER
        smallPaint.color = color
        val metrics = smallPaint.fontMetrics
        val boxWidth = smallPaint.measureText(text) + dp(2f)
        val boxHeight = metrics.descent - metrics.ascent
        val left = (x - boxWidth / 2f).coerceIn(dp(1f), max(dp(1f), width - boxWidth - dp(1f)))
        val top = (y + dp(9f)).coerceIn(dp(1f), max(dp(1f), height - boxHeight - dp(1f)))
        rect.set(left, top, left + boxWidth, top + boxHeight)
        // No room for a box here either: the distance still goes under its dot. A short
        // number over another label's edge is better than a dot nobody can identify.
        placedLabels.add(RectF(rect))
        canvas.drawText(text, left + boxWidth / 2f, top - metrics.ascent, smallPaint)
    }

    private fun placeLabel(
        canvas: Canvas,
        lines: List<Pair<Paint, String>>,
        x: Float,
        y: Float,
        sinA: Float,
        cosA: Float,
        color: Int
    ): Boolean {
        val padH = dp(4.5f)
        val padV = dp(2.5f)
        val gap = dp(7.5f)
        val heights = lines.map { (paint, _) -> paint.fontMetrics.let { it.descent - it.ascent } }
        val boxW = lines.maxOf { (paint, text) -> paint.measureText(text) } + padH * 2
        val boxH = heights.sum() + (lines.size - 1) * dp(1f) + padV * 2
        if (boxW > width) return false

        val maxLeft = max(dp(1f), width - boxW - dp(1f))
        val maxTop = max(dp(1f), height - boxH - dp(1f))
        val natural = if (sinA >= 0f) 1f else -1f

        for (side in floatArrayOf(natural, -natural)) {
            for (offset in floatArrayOf(dp(12f), dp(32f), dp(54f))) {
                val anchorX = x + side * max(kotlin.math.abs(sinA) * offset, gap)
                val anchorY = y - cosA * offset
                var left = if (side > 0f) anchorX else anchorX - boxW
                left = left.coerceIn(dp(1f), maxLeft)
                val top = (anchorY - boxH / 2f).coerceIn(dp(1f), maxTop)
                rect.set(left, top, left + boxW, top + boxH)

                var clash = false
                for (placed in placedLabels) {
                    if (RectF.intersects(placed, rect)) {
                        clash = true
                        break
                    }
                }
                if (clash) continue

                placedLabels.add(RectF(rect))
                fillPaint.color = labelBg
                canvas.drawRoundRect(rect, dp(4f), dp(4f), fillPaint)
                strokePaint.strokeWidth = dp(0.8f)
                strokePaint.color = withAlpha(color, 90)
                canvas.drawRoundRect(rect, dp(4f), dp(4f), strokePaint)

                var baseline = top + padV
                for (index in lines.indices) {
                    val paint = lines[index].first
                    baseline -= paint.fontMetrics.ascent
                    canvas.drawText(lines[index].second, left + padH, baseline, paint)
                    baseline += paint.fontMetrics.descent + dp(1f)
                }
                return true
            }
        }
        return false
    }

    private fun drawCenter(canvas: Canvas, cx: Float, cy: Float) {
        fillPaint.color = Color.parseColor("#0D141D")
        canvas.drawCircle(cx, cy, dp(9f), fillPaint)
        strokePaint.strokeWidth = dp(2f)
        strokePaint.color = accentColor
        canvas.drawCircle(cx, cy, dp(9f), strokePaint)

        smallPaint.color = textPrimary
        smallPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        smallPaint.textSize = dp(9.5f)
        val metrics = smallPaint.fontMetrics
        canvas.drawText("나", cx, cy - (metrics.ascent + metrics.descent) / 2f, smallPaint)

        smallPaint.typeface = Typeface.DEFAULT
        smallPaint.textSize = dp(8.5f)
        smallPaint.color = textMuted
        canvas.drawText("0m", cx, cy + dp(19f), smallPaint)
    }

    private fun drawEmptyText(canvas: Canvas, cx: Float, cy: Float, outer: Float) {
        smallPaint.textAlign = Paint.Align.CENTER
        smallPaint.typeface = Typeface.DEFAULT
        smallPaint.textSize = dp(11f)
        smallPaint.color = Color.parseColor("#93A4B8")
        val first = if (scanning) "10m 안에 잡힌 기기가 없어요" else "아직 스캔을 시작하지 않았어요"
        val second = if (scanning) "조금 기다리면 주변 기기가 원 안에 나타나요" else "‘스캔 시작’을 누르면 10m 안을 살펴요"
        canvas.drawText(first, cx, cy + outer * 0.46f, smallPaint)
        smallPaint.textSize = dp(9.5f)
        smallPaint.color = textMuted
        canvas.drawText(second, cx, cy + outer * 0.46f + dp(15f), smallPaint)
    }

    // ------------------------------------------------------------------- touch

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
                val hit = nearestBlip(event.x, event.y)
                if (hit != null) {
                    performClick()
                    onBlipClick?.invoke(hit)
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun nearestBlip(x: Float, y: Float): Signal? {
        var best: Signal? = null
        var bestDistance = dp(26f)
        for (hit in hits) {
            val dx = hit.x - x
            val dy = hit.y - y
            val distance = kotlin.math.sqrt(dx * dx + dy * dy)
            if (distance <= bestDistance) {
                bestDistance = distance
                best = hit.signal
            }
        }
        return best
    }

    // ------------------------------------------------------------------- helpers

    private fun angles(): Map<String, Float> {
        val signature = blips.joinToString(",") { it.signal.address }
        if (signature != anglesSignature) {
            anglesSignature = signature
            placeholderAngles = NearbyRadarMath.placeholderAngles(blips.map { it.signal.address })
        }
        return placeholderAngles
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    private fun signedDifference(from: Float, to: Float): Float {
        var diff = NearbyRadarMath.normalize(to) - NearbyRadarMath.normalize(from)
        if (diff > 180f) diff -= 360f
        if (diff < -180f) diff += 360f
        return diff
    }

    private data class Hit(val signal: Signal, val x: Float, val y: Float)
}
