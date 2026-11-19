package com.jihun.signalradar.v2

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
import com.jihun.signalradar.nearby.NearbyBlip
import com.jihun.signalradar.nearby.NearbyRadarMath
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The bright 10 m radar of the 내 주변 tab: a white disc on the pastel top area.
 *
 * - The middle is me (a blue dot, like 나의 찾기); rings sit at 2 m, 5 m and 10 m.
 * - The dial turns with the phone, so the top of the circle is what the phone is pointing
 *   at and 북 turns around it; 북 itself is always drawn in blue.
 * - Bluetooth dots are blue, Wi-Fi dots are green. A device whose direction was measured by
 *   a hand-held sweep sits at that bearing with a ray from me; everything else keeps a
 *   placeholder angle and is drawn hollow, exactly as the v1 radar did, so nobody mistakes
 *   one for a measured direction.
 *
 * This is the same information as the dark radar of 전파 감지기 (내 주변), redrawn for the
 * bright screen rather than restyled with a filter.
 */
class V2RadarDialView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

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

    var onBlipClick: ((Signal) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private var shownHeading = 0f

    private var anglesSignature: String? = null
    private var placeholderAngles: Map<String, Float> = emptyMap()

    private val hits = ArrayList<Hit>()
    private val placedLabels = ArrayList<RectF>()
    private val rect = RectF()

    private val blue = color(R.color.v2_blue)
    private val green = color(R.color.v2_emerald)
    private val ink = color(R.color.v2_ink)
    private val inkSoft = color(R.color.v2_ink_soft)
    private val inkFaint = color(R.color.v2_ink_faint)
    private val ringColor = color(R.color.v2_hero_ring)
    private val sheet = color(R.color.v2_sheet)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(dp(2.2f), dp(2.0f)), 0f)
    }
    private val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.LEFT
    }
    private val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val phoneMarker = Path()
    private val ringFractions = floatArrayOf(
        2f / NearbyRadarMath.MAX_METRES,
        5f / NearbyRadarMath.MAX_METRES,
        1f
    )
    private val ringLabels = arrayOf("2m", "5m", "10m")

    init {
        isClickable = true
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    private fun color(id: Int): Int = ContextCompat.getColor(context, id)
    private fun dp(value: Float): Float = value * density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val cx = w / 2f
        // A little above the middle: the legend and heading labels sit under the disc.
        val cy = h * 0.47f
        // The heading pill sits under the disc and the legend above it, so the disc keeps
        // clear of both instead of running under them.
        val outer = min(w * 0.45f, h * 0.42f)
        if (outer <= 0f) return

        shownHeading = NearbyRadarMath.normalize(
            shownHeading + signedDifference(shownHeading, heading) * 0.22f
        )

        drawBase(canvas, cx, cy, outer)
        drawRings(canvas, cx, cy, outer)
        drawTicks(canvas, cx, cy, outer)
        drawCardinals(canvas, cx, cy, outer)
        drawBlips(canvas, cx, cy, outer)
        drawCenter(canvas, cx, cy)
        drawPhoneMarker(canvas, cx, cy, outer)
        if (blips.isEmpty()) drawEmptyText(canvas, cx, cy, outer)
    }

    private fun drawBase(canvas: Canvas, cx: Float, cy: Float, outer: Float) {
        // A soft shadow makes the disc read as a card floating on the pastel area instead of
        // a set of faint lines over it.
        fill.color = withAlpha(Color.WHITE, 255)
        fill.setShadowLayer(dp(12f), 0f, dp(3f), withAlpha(ink, 40))
        canvas.drawCircle(cx, cy, outer, fill)
        fill.clearShadowLayer()

        fill.shader = RadialGradient(
            cx, cy, outer,
            intArrayOf(withAlpha(Color.WHITE, 255), withAlpha(Color.WHITE, 235)),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, outer, fill)
        fill.shader = null

        stroke.strokeWidth = dp(2f)
        stroke.color = withAlpha(ringColor, 255)
        canvas.drawCircle(cx, cy, outer, stroke)
    }

    private fun drawRings(canvas: Canvas, cx: Float, cy: Float, outer: Float) {
        val rings = ringFractions
        val labels = ringLabels
        smallPaint.textSize = dp(9f)
        smallPaint.textAlign = Paint.Align.CENTER
        smallPaint.color = inkFaint
        for (index in rings.indices) {
            val radius = outer * rings[index]
            val last = index == rings.size - 1
            stroke.strokeWidth = if (last) dp(1.2f) else dp(0.9f)
            stroke.color = if (last) withAlpha(ringColor, 220) else withAlpha(ringColor, 130)
            canvas.drawCircle(cx, cy, radius, stroke)
            // The metre label sits on the south-west diagonal: the top of the dial carries
            // the phone marker and the north mark, the east side carries 동.
            val rad = Math.toRadians((214.0 - shownHeading))
            val lx = cx + sin(rad).toFloat() * radius * 0.86f
            val ly = cy - cos(rad).toFloat() * radius * 0.86f
            canvas.drawText(labels[index], lx, ly + smallPaint.textSize * 0.36f, smallPaint)
        }
    }

    private fun drawTicks(canvas: Canvas, cx: Float, cy: Float, outer: Float) {
        for (degree in 0 until 360 step 10) {
            val rad = Math.toRadians((degree - shownHeading).toDouble())
            val major = degree % 30 == 0
            val start = outer - dp(1f)
            val end = start - if (major) dp(6f) else dp(3f)
            stroke.strokeWidth = if (major) dp(1.2f) else dp(0.8f)
            stroke.color = if (major) withAlpha(ringColor, 230) else withAlpha(ringColor, 140)
            val sinA = sin(rad).toFloat()
            val cosA = cos(rad).toFloat()
            canvas.drawLine(
                cx + sinA * start, cy - cosA * start,
                cx + sinA * end, cy - cosA * end,
                stroke
            )
        }
    }

    private fun drawCardinals(canvas: Canvas, cx: Float, cy: Float, outer: Float) {
        // N/E/S/W like the compass on a phone: the north mark is the one that has to be
        // found at a glance, so it is blue and the other three stay quiet.
        val labels = arrayOf("N", "E", "S", "W")
        val radius = outer * 0.82f
        smallPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        smallPaint.textSize = dp(10.5f)
        for (index in labels.indices) {
            val rad = Math.toRadians((index * 90.0 - shownHeading))
            val x = cx + sin(rad).toFloat() * radius
            val y = cy - cos(rad).toFloat() * radius
            smallPaint.color = if (index == 0) blue else withAlpha(inkFaint, 230)
            canvas.drawText(labels[index], x, y + smallPaint.textSize * 0.36f, smallPaint)
        }
        smallPaint.typeface = Typeface.DEFAULT
    }

    private fun drawPhoneMarker(canvas: Canvas, cx: Float, cy: Float, outer: Float) {
        val top = cy - outer - dp(1f)
        val half = dp(5f)
        val height = dp(7f)
        phoneMarker.reset()
        phoneMarker.moveTo(cx, top + dp(1f))
        phoneMarker.lineTo(cx - half, top - height + dp(4f))
        phoneMarker.lineTo(cx + half, top - height + dp(4f))
        phoneMarker.close()
        fill.color = if (compassAvailable) blue else inkFaint
        canvas.drawPath(phoneMarker, fill)
    }

    private fun drawBlips(canvas: Canvas, cx: Float, cy: Float, outer: Float) {
        hits.clear()
        placedLabels.clear()
        placedLabels.add(RectF(cx - dp(18f), cy - dp(18f), cx + dp(18f), cy + dp(26f)))

        val angles = angles()
        val sorted = blips.sortedWith(compareBy({ it.metres }, { it.signal.address }))
        for (blip in sorted) {
            val angle = CompassGeometry.screenAngle(
                blip.bearing ?: angles[blip.signal.address] ?: 0f,
                shownHeading
            )
            val rad = Math.toRadians(angle.toDouble())
            val sinA = sin(rad).toFloat()
            val cosA = cos(rad).toFloat()
            val radius = max(outer * NearbyRadarMath.radiusFraction(blip.metres), dp(18f))
            val x = cx + sinA * radius
            val y = cy - cosA * radius
            val color = if (blip.signal.source == SignalSource.WIFI) green else blue

            hits.add(Hit(blip.signal, x, y))

            if (blip.measured) {
                val from = outer * 0.10f
                stroke.strokeWidth = dp(1.2f)
                stroke.color = withAlpha(color, 110)
                canvas.drawLine(cx + sinA * from, cy - cosA * from, x, y, stroke)

                fill.color = color
                canvas.drawCircle(x, y, dp(5.6f), fill)
                stroke.strokeWidth = dp(1.8f)
                stroke.color = sheet
                canvas.drawCircle(x, y, dp(5.6f), stroke)
            } else {
                dash.strokeWidth = dp(1.6f)
                dash.color = withAlpha(color, 210)
                canvas.drawCircle(x, y, dp(5.6f), dash)
                fill.color = withAlpha(color, 130)
                canvas.drawCircle(x, y, dp(1.6f), fill)
            }

            drawLabel(canvas, blip, x, y, sinA, cosA, color)
        }
    }

    private fun drawLabel(
        canvas: Canvas,
        blip: NearbyBlip,
        x: Float,
        y: Float,
        sinA: Float,
        cosA: Float,
        color: Int
    ) {
        val distance = NearbyRadarMath.metreText(blip.metres)
        val name = NearbyRadarMath.shortName(blip.signal.title, 10)

        namePaint.textSize = dp(9.5f)
        namePaint.color = ink
        if (placeLabel(canvas, "$name · $distance", x, y, sinA, cosA, color)) return
        if (placeLabel(canvas, distance, x, y, sinA, cosA, color)) return
        val type = DeviceType.of(blip.signal)
        placeLabel(canvas, type.label, x, y, sinA, cosA, color)
    }

    private fun placeLabel(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        sinA: Float,
        cosA: Float,
        color: Int
    ): Boolean {
        val padH = dp(5f)
        val padV = dp(2.5f)
        val gap = dp(8f)
        val metrics = namePaint.fontMetrics
        val boxW = namePaint.measureText(text) + padH * 2
        val boxH = (metrics.descent - metrics.ascent) + padV * 2
        if (boxW > width) return false

        val maxLeft = max(dp(1f), width - boxW - dp(1f))
        val maxTop = max(dp(1f), height - boxH - dp(1f))
        val natural = if (sinA >= 0f) 1f else -1f

        for (side in floatArrayOf(natural, -natural)) {
            for (offset in floatArrayOf(dp(11f), dp(30f), dp(50f))) {
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
                // A short leader line ties the label to its dot, so a busy radar still
                // says which name belongs to which dot.
                stroke.strokeWidth = dp(1.1f)
                stroke.color = withAlpha(color, 165)
                canvas.drawLine(x, y, if (side > 0f) left else left + boxW, top + boxH / 2f, stroke)

                fill.color = withAlpha(sheet, 240)
                canvas.drawRoundRect(rect, dp(5f), dp(5f), fill)
                stroke.strokeWidth = dp(0.9f)
                stroke.color = withAlpha(color, 90)
                canvas.drawRoundRect(rect, dp(5f), dp(5f), stroke)
                canvas.drawText(
                    text,
                    left + padH,
                    top + padV - metrics.ascent,
                    namePaint
                )
                return true
            }
        }
        return false
    }

    /** Me: a blue dot with a white collar, the same marker the top area draws. */
    private fun drawCenter(canvas: Canvas, cx: Float, cy: Float) {
        fill.color = withAlpha(blue, 45)
        canvas.drawCircle(cx, cy, dp(15f), fill)
        fill.color = sheet
        canvas.drawCircle(cx, cy, dp(9f), fill)
        fill.color = blue
        canvas.drawCircle(cx, cy, dp(6.5f), fill)
        // No "0m" under the dot: it lands on the 2 m ring and reads as "2m0m". The blue dot
        // in the middle *is* me, the same marker the top area uses.
    }

    private fun drawEmptyText(canvas: Canvas, cx: Float, cy: Float, outer: Float) {
        smallPaint.textAlign = Paint.Align.CENTER
        smallPaint.typeface = Typeface.DEFAULT
        smallPaint.textSize = dp(10.5f)
        smallPaint.color = inkSoft
        val first = if (scanning) "10m 안에 잡힌 기기가 없어요" else "아직 스캔을 시작하지 않았어요"
        val second = if (scanning) "조금 기다리면 주변 기기가 원 안에 나타나요" else "‘스캔 시작’을 누르면 10m 안을 살펴요"
        canvas.drawText(first, cx, cy + outer * 0.55f, smallPaint)
        smallPaint.textSize = dp(9.5f)
        smallPaint.color = inkFaint
        canvas.drawText(second, cx, cy + outer * 0.55f + dp(14f), smallPaint)
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

    // ----------------------------------------------------------------- helpers

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
