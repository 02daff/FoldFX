package dev.foldfx

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.View
import android.widget.FrameLayout
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Covers the swinging half of the big screen and draws the "UI" (a grey placeholder for now)
 * as a picture fixed in space: the picture lies in the plane of the flat half, and for every
 * point of the rotated panel we show what a viewer in front of the phone would see through it.
 * The mapping between the two planes is a perspective transform, so one Matrix does it.
 *
 * The panel is split into vertical strips so blur and darkness can grow toward the free edge.
 */
class ProjectionPanel(context: Context, private val strips: Int) : FrameLayout(context) {

    /** Full-screen size (both halves); the content is drawn in this coordinate space. */
    var screenW = 1
    var screenH = 1
    /** true: the left half swings (hinge on this view's right edge). */
    var movingLeft = true

    private val matrix = Matrix()
    private val src = FloatArray(8)
    private val dst = FloatArray(8)
    private val stripViews = ArrayList<StripView>()

    init {
        for (i in 0 until strips) {
            val v = StripView(context)
            stripViews.add(v)
            addView(v)
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val h = b - t
        for (i in 0 until strips) {
            val x0 = (w * i / strips.toFloat()).roundToInt()
            val x1 = (w * (i + 1) / strips.toFloat()).roundToInt()
            stripViews[i].layout(x0, 0, x1, h)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        for (i in 0 until strips) {
            val sw = (w * (i + 1) / strips.toFloat()).roundToInt() - (w * i / strips.toFloat()).roundToInt()
            stripViews[i].measure(
                MeasureSpec.makeMeasureSpec(sw, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
            )
        }
    }

    /**
     * fold: how far the panel has swung from flat, in degrees (0 = flat, 90 = upright).
     * blur/dim: per strip, index 0 at the hinge.
     */
    fun update(fold: Float, blur: FloatArray, dim: FloatArray) {
        computeMatrix(fold)
        for (i in 0 until strips) {
            // strip index counted from the hinge -> view slot
            val slot = if (movingLeft) strips - 1 - i else i
            stripViews[slot].set(blur[i], dim[i])
        }
        for (v in stripViews) v.invalidate()
    }

    private fun computeMatrix(foldDeg: Float) {
        val w = width.toFloat().coerceAtLeast(1f)          // panel width = half the screen
        val h = height.toFloat().coerceAtLeast(1f)
        val phi = Math.toRadians(foldDeg.toDouble())
        val c = cos(phi).toFloat()
        val s = sin(phi).toFloat()
        val zc = 2.5f * screenW                              // viewer distance in front of the phone
        val cy = h / 2f

        // Panel corners: d = distance from hinge, y = height
        val ds = floatArrayOf(0f, w, w, 0f)
        val ys = floatArrayOf(0f, 0f, h, h)
        for (k in 0 until 4) {
            val d = ds[k]
            val y = ys[k]
            val t = zc / (zc - d * s).coerceAtLeast(1f)
            val xi = d * c * t                               // where this panel point lands in the picture
            val yi = cy + t * (y - cy)
            // picture coordinates (full-screen space)
            src[k * 2] = if (movingLeft) w - xi else w + xi
            src[k * 2 + 1] = yi
            // this view's coordinates
            dst[k * 2] = if (movingLeft) w - d else d
            dst[k * 2 + 1] = y
        }
        matrix.setPolyToPoly(src, 0, dst, 0, 4)
    }

    private inner class StripView(context: Context) : View(context) {
        private var blur = -1
        private var dim = 0f
        private val grey = Paint().apply { color = Color.rgb(128, 128, 132) }
        private val tile = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(170, 170, 176) }
        private val rect = RectF()

        fun set(b: Float, d: Float) {
            dim = d
            val r = (b / 2f).roundToInt() * 2
            if (r != blur && Build.VERSION.SDK_INT >= 31) {
                blur = r
                setRenderEffect(
                    if (r > 0) RenderEffect.createBlurEffect(r.toFloat(), r.toFloat(), Shader.TileMode.CLAMP)
                    else null
                )
            }
        }

        override fun onDraw(canvas: Canvas) {
            canvas.save()
            canvas.translate(-left.toFloat(), 0f)    // into panel coordinates
            canvas.concat(matrix)                     // into picture coordinates
            drawPlaceholderUi(canvas)
            canvas.restore()
            if (dim > 0.002f) canvas.drawColor(Color.argb((dim * 255).roundToInt().coerceIn(0, 255), 0, 0, 0))
        }

        /** Grey stand-in for the screen content: a grey box with a grid of lighter tiles. */
        private fun drawPlaceholderUi(canvas: Canvas) {
            val sw = screenW.toFloat()
            val sh = screenH.toFloat()
            // extend beyond the screen so stretched views never show empty edges
            canvas.drawRect(-sw, -sh, sw * 2f, sh * 2f, grey)
            val cols = 8
            val pad = sw * 0.04f
            val size = (sw - pad * (cols + 1)) / cols
            val r = size * 0.22f
            var y = sh * 0.08f
            while (y + size < sh * 0.95f) {
                for (i in 0 until cols) {
                    val x = pad + i * (size + pad)
                    rect.set(x, y, x + size, y + size)
                    canvas.drawRoundRect(rect, r, r, tile)
                }
                y += size + pad
            }
        }
    }
}
