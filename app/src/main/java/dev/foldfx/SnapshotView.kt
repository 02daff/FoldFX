package dev.foldfx

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.max

/** Draws the screen snapshot, scaled to fill the current screen (center-crop). */
class SnapshotView(context: Context) : View(context) {

    var bitmap: Bitmap? = null
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val dst = RectF()

    override fun onDraw(canvas: Canvas) {
        val b = bitmap ?: return
        if (b.isRecycled || width == 0 || height == 0) return
        val s = max(width.toFloat() / b.width, height.toFloat() / b.height)
        val dw = b.width * s
        val dh = b.height * s
        dst.set((width - dw) / 2f, (height - dh) / 2f, (width + dw) / 2f, (height + dh) / 2f)
        canvas.drawBitmap(b, null, dst, paint)
    }
}
