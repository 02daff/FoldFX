package dev.foldfx

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Draws the screen snapshot.
 * fill = 0: the whole snapshot fits inside the screen (e.g. the narrow cover-screen picture
 *           shown at its own shape in the middle of the big screen)
 * fill = 1: the snapshot covers the whole screen (center-crop)
 */
class SnapshotView(context: Context) : View(context) {

    var bitmap: Bitmap? = null
        set(value) {
            field = value
            invalidate()
        }

    var fill = 1f
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val dst = RectF()

    override fun onDraw(canvas: Canvas) {
        val b = bitmap ?: return
        if (b.isRecycled || width == 0 || height == 0) return
        val sx = width.toFloat() / b.width
        val sy = height.toFloat() / b.height
        val fit = min(sx, sy)
        val crop = max(sx, sy)
        val s = fit + (crop - fit) * fill.coerceIn(0f, 1f)
        val dw = b.width * s
        val dh = b.height * s
        dst.set((width - dw) / 2f, (height - dh) / 2f, (width + dw) / 2f, (height + dh) / 2f)
        canvas.drawBitmap(b, null, dst, paint)
    }
}
