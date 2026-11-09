package dev.foldfx

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.View
import android.widget.FrameLayout
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Covers the swinging half of the big screen and draws the "UI" (a grey placeholder for now)
 * as a picture fixed in space (perspective projection that follows the hinge angle).
 *
 * Smooth progressive blur: several full-size copies of the picture are stacked, each blurred
 * more than the one below, and each faded in with a soft gradient further from the hinge.
 * The result is a continuous blur from sharp at the hinge to strongest at the outer edge,
 * with no visible strips.
 */
class ProjectionPanel(context: Context) : FrameLayout(context) {

    companion object {
        const val LAYERS = 7
    }

    var screenW = 1
    var screenH = 1
    /** true: the left half swings (hinge on this view's right edge). */
    var movingLeft = true

    private val projMatrix = Matrix()
    private val src = FloatArray(8)
    private val dst = FloatArray(8)
    private val layers = ArrayList<BlurLayer>()
    private val dimView = DimView(context)

    init {
        val m = LayoutParams.MATCH_PARENT
        for (k in 0 until LAYERS) {
            val l = BlurLayer(context, k)
            layers.add(l)
            addView(l, LayoutParams(m, m))
        }
        addView(dimView, LayoutParams(m, m))
    }

    /**
     * fold: degrees the panel has swung from flat. strength: 0..1 overall effect.
     * maxBlur: blur at the outer edge at full strength. dimMax: darkness at the outer edge.
     */
    fun update(fold: Float, strength: Float, maxBlur: Float, dimMax: Float) {
        computeMatrix(fold)
        val step = 1f / (LAYERS - 1)
        for (k in 0 until LAYERS) {
            val xk = k * step                                   // 0 = hinge, 1 = outer edge
            val b = maxBlur * strength * xk.pow(0.75f)          // rises quickly away from the hinge
            layers[k].set(b, xk, step)
        }
        dimView.dim = dimMax * strength
        dimView.invalidate()
    }

    /** distance from hinge (0..1) -> x in this view */
    private fun viewX(dist: Float): Float = if (movingLeft) width * (1f - dist) else width * dist

    private fun computeMatrix(foldDeg: Float) {
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        val phi = Math.toRadians(foldDeg.toDouble())
        val c = cos(phi).toFloat()
        val s = sin(phi).toFloat()
        val zc = 2.5f * screenW
        val cy = h / 2f
        val ds = floatArrayOf(0f, w, w, 0f)
        val ys = floatArrayOf(0f, 0f, h, h)
        for (k in 0 until 4) {
            val d = ds[k]
            val y = ys[k]
            val t = zc / (zc - d * s).coerceAtLeast(1f)
            val xi = d * c * t
            val yi = cy + t * (y - cy)
            src[k * 2] = if (movingLeft) w - xi else w + xi
            src[k * 2 + 1] = yi
            dst[k * 2] = if (movingLeft) w - d else d
            dst[k * 2 + 1] = y
        }
        projMatrix.setPolyToPoly(src, 0, dst, 0, 4)
    }

    // ---- one blurred copy, faded in with a gradient mask ----

    private inner class BlurLayer(context: Context, private val index: Int) : FrameLayout(context) {
        private val content = ContentView(context)
        private var xk = 0f
        private var ramp = 0f
        private val maskPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }

        init {
            addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            setWillNotDraw(false)
        }

        fun set(blur: Float, xk: Float, ramp: Float) {
            this.xk = xk
            this.ramp = ramp
            content.setBlur(blur)
            content.invalidate()
            invalidate()
        }

        override fun dispatchDraw(canvas: Canvas) {
            if (index == 0) {          // bottom layer: full, sharpest
                super.dispatchDraw(canvas)
                return
            }
            val w = width.toFloat()
            val h = height.toFloat()
            val save = canvas.saveLayer(0f, 0f, w, h, null)
            super.dispatchDraw(canvas)
            // transparent before (xk - ramp), fully visible from xk outward
            val xs = viewX((xk - ramp).coerceAtLeast(0f))
            val xe = viewX(xk)
            maskPaint.shader = LinearGradient(xs, 0f, xe, 0f, Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, h, maskPaint)
            canvas.restoreToCount(save)
        }
    }

    // ---- the projected picture ----

    private inner class ContentView(context: Context) : View(context) {
        private var blur = -1
        private val grey = Paint().apply { color = Color.rgb(128, 128, 132) }
        private val tile = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(170, 170, 176) }
        private val rect = RectF()

        fun setBlur(b: Float) {
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
            canvas.concat(this@ProjectionPanel.projMatrix)
            drawPlaceholderUi(canvas)
            canvas.restore()
        }

        /** Grey stand-in for the screen content: a grey box with a grid of lighter tiles. */
        private fun drawPlaceholderUi(canvas: Canvas) {
            val sw = screenW.toFloat()
            val sh = screenH.toFloat()
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

    // ---- dark glass: smooth darkening toward the outer edge ----

    private inner class DimView(context: Context) : View(context) {
        var dim = 0f
        private val paint = Paint()

        override fun onDraw(canvas: Canvas) {
            if (dim < 0.003f) return
            val a0 = (dim * 0.30f * 255).roundToInt().coerceIn(0, 255)
            val a1 = (dim * 255).roundToInt().coerceIn(0, 255)
            paint.shader = LinearGradient(
                viewX(0f), 0f, viewX(1f), 0f,
                Color.argb(a0, 0, 0, 0), Color.argb(a1, 0, 0, 0), Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        }
    }
}
