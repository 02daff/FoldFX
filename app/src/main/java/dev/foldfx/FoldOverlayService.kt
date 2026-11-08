package dev.foldfx

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.RenderEffect
import android.graphics.Shader
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * iPhone Duo-style fold animation.
 *
 * When the hinge starts moving, we grab one snapshot of the screen (MediaProjection),
 * cover the screen with it, and as the fold progresses the snapshot zooms, blurs and
 * dissolves through a frosted "glass" layer. The real screen underneath is blurred too and
 * sharpens back into view as the phone reaches fully open/closed.
 *
 * Without screen-capture permission it falls back to blur + frost only.
 */
class FoldOverlayService : Service(), SensorEventListener {

    companion object {
        const val ACTION_PREVIEW = "dev.foldfx.PREVIEW"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"

        @Volatile var running = false
            private set
        @Volatile var captureActive = false
            private set

        private val logLines = ArrayDeque<String>()
        private var logT0 = 0L

        fun log(msg: String) {
            val now = SystemClock.uptimeMillis()
            if (logT0 == 0L) logT0 = now
            synchronized(logLines) {
                logLines.addLast("%6.2fs  %s".format((now - logT0) / 1000f, msg))
                while (logLines.size > 40) logLines.removeFirst()
            }
        }

        fun logText(): String = synchronized(logLines) { logLines.joinToString("\n") }

        private const val CHANNEL = "foldfx"
        private const val HOLD_MS = 200L            // effect lingers this long after movement stops
        private const val START_THRESHOLD = 10f     // degrees of movement that start the effect
        private const val DISSOLVE_MS = 420f        // dissolve after the cover/inner screen switch
        private const val SNAPSHOT_TIMEOUT_MS = 160L
        private const val PREVIEW_MS = 1600L
    }

    private enum class State { IDLE, CAPTURING, ANIMATING }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private lateinit var sensors: SensorManager
    private var hinge: Sensor? = null

    // ---- capture ----
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var capW = 0
    private var capH = 0
    private var snapshot: Bitmap? = null

    // ---- overlay ----
    private var root: FrameLayout? = null
    private var snapView: SnapshotView? = null
    private var frostView: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var lastBlur = -1
    private var lastSnapBlur = -1

    // ---- motion ----
    private var state = State.IDLE
    private var hasReading = false
    private var restAngle = 180f
    private var targetAngle = 180f
    private var shownAngle = 180f
    private var startAngle = 180f
    private var lastMoveAt = 0L
    private var fade = 0f
    private var frameRunning = false
    private var preview = false
    private var animStartAt = 0L
    private var startW = 0
    private var startH = 0
    private var switchedAt = 0L
    private var lastLoggedSize = ""

    private val frame = Choreographer.FrameCallback { tick() }
    private val snapshotTimeout = Runnable {
        if (state == State.CAPTURING) {
            log("snapshot timed out (no frame)")
            pauseCapture()
            beginAnimation()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground(withCapture = false)

        wm = getSystemService(WindowManager::class.java)
        sensors = getSystemService(SensorManager::class.java)
        hinge = sensors.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)

        if (hinge == null || !Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        sensors.registerListener(this, hinge, SensorManager.SENSOR_DELAY_FASTEST)
        running = true
        log("service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PREVIEW) {
            startPreview()
            return START_NOT_STICKY
        }
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_DATA)
        }
        if (code == Activity.RESULT_OK && data != null) setupProjection(code, data)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        if (::sensors.isInitialized) sensors.unregisterListener(this)
        Choreographer.getInstance().removeFrameCallback(frame)
        main.removeCallbacks(snapshotTimeout)
        frameRunning = false
        detach()
        releaseCapture()
        snapshot?.recycle()
        snapshot = null
        super.onDestroy()
    }

    // =====================================================================
    // Screen capture
    // =====================================================================

    private fun setupProjection(code: Int, data: Intent) {
        releaseCapture()
        startAsForeground(withCapture = true)   // must happen before getMediaProjection on Android 14+
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val mp = runCatching { mpm.getMediaProjection(code, data) }.getOrNull() ?: return
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                log("screen capture STOPPED by system")
                main.post { releaseCapture() }
            }
        }, main)
        projection = mp
        log("screen capture granted")

        val (w, h) = captureSize()
        val r = newReader(w, h)
        virtualDisplay = runCatching {
            mp.createVirtualDisplay(
                "FoldFX", w, h, resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                null,       // paused: no surface until we need a snapshot
                null, main
            )
        }.getOrNull()
        if (virtualDisplay == null) {
            r.close()
            reader = null
        }
        captureActive = virtualDisplay != null
    }

    private fun newReader(w: Int, h: Int): ImageReader {
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        r.setOnImageAvailableListener({ onFrame(it) }, main)
        reader = r
        capW = w
        capH = h
        return r
    }

    private fun captureSize(): Pair<Int, Int> {
        val b = wm.currentWindowMetrics.bounds
        return Pair(b.width().coerceAtLeast(2), b.height().coerceAtLeast(2))
    }

    /** Turn the mirror on just long enough to grab one frame. */
    private fun requestSnapshot() {
        state = State.CAPTURING
        val vd = virtualDisplay
        if (vd == null) {
            log("no screen capture, blur only")
            beginAnimation()
            return
        }
        val (w, h) = captureSize()
        if (w != capW || h != capH || reader == null) {
            // Cover screen and inner screen have different sizes
            val old = reader
            val r = newReader(w, h)
            vd.resize(w, h, resources.displayMetrics.densityDpi)
            vd.surface = r.surface
            old?.close()
        } else {
            vd.surface = reader!!.surface
        }
        main.postDelayed(snapshotTimeout, SNAPSHOT_TIMEOUT_MS)
    }

    private fun onFrame(r: ImageReader) {
        val img = runCatching { r.acquireLatestImage() }.getOrNull() ?: return
        try {
            if (state == State.CAPTURING) {
                main.removeCallbacks(snapshotTimeout)
                snapshot?.recycle()
                snapshot = runCatching { toBitmap(img) }.getOrNull()
                log("snapshot " + (snapshot?.let { "${it.width}x${it.height}" } ?: "FAILED"))
                pauseCapture()
                beginAnimation()
            }
        } finally {
            img.close()
        }
    }

    private fun toBitmap(img: Image): Bitmap {
        val plane = img.planes[0]
        val pixelStride = plane.pixelStride
        val rowPadding = plane.rowStride - pixelStride * img.width
        val padded = Bitmap.createBitmap(
            img.width + rowPadding / pixelStride, img.height, Bitmap.Config.ARGB_8888
        )
        padded.copyPixelsFromBuffer(plane.buffer)
        if (rowPadding == 0) return padded
        val cropped = Bitmap.createBitmap(padded, 0, 0, img.width, img.height)
        padded.recycle()
        return cropped
    }

    private fun pauseCapture() {
        runCatching { virtualDisplay?.surface = null }
    }

    private fun releaseCapture() {
        captureActive = false
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { reader?.close() }
        reader = null
        val mp = projection
        projection = null
        runCatching { mp?.stop() }
    }

    // =====================================================================
    // Hinge sensor
    // =====================================================================

    override fun onSensorChanged(event: SensorEvent) {
        val a = event.values[0].coerceIn(0f, 180f)
        val now = SystemClock.uptimeMillis()
        if (!hasReading) {
            hasReading = true
            restAngle = a
            targetAngle = a
            shownAngle = a
            return
        }
        if (abs(a - targetAngle) > 0.5f) lastMoveAt = now
        targetAngle = a

        val sizeNow = wm.currentWindowMetrics.bounds.let { "${it.width()}x${it.height()}" }
        if (sizeNow != lastLoggedSize) {
            log("display is now $sizeNow at ${a.roundToInt()}°")
            lastLoggedSize = sizeNow
        }

        if (state == State.IDLE && !preview && abs(a - restAngle) > START_THRESHOLD) {
            log("fold started at ${restAngle.roundToInt()}° (now ${a.roundToInt()}°)")
            startAngle = restAngle
            shownAngle = restAngle
            lastMoveAt = now
            requestSnapshot()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun startPreview() {
        if (state != State.IDLE) return
        preview = true
        startAngle = 10f
        shownAngle = 10f
        requestSnapshot()
    }

    // =====================================================================
    // Animation
    // =====================================================================

    private fun beginAnimation() {
        state = State.ANIMATING
        animStartAt = SystemClock.uptimeMillis()
        fade = 1f
        switchedAt = 0L
        wm.currentWindowMetrics.bounds.let { startW = it.width(); startH = it.height() }
        attach()
        log(if (root != null) "overlay shown" else "overlay FAILED to show")
        snapView?.bitmap = snapshot
        if (!frameRunning) {
            frameRunning = true
            Choreographer.getInstance().postFrameCallback(frame)
        }
    }

    private fun tick() {
        val now = SystemClock.uptimeMillis()

        // Detect the moment the phone switches between cover screen and inner screen
        val b = wm.currentWindowMetrics.bounds
        if (switchedAt == 0L && (b.width() != startW || b.height() != startH)) {
            switchedAt = now
            log("screen switched to ${b.width()}x${b.height()} at ${shownAngle.roundToInt()}°")
        }

        val moving: Boolean
        if (preview) {
            val e = (now - animStartAt).toFloat()
            val k = (e / PREVIEW_MS).coerceIn(0f, 1f)
            shownAngle = startAngle + (180f - startAngle) * smooth(k)
            // Pretend the screens switch halfway through
            if (switchedAt == 0L && k >= 0.5f) switchedAt = now
            moving = e < PREVIEW_MS
        } else {
            shownAngle += (targetAngle - shownAngle) * 0.35f
            moving = now - lastMoveAt < HOLD_MS
        }

        val endAngle = if (startAngle < 90f) 180f else 0f
        val span = abs(endAngle - startAngle).coerceAtLeast(20f)
        val u = (abs(shownAngle - startAngle) / span).coerceIn(0f, 1f)

        val done: Boolean
        if (switchedAt > 0L) {
            // After the switch: the old screen's content dissolves and the new screen sharpens
            val k = ((now - switchedAt) / DISSOLVE_MS).coerceIn(0f, 1f)
            renderAfterSwitch(k)
            done = k >= 1f
        } else {
            // Before the switch: content blurs and zooms as you fold
            if (!moving) fade += (0f - fade) * 0.3f
            renderBeforeSwitch(u, fade)
            done = !moving && fade < 0.01f
        }

        if (done) {
            log("animation finished")
            finish()
        } else {
            Choreographer.getInstance().postFrameCallback(frame)
        }
    }

    private fun finish() {
        frameRunning = false
        detach()
        state = State.IDLE
        preview = false
        restAngle = targetAngle
        switchedAt = 0L
        snapshot?.recycle()
        snapshot = null
    }

    /** u: how far through the fold (0..1). f: fade-out when you stop moving. */
    private fun renderBeforeSwitch(u: Float, f: Float) {
        val e = smooth((u / 0.45f).coerceIn(0f, 1f))   // reaches full effect by ~45% of the fold
        applyLook(
            snapAlpha = f,
            scale = 1f + 0.10f * e,
            snapBlur = 60f * e,
            frost = 0.20f * e * f,
            behindBlur = Prefs.maxBlur(this) * e * f
        )
    }

    /** k: 0 right at the switch, 1 when the dissolve is complete. */
    private fun renderAfterSwitch(k: Float) {
        val out = 1f - smooth(k)
        applyLook(
            snapAlpha = out,
            scale = 1.10f - 0.10f * smooth(k),
            snapBlur = 60f * out,
            frost = 0.20f * out,
            behindBlur = Prefs.maxBlur(this) * out
        )
    }

    private fun applyLook(snapAlpha: Float, scale: Float, snapBlur: Float, frost: Float, behindBlur: Float) {
        val sv = snapView ?: return
        val fv = frostView ?: return
        val r = root ?: return
        val p = params ?: return

        sv.alpha = snapAlpha.coerceIn(0f, 1f)
        sv.scaleX = scale
        sv.scaleY = scale
        if (Build.VERSION.SDK_INT >= 31) {
            val sb = (snapBlur / 2f).roundToInt() * 2
            if (sb != lastSnapBlur) {
                lastSnapBlur = sb
                sv.setRenderEffect(
                    if (sb > 0) RenderEffect.createBlurEffect(sb.toFloat(), sb.toFloat(), Shader.TileMode.CLAMP)
                    else null
                )
            }
        }

        val blurOk = blurAvailable()
        fv.alpha = (if (blurOk) frost else frost * 2f).coerceIn(0f, 0.6f)

        if (blurOk) {
            val rad = (behindBlur / 2f).roundToInt() * 2
            if (rad != lastBlur) {
                lastBlur = rad
                p.blurBehindRadius = rad
                p.flags = if (rad > 0) p.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                else p.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
                runCatching { wm.updateViewLayout(r, p) }
            }
        }
    }

    private fun smooth(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun blurAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && wm.isCrossWindowBlurEnabled

    // =====================================================================
    // Overlay window
    // =====================================================================

    private fun attach() {
        if (root != null) return
        if (!Settings.canDrawOverlays(this)) return

        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val sv = SnapshotView(this)
        val fv = View(this).apply {
            setBackgroundColor(Color.WHITE)
            alpha = 0f
        }
        val r = FrameLayout(this).apply {
            addView(sv, FrameLayout.LayoutParams(match, match))
            addView(fv, FrameLayout.LayoutParams(match, match))
        }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            // Android blocks touches under untrusted overlays more opaque than 0.8
            alpha = 0.8f
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            fitInsetsTypes = 0
            gravity = Gravity.TOP or Gravity.LEFT
        }
        try {
            wm.addView(r, p)
            root = r
            snapView = sv
            frostView = fv
            params = p
            lastBlur = -1
            lastSnapBlur = -1
        } catch (e: Exception) {
            root = null
        }
    }

    private fun detach() {
        root?.let { runCatching { wm.removeView(it) } }
        snapView?.bitmap = null
        root = null
        snapView = null
        frostView = null
        params = null
        lastBlur = -1
        lastSnapBlur = -1
    }

    // =====================================================================
    // Foreground notification
    // =====================================================================

    private fun startAsForeground(withCapture: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Fold animation", NotificationManager.IMPORTANCE_MIN)
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Fold animation is on")
            .setOngoing(true)
            .build()
        val type = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                    (if (withCapture) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0)
            withCapture -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            else -> 0
        }
        if (type != 0) startForeground(1, n, type) else startForeground(1, n)
    }
}
