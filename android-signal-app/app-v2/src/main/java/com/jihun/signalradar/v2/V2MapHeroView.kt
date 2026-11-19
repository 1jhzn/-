package com.jihun.signalradar.v2

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.jihun.signalradar.R

/**
 * The pastel top area: a soft mint/sage wash with a few faint map curves, wide rings and the
 * blue "my location" dot.
 *
 * It is drawn by hand rather than loaded from a picture so it stays crisp on every phone and
 * costs nothing to ship, and it never pretends to be a real map: the curves and rings are
 * decoration behind the signal card or the 10 m radar.
 */
class V2MapHeroView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    private val background = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val top = color(R.color.v2_hero_top)
    private val bottom = color(R.color.v2_hero_bottom)
    private val ring = color(R.color.v2_hero_ring)
    private val haze = color(R.color.v2_hero_haze)
    private val blue = color(R.color.v2_blue)

    private val curve = Path()

    private fun color(id: Int): Int = ContextCompat.getColor(context, id)
    private fun dp(value: Float): Float = value * density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // ---- the wash: mint at the top, a slightly deeper sage at the bottom
        background.shader = LinearGradient(
            0f, 0f, 0f, h, top, bottom, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, background)
        background.shader = null

        // ---- a wide, very faint glow behind where the signal card / radar sits
        background.shader = RadialGradient(
            w * 0.5f, h * 0.62f, w * 0.75f,
            intArrayOf(withAlpha(Color.WHITE, 110), withAlpha(Color.WHITE, 0)),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, background)
        background.shader = null

        // ---- wide decorative rings, off to one side so they read as map circles
        line.style = Paint.Style.STROKE
        // Kept low and to the corners: the 10 m radar draws its own rings in the middle of
        // this area, and a decoration that crosses them reads as a second radar.
        line.strokeWidth = dp(1.1f)
        line.color = withAlpha(ring, 60)
        canvas.drawCircle(w * 0.06f, h * 1.10f, w * 0.36f, line)
        line.color = withAlpha(ring, 45)
        canvas.drawCircle(w * 0.06f, h * 1.10f, w * 0.22f, line)
        line.color = withAlpha(ring, 40)
        canvas.drawCircle(w * 1.02f, h * 1.16f, w * 0.30f, line)

        // ---- two soft "roads", the way a map sketch would have them
        line.strokeWidth = dp(10f)
        line.color = withAlpha(haze, 160)
        curve.reset()
        curve.moveTo(-w * 0.05f, h * 0.30f)
        curve.cubicTo(w * 0.30f, h * 0.16f, w * 0.55f, h * 0.44f, w * 1.05f, h * 0.26f)
        canvas.drawPath(curve, line)

        line.strokeWidth = dp(7f)
        line.color = withAlpha(haze, 120)
        curve.reset()
        curve.moveTo(-w * 0.05f, h * 0.88f)
        curve.cubicTo(w * 0.25f, h * 0.72f, w * 0.62f, h * 0.98f, w * 1.05f, h * 0.78f)
        canvas.drawPath(curve, line)

        // ---- my location: a blue dot with a soft halo and a white collar
        val dotX = w * 0.5f
        val dotY = h * 0.56f
        fill.shader = RadialGradient(
            dotX, dotY, dp(46f),
            intArrayOf(withAlpha(blue, 80), withAlpha(blue, 0)),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(dotX, dotY, dp(46f), fill)
        fill.shader = null

        fill.color = Color.WHITE
        canvas.drawCircle(dotX, dotY, dp(9.5f), fill)
        fill.color = blue
        canvas.drawCircle(dotX, dotY, dp(6.5f), fill)
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
}
