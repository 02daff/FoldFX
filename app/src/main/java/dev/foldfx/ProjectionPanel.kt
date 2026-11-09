package dev.foldfx

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
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
 * Full-screen effect for the big screen.
 *
 * The "UI" (grey placeholder for now) is one picture fixed in space:
 *  - on the flat half it is drawn as-is,
 *  - on the swinging half it is drawn with a perspective projection that follows the hinge,
 * and both meet exactly at the hinge line, so the picture is continuous.
 *
 * Everything is driven by one coordinate v across the whole screen:
 *   v = 0 at the outer edge of the flat half, 0.5 at the hinge, 1 at the outer edge of the
 *   swinging half.
 * Blur and darkness rise smoothly with v, and the whole effect fades out toward the flat
 * half's outer edge, so there is no line at the hinge.
 */
class ProjectionPanel(context: Context) : FrameLayout(context) {

    companion object {
        const val LAYERS = 9
    }

    var screenW = 1
    var screenH = 1
    /** true: the left half swings. */
    var movingLeft = true
    /** 0..1: how strongly the effect covers the screen (fades in off flat). */
    var coverage = 0f

    /** Real screenshot to project; null = grey placeholder. */
    var snapshot: Bitmap? = null
        set(value) {
            field = value
            snapShader = value?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
            snapShaderFor = -1
        }
    private var snapShader: BitmapShader? = null
    private var snapShaderFor = -1
    private val snapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val shaderMatrix = Matrix()

    // projection parameters of the current frame (for viewing-angle blur)
    private var curCos = 1f
    private var curSin = 0f
    private var curZc = 1f

    private val projMatrix = Matrix()
    private val src = FloatArray(8)
    private val dst = FloatArray(8)
    private val layers = ArrayList<BlurLayer>()
    private val dimView = DimView(context)
    private val fadePaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }

    init {
        val m = LayoutParams.MATCH_PARENT
        for (k in 0 until LAYERS) {
            val l = BlurLayer(context, k)
            layers.add(l)
            addView(l, LayoutParams(m, m))
        }
        addView(dimView, LayoutParams(m, m))
        setWillNotDraw(false)
    }

    /**
     * lock: 0..1, how much the content stays fixed in space (1 = fully fixed, which stretches a
     * lot; small values keep the content close to its normal scale, like the iPhone).
     */
    fun update(fold: Float, strength: Float, maxBlur: Float, dimMax: Float, lock: Float) {
        // Partial lock, and never more than 50° of compensation (max stretch about 1.5x)
        computeMatrix((fold * lock.coerceIn(0f, 1f)).coerceAtMost(50f))
        val step = 1f / (LAYERS - 1)
        for (k in 0 until LAYERS) {
            val vk = k * step
            val base = maxBlur * strength * vk.pow(1.3f)
            layers[k].set((base * viewStretch(vk)).coerceAtMost(maxBlur * 3f), vk, step)
        }
        dimView.dim = dimMax * strength
        dimView.invalidate()
        invalidate()
    }

    /**
     * How much the picture is stretched on the panel at position v, as seen from the viewer.
     * Blur is defined on the picture, so where the panel turns away from you (picture stretched
     * on the screen) the on-screen blur must be larger. 1 on the flat half.
     */
    private fun viewStretch(v: Float): Float {
        if (v <= 0.5f) return 1f
        val half = width / 2f
        val d = (v - 0.5f) * 2f * half
        val denom = (curZc - d * curSin).coerceAtLeast(1f)
        val dxdd = kotlin.math.abs(curCos) * curZc * curZc / (denom * denom)   // picture px per panel px
        return (1f / dxdd.coerceAtLeast(0.3f)).coerceIn(1f, 3.3f)
    }

    /** v (0..1 across the screen, see class doc) -> x in this view */
    private fun viewX(v: Float): Float = if (movingLeft) width * (1f - v) else width * v

    /** Fade the whole effect out toward the flat half's outer edge. */
    override fun dispatchDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val save = canvas.saveLayer(0f, 0f, w, h, null)
        super.dispatchDraw(canvas)
        val c = coverage.coerceIn(0f, 1f)
        val stops = floatArrayOf(0f, 0.2f, 0.4f, 0.5f, 0.6f, 1f)
        val alphas = floatArrayOf(0f, 0.12f, 0.55f, 0.85f, 1f, 1f)
        val colors = IntArray(stops.size) { Color.argb((alphas[it] * c * 255).roundToInt(), 0, 0, 0) }
        fadePaint.shader = LinearGradient(viewX(0f), 0f, viewX(1f), 0f, colors, stops, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, fadePaint)
        canvas.restoreToCount(save)
    }

    private fun computeMatrix(foldDeg: Float) {
        val half = width / 2f
        val h = height.toFloat().coerceAtLeast(1f)
        val phi = Math.toRadians(foldDeg.toDouble())
        val c = cos(phi).toFloat()
        val s = sin(phi).toFloat()
        val zc = 2.5f * screenW
        curCos = c; curSin = s; curZc = zc
        val cy = h / 2f
        val ds = floatArrayOf(0f, half, half, 0f)
        val ys = floatArrayOf(0f, 0f, h, h)
        val sign = if (movingLeft) -1f else 1f
        for (k in 0 until 4) {
            val d = ds[k]
            val y = ys[k]
            val t = zc / (zc - d * s).coerceAtLeast(1f)
            val xi = d * c * t
            val yi = cy + t * (y - cy)
            src[k * 2] = half + sign * xi        // picture point seen through this panel point
            src[k * 2 + 1] = yi
            dst[k * 2] = half + sign * d         // the panel point on screen
            dst[k * 2 + 1] = y
        }
        projMatrix.setPolyToPoly(src, 0, dst, 0, 4)
    }

    // ---- one blurred copy, faded in with a gradient mask ----

    private inner class BlurLayer(context: Context, private val index: Int) : FrameLayout(context) {
        private val content = ContentView(context)
        private var vk = 0f
        private var ramp = 0f
        private val maskPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }

        init {
            addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            setWillNotDraw(false)
        }

        fun set(blur: Float, vk: Float, ramp: Float) {
            this.vk = vk
            this.ramp = ramp
            content.setBlur(blur)
            content.invalidate()
            invalidate()
        }

        override fun dispatchDraw(canvas: Canvas) {
            if (index == 0) {
                super.dispatchDraw(canvas)
                return
            }
            val w = width.toFloat()
            val h = height.toFloat()
            val save = canvas.saveLayer(0f, 0f, w, h, null)
            super.dispatchDraw(canvas)
            val xs = viewX((vk - ramp).coerceAtLeast(0f))
            val xe = viewX(vk)
            maskPaint.shader = LinearGradient(xs, 0f, xe, 0f, Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, h, maskPaint)
            canvas.restoreToCount(save)
        }
    }

    // ---- the picture: flat half as-is, swinging half projected ----

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
            val w = width.toFloat()
            val h = height.toFloat()
            val half = w / 2f
            // flat half
            canvas.save()
            if (movingLeft) canvas.clipRect(half, 0f, w, h) else canvas.clipRect(0f, 0f, half, h)
            drawPlaceholderUi(canvas)
            canvas.restore()
            // swinging half
            canvas.save()
            if (movingLeft) canvas.clipRect(0f, 0f, half, h) else canvas.clipRect(half, 0f, w, h)
            canvas.concat(this@ProjectionPanel.projMatrix)
            drawPlaceholderUi(canvas)
            canvas.restore()
        }

        /** The screenshot (edges extended), or a grey stand-in when there is none. */
        private fun drawPlaceholderUi(canvas: Canvas) {
            val sw = screenW.toFloat()
            val sh = screenH.toFloat()
            val bmp = this@ProjectionPanel.snapshot
            val shader = this@ProjectionPanel.snapShader
            if (bmp != null && shader != null && !bmp.isRecycled) {
                val key = screenW * 31 + screenH
                if (snapShaderFor != key) {
                    shaderMatrix.setScale(sw / bmp.width, sh / bmp.height)
                    shader.setLocalMatrix(shaderMatrix)
                    snapShaderFor = key
                }
                snapPaint.shader = shader
                canvas.drawRect(-sw, -sh, sw * 2f, sh * 2f, snapPaint)
                return
            }
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

    // ---- dark glass: smooth darkening rising toward the swinging edge ----

    private inner class DimView(context: Context) : View(context) {
        var dim = 0f
        private val paint = Paint()

        override fun onDraw(canvas: Canvas) {
            if (dim < 0.003f) return
            val stops = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)
            val colors = IntArray(stops.size) {
                Color.argb((dim * stops[it].pow(1.4f) * 255).roundToInt().coerceIn(0, 255), 0, 0, 0)
            }
            paint.shader = LinearGradient(viewX(0f), 0f, viewX(1f), 0f, colors, stops, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        }
    }
}
