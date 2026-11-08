package dev.foldfx

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.View

/**
 * Draws inside the moving "glass pane" window. The window itself blurs what's behind it
 * (blurBehindRadius); this view adds a soft frosted leading edge so the pane doesn't have
 * a hard cut line, plus a faint veil so it still reads as glass when blur is unavailable.
 */
class FoldEffectView(context: Context) : View(context) {

    /** 0..1 overall strength */
    var strength = 0f
    /** true if the edge facing the hinge (the moving edge) is on the left of this pane */
    var leadingEdgeLeft = true
    /** When the system can't blur, draw a much stronger frost so the effect is still visible. */
    var blurActive = true

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val k = strength.coerceIn(0f, 1f)
        if (k < 0.003f || width < 2) return
        val w = width.toFloat()
        val h = height.toFloat()
        val boost = if (blurActive) 1f else 2.2f

        // Faint glass veil
        canvas.drawColor(Color.argb((k * 22 * boost).toInt().coerceAtMost(160), 255, 255, 255))

        // Bright, soft leading edge where the pane meets the sharp content
        val edgeW = (w * 0.35f).coerceAtMost(resources.displayMetrics.density * 140)
        val x0 = if (leadingEdgeLeft) 0f else w
        val x1 = if (leadingEdgeLeft) edgeW else w - edgeW
        paint.shader = LinearGradient(
            x0, 0f, x1, 0f,
            intArrayOf(white(k * 0.55f * boost), white(k * 0.18f * boost), Color.TRANSPARENT),
            floatArrayOf(0f, 0.35f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(minOf(x0, x1), 0f, maxOf(x0, x1), h, paint)
        paint.shader = null
    }

    private fun white(a: Float) = Color.argb((a.coerceIn(0f, 1f) * 255).toInt(), 255, 255, 255)
}
