package dev.foldfx

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Draws the "frosted glass" part of the effect on top of the screen.
 * The real background blur comes from the window (blurBehindRadius) in FoldOverlayService;
 * this view adds the milky veil, the moving-half panel, a light sweep and a hinge shadow.
 *
 * intensity: 0 = invisible, 1 = strongest (middle of the fold)
 * progress:  0 = closed, 1 = fully open (from the hinge angle)
 */
class FoldEffectView(context: Context) : View(context) {

    var intensity = 0f
    var progress = 0f
    /** Which half of the inner screen physically swings. Flip it in the app if it looks wrong. */
    var movingSideRight = true

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val k = intensity.coerceIn(0f, 1f)
        if (k < 0.003f) return

        val w = width.toFloat()
        val h = height.toFloat()

        // Inner screen of the N5 is almost square; the cover screen is tall and narrow.
        val isInnerScreen = w > h * 0.75f

        // 1) Soft milky veil over everything
        canvas.drawColor(Color.argb((k * 55).toInt(), 255, 255, 255))

        // 2) The moving panel (half of the inner screen, or the whole cover screen)
        val hingeX: Float
        val edgeX: Float
        if (isInnerScreen) {
            hingeX = w / 2f
            edgeX = if (movingSideRight) w else 0f
        } else {
            hingeX = if (movingSideRight) 0f else w
            edgeX = if (movingSideRight) w else 0f
        }
        val panelLeft = min(hingeX, edgeX)
        val panelRight = max(hingeX, edgeX)

        paint.shader = LinearGradient(
            hingeX, 0f, edgeX, 0f,
            intArrayOf(white(k * 0.85f), white(k * 0.45f), white(k * 0.15f)),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRect(panelLeft, 0f, panelRight, h, paint)

        // 3) A light band that sweeps across the panel as the angle changes
        val bandX = hingeX + (edgeX - hingeX) * progress
        val bw = w * 0.14f
        paint.shader = LinearGradient(
            bandX - bw, 0f, bandX + bw, 0f,
            intArrayOf(Color.TRANSPARENT, white(k * 0.6f), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRect(max(panelLeft, bandX - bw), 0f, min(panelRight, bandX + bw), h, paint)

        // 4) Shadow along the hinge on the inner screen, to sell the bend
        if (isInnerScreen) {
            val sw = w * 0.06f
            paint.shader = LinearGradient(
                hingeX - sw, 0f, hingeX + sw, 0f,
                intArrayOf(Color.TRANSPARENT, Color.argb((k * 70).toInt(), 0, 0, 0), Color.TRANSPARENT),
                null, Shader.TileMode.CLAMP
            )
            canvas.drawRect(hingeX - sw, 0f, hingeX + sw, h, paint)
        }

        paint.shader = null
    }

    private fun white(alpha01: Float): Int =
        Color.argb((alpha01.coerceIn(0f, 1f) * 255).toInt(), 255, 255, 255)
}
