package dev.foldfx

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View

/**
 * A frosted "glass" pane drawn on top of the (window-blurred) screen.
 *
 * shape = 0: the pane is the size and shape of the cover screen, centred
 * shape = 1: the pane covers the whole screen
 * Growing shape 0 -> 1 on the big screen gives the impression of the cover screen
 * expanding into the inner screen.
 */
class GlassView(context: Context) : View(context) {

    var strength = 0f
    var shape = 1f
    /** Cover screen aspect ratio (width / height). Find N5 cover is about 1140 x 2616. */
    var coverAspect = 1140f / 2616f

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val k = strength.coerceIn(0f, 1f)
        if (k < 0.003f) return
        val w = width.toFloat()
        val h = height.toFloat()
        val d = resources.displayMetrics.density

        // Rect of the pane: from a centred cover-shaped card to the full screen
        val s = shape.coerceIn(0f, 1f)
        val cardH = h * 0.86f
        val cardW = (cardH * coverAspect).coerceAtMost(w * 0.9f)
        val pw = cardW + (w - cardW) * s
        val ph = cardH + (h - cardH) * s
        rect.set((w - pw) / 2f, (h - ph) / 2f, (w + pw) / 2f, (h + ph) / 2f)
        val radius = 28f * d * (1f - s)

        // Soft milky glass with a light sheen from the top-left
        fillPaint.shader = LinearGradient(
            rect.left, rect.top, rect.right, rect.bottom,
            intArrayOf(white(0.42f * k), white(0.22f * k), white(0.32f * k)),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(rect, radius, radius, fillPaint)

        // Thin bright edge so the pane reads as glass while it's a card
        if (s < 0.98f) {
            edgePaint.strokeWidth = 1.5f * d
            edgePaint.color = white(0.7f * k * (1f - s))
            canvas.drawRoundRect(rect, radius, radius, edgePaint)
        }
    }

    private fun white(a: Float) = Color.argb((a.coerceIn(0f, 1f) * 255).toInt(), 255, 255, 255)
}
