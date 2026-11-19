package com.jihun.signalradar.v2

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.jihun.signalradar.direction.SignalDirectionTracker
import com.jihun.signalradar.nearby.NearbyRadarMath
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The glowing orb of the 신호 찾기 screen, drawn on a Canvas.
 *
 * - The orb grows and brightens with the level ([scale]): small and dim far away, filling
 *   the screen when the signal is in your hand.
 * - Two rings keep expanding out of it like a pulse, so a screen that is not changing still
 *   looks alive.
 * - The twelve directions the sweep measured sit around it as arcs; the strongest one gets a
 *   chevron at the rim. The chevron is drawn relative to where the phone points, so turning
 *   towards the signal brings the chevron to the top ([aligned]).
 * - [emerald] shifts the orb from its cool white-blue to the emerald of the arrival screen.
 */
class V2OrbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** 0.32 … 1.0, from the smoothed level. */
    var scale: Float = OrbFinderState.MIN_SCALE
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** 0 → dark screen colours, 1 → the emerald arrival. */
    var emerald: Float = 0f
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** 0 … 1, driven by the screen's ticker; moves the pulse rings. */
    var pulse: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    var heading: Float = 0f
        set(value) {
            val next = NearbyRadarMath.normalize(value)
            if (next != field) {
                field = next
                invalidate()
            }
        }

    var peakAzimuth: Float? = null
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var aligned: Boolean = false
        set(value) {
            if (field != value) {
                field = value
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

    /** One value per direction, NaN where that direction has not been measured. */
    var sectorValues: FloatArray? = null
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val rect = RectF()
    private val arrow = android.graphics.Path()

    private val cool = Color.parseColor("#7FE3FF")
    private val emeraldColor = Color.parseColor("#02B95A")
    private val emeraldLight = Color.parseColor("#8CFFC0")
    private val inkDark = Color.parseColor("#0B1113")

    private fun dp(value: Float): Float = value * density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val cx = w / 2f
        val cy = h * 0.50f
        val outer = min(w, h) / 2f - dp(6f)
        if (outer <= 0f) return

        val glow = if (emerald > 0.5f) emeraldLight else cool
        drawSectorArcs(canvas, cx, cy, outer)
        drawPulse(canvas, cx, cy, outer, glow)
        val orbRadius = outer * 0.42f * scale
        drawOrb(canvas, cx, cy, orbRadius, glow)
        drawDirection(canvas, cx, cy, outer, orbRadius)
    }

    /** The twelve measured directions: a rim of short arcs, brighter where it was louder. */
    private fun drawSectorArcs(canvas: Canvas, cx: Float, cy: Float, outer: Float) {
        val values = sectorValues ?: return
        val radius = outer * 0.94f
        val arcRect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        var strongest = Float.NEGATIVE_INFINITY
        for (value in values) if (!value.isNaN() && value > strongest) strongest = value
        if (strongest == Float.NEGATIVE_INFINITY) return

        stroke.strokeWidth = dp(3.4f)
        stroke.strokeCap = Paint.Cap.ROUND
        for (index in values.indices) {
            val value = values[index]
            if (value.isNaN()) continue
            // -95 dBm is invisible, -40 dBm fills the arc.
            val strength = ((value + 95f) / 55f).coerceIn(0.12f, 1f)
            val center = SignalDirectionTracker.sectorCenter(index)
            val start = center - heading - SignalDirectionTracker.SECTOR_DEG / 2f + 2.5f
            stroke.color = withAlpha(if (emerald > 0.5f) emeraldLight else cool, (60 + 170 * strength).toInt())
            canvas.drawArc(arcRect, start - 90f, SignalDirectionTracker.SECTOR_DEG - 5f, false, stroke)
        }
        stroke.strokeCap = Paint.Cap.BUTT
    }

    private fun drawPulse(canvas: Canvas, cx: Float, cy: Float, outer: Float, glow: Int) {
        for (index in 0..1) {
            val phase = (pulse + index * 0.5f) % 1f
            val radius = outer * (0.30f + 0.62f * phase) * (0.55f + 0.45f * scale)
            val alpha = ((1f - phase) * 70 * (0.4f + 0.6f * scale)).toInt()
            if (alpha <= 0) continue
            stroke.strokeWidth = dp(1.6f)
            stroke.color = withAlpha(glow, alpha)
            canvas.drawCircle(cx, cy, radius, stroke)
        }
    }

    private fun drawOrb(canvas: Canvas, cx: Float, cy: Float, radius: Float, glow: Int) {
        if (radius <= 0f) return

        // the halo around the orb
        fill.shader = RadialGradient(
            cx, cy, radius * 2.1f,
            intArrayOf(withAlpha(glow, 110), withAlpha(glow, 26), withAlpha(glow, 0)),
            floatArrayOf(0.32f, 0.62f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius * 2.1f, fill)
        fill.shader = null

        // the sphere itself: bright in the middle, deep at the rim. Once the screen is
        // emerald the rim turns emerald too, otherwise the sphere would read as a dark hole
        // punched into a bright green screen.
        val core = if (emerald > 0.5f) Color.WHITE else Color.parseColor("#EAFBFF")
        val rim = blend(inkDark, Color.parseColor("#019A4A"), emerald)
        fill.shader = RadialGradient(
            cx, cy - radius * 0.18f, radius * 1.15f,
            intArrayOf(core, withAlpha(glow, 235), withAlpha(rim, 200)),
            floatArrayOf(0f, 0.62f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius, fill)
        fill.shader = null

        stroke.strokeWidth = dp(1.2f)
        stroke.color = withAlpha(glow, if (aligned) 235 else 120)
        canvas.drawCircle(cx, cy, radius, stroke)

        // a soft highlight, so the orb reads as a sphere and not as a flat circle
        fill.color = withAlpha(Color.WHITE, if (emerald > 0.5f) 190 else 140)
        canvas.drawCircle(cx - radius * 0.26f, cy - radius * 0.30f, radius * 0.20f, fill)
    }

    /** The chevron at the rim: which way the signal was loudest, relative to the phone. */
    private fun drawDirection(canvas: Canvas, cx: Float, cy: Float, outer: Float, orbRadius: Float) {
        val peak = peakAzimuth
        if (!compassAvailable) {
            small.textSize = dp(9.5f)
            small.color = withAlpha(Color.WHITE, 170)
            canvas.drawText("방향 센서 없음", cx, cy + outer * 0.90f, small)
            return
        }
        if (peak == null) return

        val angle = peak - heading
        val rad = Math.toRadians(angle.toDouble())
        val sinA = sin(rad).toFloat()
        val cosA = cos(rad).toFloat()

        val from = orbRadius + dp(10f)
        val to = outer * 0.90f
        stroke.strokeWidth = dp(if (aligned) 3.2f else 2f)
        stroke.color = withAlpha(Color.WHITE, if (aligned) 235 else 150)
        canvas.drawLine(cx + sinA * from, cy - cosA * from, cx + sinA * to, cy - cosA * to, stroke)

        // arrowhead
        val tipX = cx + sinA * to
        val tipY = cy - cosA * to
        val wing = dp(6.5f)
        val leftX = tipX - sinA * dp(9f) + cosA * wing
        val leftY = tipY + cosA * dp(9f) + sinA * wing
        val rightX = tipX - sinA * dp(9f) - cosA * wing
        val rightY = tipY + cosA * dp(9f) - sinA * wing
        fill.color = withAlpha(Color.WHITE, if (aligned) 245 else 190)
        arrow.reset()
        arrow.moveTo(tipX, tipY)
        arrow.lineTo(leftX, leftY)
        arrow.lineTo(rightX, rightY)
        arrow.close()
        canvas.drawPath(arrow, fill)

        if (aligned) {
            stroke.strokeWidth = dp(1.4f)
            stroke.color = withAlpha(Color.WHITE, 90)
            rect.set(cx - outer * 0.98f, cy - outer * 0.98f, cx + outer * 0.98f, cy + outer * 0.98f)
            canvas.drawArc(rect, -90f - dp(14f), dp(28f), false, stroke)
        }
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    private fun blend(from: Int, to: Int, t: Float): Int {
        val f = t.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(from) + (Color.red(to) - Color.red(from)) * f).toInt(),
            (Color.green(from) + (Color.green(to) - Color.green(from)) * f).toInt(),
            (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * f).toInt()
        )
    }
}
