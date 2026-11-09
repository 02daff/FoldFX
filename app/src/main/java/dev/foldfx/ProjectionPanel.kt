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
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Full-screen effect for the big screen.
 *
 * All the fold math is done in the phone's NATURAL frame (portrait, hinge running top to
 * bottom). That frame is rotated to match the current screen rotation, so the effect lines up
 * with the physical hinge in portrait and landscape alike.
 *
 * The "UI" (screenshot, or a grey placeholder) is one picture fixed in space:
 *  - on the flat half it is drawn as-is,
 *  - on the swinging half it is drawn with a perspective projection that follows the hinge,
 *    with black void wherever the tilted panel extends past the picture.
 *
 * v = 0 at the outer edge of the flat half, 0.5 at the hinge, 1 at the outer edge of the
 * swinging half. Blur and darkness rise smoothly with v; the whole effect fades out toward the
 * flat half's outer edge.
 */
class ProjectionPanel(context: Context) : FrameLayout(context) {

    companion object {
        const val LAYERS = 6
        /** Glass mode: where the effect begins, a bit past the hinge into the flat half (hinge = 0.5). */
        const val GLASS_START = 0.38f
    }

    /** Current screen size (as the screen is now rotated). Changing it re-lays out the frame. */
    var screenW = 1
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }
    var screenH = 1
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }
    /** Display rotation: 0, 1, 2, 3 = 0°, 90°, 180°, 270° (Surface.ROTATION_*). */
    var rotation90 = 0
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }
    /** true: the left half (in natural portrait orientation) swings. */
    var movingLeft = true
    /** 0..1: how strongly the effect covers the screen (fades in off flat). */
    var coverage = 0f
    /** 0..1: how far the effect has swept in, from the flat side across the hinge to the edge. */
    var reveal = 1f

    /** Real screenshot (in current screen orientation); null = grey placeholder. */
    var snapshot: Bitmap? = null
        set(value) {
            field = value
            snapSmall?.recycle()
            snapSmall = value?.let {
                // Blurred layers don't need full resolution: half size is ~4x less work
                Bitmap.createScaledBitmap(it, (it.width / 2).coerceAtLeast(1), (it.height / 2).coerceAtLeast(1), true)
            }
            snapShader = value?.let { makeShader(it) }
            snapSmallShader = snapSmall?.let { makeShader(it) }
            snapShaderFor = -1
            snapSmallShaderFor = -1
        }
    private var snapSmall: Bitmap? = null
    private var snapShader: BitmapShader? = null
    private var snapSmallShader: BitmapShader? = null
    private var snapShaderFor = -1
    private var snapSmallShaderFor = -1
    private val snapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val shaderMatrix = Matrix()

    private fun makeShader(b: Bitmap): BitmapShader =
        if (Build.VERSION.SDK_INT >= 31) BitmapShader(b, Shader.TileMode.DECAL, Shader.TileMode.DECAL)
        else BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)

    /** Picture of the cover screen, shown on the swinging half right after opening. */
    var coverPic: Bitmap? = null
        set(value) {
            field = value
            coverShader = value?.let { makeShader(it) }
        }
    private var coverShader: BitmapShader? = null
    /** Glass-box mode: swinging half shows a frosted glass box over the wallpaper; other half untouched. */
    var glassMode = false
    var wallpaper: Bitmap? = null
        set(value) {
            field = value
            wallShader = value?.let { makeShader(it) }
            wallShaderFor = -1
            fogColor = value?.let { averageColor(it) } ?: Color.rgb(240, 242, 247)
        }
    // ---- star sparkles (glass mode) ----
    private class Star(val x: Float, val y: Float, val size: Float, val phase: Float, val speed: Float)

    private fun makeStars(n: Int, seed: Long): List<Star> {
        val r = java.util.Random(seed)
        return List(n) {
            Star(r.nextFloat(), r.nextFloat(), 0.8f + r.nextFloat() * r.nextFloat() * 3.7f,
                r.nextFloat() * 6.283f, 0.6f + r.nextFloat() * 1.6f)
        }
    }
    private val pictureStars = makeStars(90, 7L)    // drawn in the picture (move with the panel, get fogged)
    private val fogStars = makeStars(60, 42L)       // floating on top of the fog, across the whole box
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    /** Draws twinkling stars; xRange limits where they appear (0..1 of width). */
    private fun drawStars(canvas: Canvas, stars: List<Star>, w: Float, h: Float, alphaAt: (Float) -> Float) {
        val d = resources.displayMetrics.density
        val t = android.os.SystemClock.uptimeMillis() / 1000f
        for (st in stars) {
            val a = alphaAt(st.x)
            if (a <= 0.01f) continue
            val tw = 0.55f + 0.45f * kotlin.math.sin(t * st.speed * 2.4f + st.phase)
            val alpha = (a * tw).coerceIn(0f, 1f)
            val cx = st.x * w
            val cy = st.y * h
            val r = st.size * d
            starPaint.color = Color.rgb(120, 150, 255)
            starPaint.alpha = (alpha * 95).toInt()
            canvas.drawCircle(cx, cy, r * 1.9f, starPaint)          // soft blue glow (shows on white frost)
            starPaint.color = Color.WHITE
            starPaint.alpha = (alpha * 255).toInt()
            canvas.drawCircle(cx, cy, r * 0.65f, starPaint)         // bright core
            if (st.size > 1.8f) {
                // four-point sparkle for the bigger stars
                starPaint.color = Color.rgb(200, 215, 255)
                starPaint.alpha = (alpha * 230).toInt()
                starPaint.strokeWidth = r * 0.22f
                val l = r * 2.6f * tw
                canvas.drawLine(cx - l, cy, cx + l, cy, starPaint)
                canvas.drawLine(cx, cy - l, cx, cy + l, starPaint)
            }
        }
    }

    /** Average colour of the wallpaper, used as the fog colour. */
    private var fogColor = Color.rgb(240, 242, 247)

    private fun averageColor(b: Bitmap): Int {
        var r = 0L; var g = 0L; var bl = 0L; var n = 0
        val sx = (b.width / 16).coerceAtLeast(1)
        val sy = (b.height / 16).coerceAtLeast(1)
        var y = sy / 2
        while (y < b.height) {
            var x = sx / 2
            while (x < b.width) {
                val c = b.getPixel(x, y)
                r += (c shr 16) and 0xff; g += (c shr 8) and 0xff; bl += c and 0xff; n++
                x += sx
            }
            y += sy
        }
        if (n == 0) return Color.rgb(34, 30, 46)
        // white frost with just a hint of the wallpaper's colour
        val mix = 0.78f
        return Color.rgb(
            ((r / n) * (1 - mix) + 245 * mix).toInt(),
            ((g / n) * (1 - mix) + 246 * mix).toInt(),
            ((bl / n) * (1 - mix) + 250 * mix).toInt()
        )
    }
    private var wallShader: BitmapShader? = null
    private var wallShaderFor = -1
    private val wallMatrix = Matrix()
    private val glassFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 160, 160, 168) }
    private val glassFallback = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(226, 229, 236) }
    private val glassEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(150, 255, 255, 255)
    }

    /** true while opening from the cover screen (no fade to black). */
    var opening = false
    /** 0..1: how much of the cover picture is still showing (1 = only cover, 0 = gone). */
    var coverMix = 0f
    private val coverPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val coverMatrix = Matrix()

    /** Corner radius of the real screen, so the floating picture has the same rounded corners. */
    var cornerRadius = 0f

    /** screen -> natural frame, for drawing the screenshot inside the natural frame */
    private val screenToNatural = Matrix()

    private val projMatrix = Matrix()
    private val src = FloatArray(8)
    private val dst = FloatArray(8)
    private var curCos = 1f
    private var curSin = 0f
    private var curZc = 1f

    private val frame = NaturalFrame(context)

    init {
        addView(frame)
    }

    /** Natural (portrait) size of the screen. */
    private val natW get() = if (rotation90 % 2 == 1) screenH else screenW
    private val natH get() = if (rotation90 % 2 == 1) screenW else screenH

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        frame.measure(
            MeasureSpec.makeMeasureSpec(natW, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(natH, MeasureSpec.EXACTLY)
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val h = b - t
        val left = (w - natW) / 2
        val top = (h - natH) / 2
        frame.layout(left, top, left + natW, top + natH)
        frame.pivotX = natW / 2f
        frame.pivotY = natH / 2f
        // natural frame -> screen: 90° display rotation shows the natural frame turned 90° CCW
        frame.rotation = when (rotation90) {
            1 -> -90f
            2 -> 180f
            3 -> 90f
            else -> 0f
        }
        // screen -> natural (inverse), used to draw the screenshot in the natural frame
        screenToNatural.reset()
        when (rotation90) {
            1 -> { screenToNatural.postRotate(90f); screenToNatural.postTranslate(natW.toFloat(), 0f) }
            2 -> { screenToNatural.postRotate(180f); screenToNatural.postTranslate(natW.toFloat(), natH.toFloat()) }
            3 -> { screenToNatural.postRotate(-90f); screenToNatural.postTranslate(0f, natH.toFloat()) }
        }
    }

    fun update(fold: Float, strength: Float, maxBlur: Float, dimMax: Float, lock: Float) {
        // No stretching: the panel only gets the perspective of coming toward you, so the flat
        // picture looks smaller inside the moving frame and black void opens around it.
        // Keeps moving smoothly all the way to 90°, then the panel fades to dark.
        computeMatrix(fold.coerceAtMost(90f), lock.coerceIn(0f, 1f))
        // Fade to black between hinge readings 80° and 60° (fold 100° -> 120°).
        // Not when opening from the cover screen: then the cover picture should be seen.
        val away = if (opening) 0f else smoothstep((fold - 100f) / 20f)
        frame.update(strength, maxBlur, dimMax + (1f - dimMax) * away, strength, away)
    }

    private fun smoothstep(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /**
     * Perspective of the swinging panel coming toward the viewer, WITHOUT the sideways
     * stretch: panel point (d, y) shows the picture at (d * t, cy + (y - cy) * t), where
     * t = zc / (zc - d * sin(fold)) grows toward the outer edge. The picture therefore shrinks
     * toward the hinge inside the moving frame, and black void appears around it.
     */
    private fun computeMatrix(foldDeg: Float, amount: Float) {
        val w = natW.toFloat()
        val h = natH.toFloat().coerceAtLeast(1f)
        val half = w / 2f
        val s = (sin(Math.toRadians(foldDeg.toDouble())).toFloat() * amount).coerceIn(0f, 1f)
        val zc = 1.2f * w                                   // viewer distance (closer = stronger effect)
        curCos = 1f; curSin = s; curZc = zc
        val cy = h / 2f
        val ds = floatArrayOf(0f, half, half, 0f)
        val ys = floatArrayOf(0f, 0f, h, h)
        val sign = if (movingLeft) -1f else 1f
        for (k in 0 until 4) {
            val d = ds[k]
            val y = ys[k]
            val t = zc / (zc - d * s).coerceAtLeast(1f)
            src[k * 2] = half + sign * d * t
            src[k * 2 + 1] = cy + t * (y - cy)
            dst[k * 2] = half + sign * d
            dst[k * 2 + 1] = y
        }
        projMatrix.setPolyToPoly(src, 0, dst, 0, 4)
    }

    /** v (0..1, see class doc) -> x in the natural frame */
    private fun natX(v: Float): Float = if (movingLeft) natW * (1f - v) else natW * v

    /** On-screen blur multiplier where the panel is seen at a steep angle (picture stretched). */
    private fun viewStretch(v: Float): Float {
        if (v <= 0.5f) return 1f
        val d = (v - 0.5f) * natW
        val denom = (curZc - d * curSin).coerceAtLeast(1f)
        val dxdd = abs(curCos) * curZc * curZc / (denom * denom)
        return 1f   // no stretching any more, so no extra blur needed
    }

    // =====================================================================
    // Natural frame: blur layers + dark glass + fade-out mask
    // =====================================================================

    private inner class NaturalFrame(context: Context) : FrameLayout(context) {
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

        fun update(strength: Float, maxBlur: Float, dimMax: Float, haze: Float, away: Float) {
            dimView.haze = haze
            dimView.away = away
            if (glassMode) {
                // Fog: all layers spread from the split to the far edge, blur growing to ~2x max
                val step = (1f - GLASS_START) / (LAYERS - 1)
                for (k in 0 until LAYERS) {
                    val vk = GLASS_START + k * step
                    val x = (vk - GLASS_START) / (1f - GLASS_START)
                    layers[k].set(maxBlur * 2.2f * strength * x.pow(1.1f), vk, step)
                }
            } else {
                val step = 1f / (LAYERS - 1)
                for (k in 0 until LAYERS) {
                    val vk = k * step
                    val base = maxBlur * strength * vk.pow(1.3f)
                    layers[k].set((base * viewStretch(vk)).coerceAtMost(maxBlur * 1.5f), vk, step)
                }
            }
            dimView.dim = dimMax * strength
            dimView.invalidate()
            invalidate()
        }

        override fun dispatchDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val save = canvas.saveLayer(0f, 0f, w, h, null)
            super.dispatchDraw(canvas)
            val c = coverage.coerceIn(0f, 1f)
            val stops = if (glassMode) floatArrayOf(0f, GLASS_START, 0.47f, 0.52f, 0.7f, 1f)
                else floatArrayOf(0f, 0.2f, 0.4f, 0.5f, 0.6f, 1f)
            // glass: soft lead-in on the flat side only, fully covered from just past the hinge
            val alphas = if (glassMode) floatArrayOf(0f, 0f, 0.65f, 1f, 1f, 1f)
                else floatArrayOf(0f, 0.12f, 0.55f, 0.85f, 1f, 1f)
            val colors = IntArray(stops.size) { Color.argb((alphas[it] * c * 255).roundToInt(), 0, 0, 0) }
            fadePaint.shader = LinearGradient(natX(0f), 0f, natX(1f), 0f, colors, stops, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, h, fadePaint)
            // Sweep: visible up to a front that moves from the flat side out to the folding edge
            val rv = reveal.coerceIn(0f, 1f)
            if (rv < 0.999f) {
                val feather = 0.12f
                val front = GLASS_START + (1f + feather - GLASS_START) * rv
                val x0 = natX((front - feather).coerceIn(0f, 1f))
                val x1 = natX(front.coerceIn(0f, 1f))
                fadePaint.shader = LinearGradient(x0, 0f, x1, 0f, Color.BLACK, Color.TRANSPARENT, Shader.TileMode.CLAMP)
                canvas.drawRect(0f, 0f, w, h, fadePaint)
            }
            canvas.restoreToCount(save)
        }
    }

    private inner class BlurLayer(context: Context, private val index: Int) : FrameLayout(context) {
        private val content = ContentView(context, index == 0)
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
            val xs = natX((vk - ramp).coerceAtLeast(0f))
            val xe = natX(vk)
            maskPaint.shader = LinearGradient(xs, 0f, xe, 0f, Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, h, maskPaint)
            canvas.restoreToCount(save)
        }
    }

    /** The picture: flat half as-is, swinging half projected, black void around it. */
    private inner class ContentView(context: Context, private val sharp: Boolean) : View(context) {
        private var blur = -1
        private val grey = Paint().apply { color = Color.rgb(128, 128, 132) }
        private val tile = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(170, 170, 176) }
        private val rect = RectF()

        fun setBlur(b: Float) {
            val r = (b * 2f).roundToInt()          // half-pixel steps, so the blur changes smoothly
            if (r != blur && Build.VERSION.SDK_INT >= 31) {
                blur = r
                val rad = r / 2f
                setRenderEffect(
                    if (rad > 0.25f) RenderEffect.createBlurEffect(rad, rad, Shader.TileMode.CLAMP)
                    else null
                )
            }
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val half = w / 2f
            if (glassMode) {
                // flat half (only the part past the hinge shows, the mask fades it in)
                canvas.save()
                if (movingLeft) canvas.clipRect(half, 0f, w, h) else canvas.clipRect(0f, 0f, half, h)
                drawGlassBox(canvas)
                canvas.restore()
                // swinging half: black void, then the glass box over the wallpaper, in perspective
                canvas.save()
                if (movingLeft) canvas.clipRect(0f, 0f, half, h) else canvas.clipRect(half, 0f, w, h)
                canvas.drawColor(Color.BLACK)
                canvas.concat(this@ProjectionPanel.projMatrix)
                drawGlassBox(canvas)
                canvas.restore()
                return
            }
            // flat half
            canvas.save()
            if (movingLeft) canvas.clipRect(half, 0f, w, h) else canvas.clipRect(0f, 0f, half, h)
            drawPicture(canvas)
            canvas.restore()
            // swinging half
            canvas.save()
            if (movingLeft) canvas.clipRect(0f, 0f, half, h) else canvas.clipRect(half, 0f, w, h)
            canvas.drawColor(Color.BLACK)
            canvas.concat(this@ProjectionPanel.projMatrix)
            drawPicture(canvas)
            drawCoverPicture(canvas, half, h)
            canvas.restore()
        }

        /** Wallpaper (center-crop over the whole screen) with a translucent grey glass box on top. */
        private fun drawGlassBox(canvas: Canvas) {
            val nw = natW.toFloat()
            val nh = natH.toFloat()
            rect.set(0f, 0f, nw, nh)
            val r = cornerRadius
            val wp = wallpaper
            val shader = wallShader
            if (wp != null && shader != null && !wp.isRecycled) {
                val key = natW * 31 + natH
                if (wallShaderFor != key) {
                    val sc = maxOf(nw / wp.width, nh / wp.height)
                    wallMatrix.setScale(sc, sc)
                    wallMatrix.postTranslate((nw - wp.width * sc) / 2f, (nh - wp.height * sc) / 2f)
                    shader.setLocalMatrix(wallMatrix)
                    wallShaderFor = key
                }
                snapPaint.shader = shader
                canvas.drawRoundRect(rect, r, r, snapPaint)
            } else {
                canvas.drawRoundRect(rect, r, r, glassFallback)   // no wallpaper: deep dark glass
            }
            // no tint or border: the wallpaper itself melts into blur and darkness
            // star sparkles in the picture (fainter on a real wallpaper, brighter on the placeholder)
            val base = if (wp != null) 0.75f else 1f
            drawStars(canvas, pictureStars, nw, nh) { base }
        }

        /** The cover screen's picture, filling the swinging half (center-crop), fading out. */
        private fun drawCoverPicture(canvas: Canvas, half: Float, h: Float) {
            val bmp = coverPic ?: return
            val shader = coverShader ?: return
            val mix = coverMix.coerceIn(0f, 1f)
            if (mix < 0.01f || bmp.isRecycled) return
            val left = if (movingLeft) 0f else half
            rect.set(left, 0f, left + half, h)
            val sc = maxOf(half / bmp.width, h / bmp.height)
            val dx = left + (half - bmp.width * sc) / 2f
            val dy = (h - bmp.height * sc) / 2f
            coverMatrix.setScale(sc, sc)
            coverMatrix.postTranslate(dx, dy)
            shader.setLocalMatrix(coverMatrix)
            coverPaint.shader = shader
            coverPaint.alpha = (mix * 255).toInt()
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, coverPaint)
        }

        /** Draws the picture in natural-frame coordinates. */
        private fun drawPicture(canvas: Canvas) {
            val nw = natW.toFloat()
            val nh = natH.toFloat()
            val bmp = if (sharp) this@ProjectionPanel.snapshot else (snapSmall ?: this@ProjectionPanel.snapshot)
            val shader = if (sharp || snapSmall == null) snapShader else snapSmallShader
            if (bmp != null && shader != null && !bmp.isRecycled) {
                val key = screenW * 31 + screenH
                val done = if (shader === snapShader) snapShaderFor == key else snapSmallShaderFor == key
                if (!done) {
                    shaderMatrix.setScale(screenW.toFloat() / bmp.width, screenH.toFloat() / bmp.height)
                    shader.setLocalMatrix(shaderMatrix)
                    if (shader === snapShader) snapShaderFor = key else snapSmallShaderFor = key
                }
                snapPaint.shader = shader
                val r = cornerRadius
                canvas.save()
                canvas.concat(screenToNatural)          // screenshot is in screen orientation
                rect.set(0f, 0f, screenW.toFloat(), screenH.toFloat())
                canvas.drawRoundRect(rect, r, r, snapPaint)
                canvas.restore()
                return
            }
            // No screenshot (yet): draw nothing, so the real screen shows through
            if (bmp == null) return
            rect.set(0f, 0f, nw, nh)
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, grey)
            val cols = 8
            val pad = nw * 0.04f
            val size = (nw - pad * (cols + 1)) / cols
            val r = size * 0.22f
            var y = nh * 0.08f
            while (y + size < nh * 0.95f) {
                for (i in 0 until cols) {
                    val x = pad + i * (size + pad)
                    rect.set(x, y, x + size, y + size)
                    canvas.drawRoundRect(rect, r, r, tile)
                }
                y += size + pad
            }
        }
    }

    /** Dark glass: smooth darkening rising toward the swinging edge. */
    private inner class DimView(context: Context) : View(context) {
        var dim = 0f
        var haze = 0f
        var away = 0f
        private val paint = Paint()

        override fun onDraw(canvas: Canvas) {
            if (glassMode) {
                drawGlassHaze(canvas)
                return
            }
            if (dim < 0.003f) return
            val stops = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)
            // As dim approaches 1 the gradient flattens into solid black over the swinging half
            val flat = ((dim - 0.7f) / 0.3f).coerceIn(0f, 1f)
            val colors = IntArray(stops.size) {
                val g = stops[it].pow(1.4f)
                val swing = if (stops[it] >= 0.5f) 1f else stops[it] * 2f    // full on swinging half
                val a = dim * (g + (swing - g) * flat)
                Color.argb((a * 255).roundToInt().coerceIn(0, 255), 0, 0, 0)
            }
            paint.shader = LinearGradient(natX(0f), 0f, natX(1f), 0f, colors, stops, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        }

        /** Dark tint deepening toward the far edge; full black as the panel turns away. */
        private fun drawGlassHaze(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val hz = haze.coerceIn(0f, 1f)
            if (hz > 0.003f) {
                val stops = floatArrayOf(0f, GLASS_START, 0.55f, 0.75f, 1f)
                // fog in the wallpaper's own colour: flattens contrast so shapes lose their edges
                val fa = floatArrayOf(0f, 0f, 0.30f, 0.55f, 0.78f)   // milky frost thickening outward
                val fc = fogColor
                val fogColors = IntArray(stops.size) {
                    Color.argb((fa[it] * hz * 255).roundToInt(), Color.red(fc), Color.green(fc), Color.blue(fc))
                }
                paint.shader = LinearGradient(natX(0f), 0f, natX(1f), 0f, fogColors, stops, Shader.TileMode.CLAMP)
                canvas.drawRect(0f, 0f, w, h, paint)
                // then a lighter dark tint deepening toward the far edge
                val a = floatArrayOf(0f, 0f, 0f, 0f, 0f)   // no dark tint: white frosted glass
                val colors = IntArray(stops.size) { Color.argb((a[it] * hz * 255).roundToInt(), 16, 12, 26) }
                paint.shader = LinearGradient(natX(0f), 0f, natX(1f), 0f, colors, stops, Shader.TileMode.CLAMP)
                canvas.drawRect(0f, 0f, w, h, paint)
                // sparkles floating in the fog: x here is the screen position, mapped to v
                drawStars(canvas, fogStars, w, h) { xf ->
                    val v = if (movingLeft) 1f - xf else xf
                    ((v - GLASS_START) / 0.12f).coerceIn(0f, 1f) * (0.4f + 0.6f * hz)
                }
                invalidate()   // keep twinkling while the fog is visible
            }
            val aw = away.coerceIn(0f, 1f)
            if (aw > 0.003f) {
                val stops = floatArrayOf(0f, 0.45f, 0.55f, 1f)
                val a = floatArrayOf(0f, 0f, aw, aw)
                val colors = IntArray(stops.size) { Color.argb((a[it] * 255).roundToInt(), 0, 0, 0) }
                paint.shader = LinearGradient(natX(0f), 0f, natX(1f), 0f, colors, stops, Shader.TileMode.CLAMP)
                canvas.drawRect(0f, 0f, w, h, paint)
            }
        }
    }
}
