package dev.foldfx

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Choreographer
import android.view.Display
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
 * Closing: as you fold, the big screen's content blurs and zooms; when the phone switches to
 * the cover screen, that content dissolves there while the cover screen sharpens.
 *
 * Opening: while the cover screen is on we keep a recent picture of it. When the big screen
 * turns on, that picture appears in the middle at its own shape, grows to fill the screen and
 * dissolves, while the big screen sharpens behind it.
 */
class FoldOverlayService : Service(), SensorEventListener, DisplayManager.DisplayListener {

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
                while (logLines.size > 60) logLines.removeFirst()
            }
        }

        fun logText(): String = synchronized(logLines) { logLines.joinToString("\n") }

        private const val CHANNEL = "foldfx"
        private const val STOP_MS = 700L            // a pause this long ends the effect
        private const val START_THRESHOLD = 10f     // degrees of movement that start the effect
        private const val OPEN_END = 172f           // counts as fully open
        private const val SNAPSHOT_TIMEOUT_MS = 160L
        private const val DISSOLVE_MS = 480f        // dissolve after the screen switch
        private const val PREVIEW_MS = 1600L
        private const val COVER_CACHE_MS = 300L     // refresh the cover-screen picture this often
    }

    private enum class State { IDLE, CAPTURING, ANIMATING }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private lateinit var sensors: SensorManager
    private lateinit var displays: DisplayManager
    private var hinge: Sensor? = null

    // ---- capture ----
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var capW = 0
    private var capH = 0
    private var live = false                 // mirror running continuously (cover screen cache)
    private var snapshot: Bitmap? = null
    private var coverCache: Bitmap? = null
    private var coverCacheAt = 0L

    // ---- overlay ----
    private var root: FrameLayout? = null
    private var snapView: SnapshotView? = null
    private var frostView: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var lastBlur = -1
    private var lastSnapBlur = -1

    // ---- motion / screens ----
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
    private var switchedAt = 0L
    private var expandFromCover = false
    private var waitForRest = false
    private var screenW = 0
    private var screenH = 0
    private var screenOn = true

    private val frame = Choreographer.FrameCallback { tick() }
    private val snapshotTimeout = Runnable {
        if (state == State.CAPTURING) {
            log("snapshot timed out (no frame)")
            beginAnimation()
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_OFF -> { screenOn = false; log("screen off") }
                Intent.ACTION_SCREEN_ON -> { screenOn = true; log("screen on") }
                Intent.ACTION_USER_PRESENT -> {
                    log("unlocked")
                    if (!captureActive && Prefs.autoReenable(this@FoldOverlayService)) {
                        log("re-enabling screen capture")
                        runCatching {
                            startActivity(
                                Intent(this@FoldOverlayService, CaptureActivity::class.java)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }.onFailure { log("could not re-enable: ${it.javaClass.simpleName}") }
                    }
                }
            }
            updateLive()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground(withCapture = false)

        wm = getSystemService(WindowManager::class.java)
        sensors = getSystemService(SensorManager::class.java)
        displays = getSystemService(DisplayManager::class.java)
        hinge = sensors.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)

        if (hinge == null || !Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        currentSize().let { screenW = it.first; screenH = it.second }
        screenOn = getSystemService(PowerManager::class.java).isInteractive

        sensors.registerListener(this, hinge, SensorManager.SENSOR_DELAY_FASTEST)
        displays.registerDisplayListener(this, main)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenReceiver, filter)
        }
        running = true
        log("service started, screen ${screenW}x$screenH")
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
        if (::displays.isInitialized) displays.unregisterDisplayListener(this)
        runCatching { unregisterReceiver(screenReceiver) }
        Choreographer.getInstance().removeFrameCallback(frame)
        main.removeCallbacks(snapshotTimeout)
        frameRunning = false
        detach()
        releaseCapture()
        snapshot?.recycle(); snapshot = null
        coverCache?.recycle(); coverCache = null
        super.onDestroy()
    }

    private fun currentSize(): Pair<Int, Int> {
        val b = wm.currentWindowMetrics.bounds
        return Pair(b.width().coerceAtLeast(2), b.height().coerceAtLeast(2))
    }

    private fun isCover(w: Int, h: Int) = w < h * 0.75f

    // =====================================================================
    // Screen capture
    // =====================================================================

    private fun setupProjection(code: Int, data: Intent) {
        releaseCapture()
        startAsForeground(withCapture = true)   // must happen before getMediaProjection on Android 14+
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val mp = runCatching { mpm.getMediaProjection(code, data) }.getOrNull()
        if (mp == null) {
            log("screen capture token rejected")
            return
        }
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                log("screen capture STOPPED by system")
                main.post { releaseCapture() }
            }
        }, main)
        projection = mp

        val (w, h) = currentSize()
        val r = newReader(w, h)
        virtualDisplay = runCatching {
            mp.createVirtualDisplay(
                "FoldFX", w, h, resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                null, null, main
            )
        }.getOrNull()
        if (virtualDisplay == null) {
            r.close(); reader = null
            log("could not start screen capture")
        } else {
            log("screen capture granted")
        }
        captureActive = virtualDisplay != null
        live = false
        updateLive()
    }

    private fun newReader(w: Int, h: Int): ImageReader {
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        r.setOnImageAvailableListener({ onFrame(it) }, main)
        reader = r
        capW = w
        capH = h
        return r
    }

    /** Point the mirror at an ImageReader matching the current screen size. */
    private fun attachSurface(): Boolean {
        val vd = virtualDisplay ?: return false
        val (w, h) = currentSize()
        if (w != capW || h != capH || reader == null) {
            val old = reader
            val r = newReader(w, h)
            vd.resize(w, h, resources.displayMetrics.densityDpi)
            vd.surface = r.surface
            old?.close()
        } else {
            vd.surface = reader!!.surface
        }
        return true
    }

    private fun pauseCapture() {
        runCatching { virtualDisplay?.surface = null }
        live = false
    }

    /** While idle on the cover screen, keep mirroring so we always have a fresh picture of it. */
    private fun updateLive() {
        val want = captureActive && screenOn && state == State.IDLE && isCover(screenW, screenH)
        if (want && !live) {
            if (attachSurface()) {
                live = true
                coverCacheAt = 0L
            }
        } else if (!want && live && state == State.IDLE) {
            pauseCapture()
        }
    }

    private fun onFrame(r: ImageReader) {
        val img = runCatching { r.acquireLatestImage() }.getOrNull() ?: return
        try {
            val now = SystemClock.uptimeMillis()
            if (state == State.CAPTURING) {
                main.removeCallbacks(snapshotTimeout)
                snapshot?.recycle()
                snapshot = runCatching { toBitmap(img) }.getOrNull()
                log("snapshot " + (snapshot?.let { "${it.width}x${it.height}" } ?: "FAILED"))
                pauseCapture()
                beginAnimation()
            } else if (live && now - coverCacheAt >= COVER_CACHE_MS) {
                val bmp = runCatching { toBitmap(img) }.getOrNull()
                if (bmp != null) {
                    coverCache?.recycle()
                    coverCache = bmp
                    coverCacheAt = now
                }
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

    private fun releaseCapture() {
        captureActive = false
        live = false
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
            restAngle = a; targetAngle = a; shownAngle = a
            log("hinge at ${a.roundToInt()}°")
            return
        }
        val prevMoveAt = lastMoveAt
        if (abs(a - targetAngle) > 0.5f) lastMoveAt = now
        targetAngle = a

        if (state != State.IDLE || preview) return

        // After a screen-switch animation, wait until the hinge settles before starting another
        if (waitForRest) {
            restAngle = a
            if (now - prevMoveAt > STOP_MS || a >= OPEN_END || a <= 5f) waitForRest = false
            return
        }

        if (abs(a - restAngle) > START_THRESHOLD) {
            log("fold started at ${restAngle.roundToInt()}° (now ${a.roundToInt()}°)")
            startAngle = restAngle
            shownAngle = restAngle
            requestSnapshot()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // =====================================================================
    // Screen switch (cover <-> inner)
    // =====================================================================

    override fun onDisplayAdded(displayId: Int) {}
    override fun onDisplayRemoved(displayId: Int) {}

    override fun onDisplayChanged(displayId: Int) {
        if (displayId != Display.DEFAULT_DISPLAY) return
        checkScreenSwitch()
    }

    private fun checkScreenSwitch() {
        val (w, h) = currentSize()
        if (w == screenW && h == screenH) return
        val fromCover = isCover(screenW, screenH)
        val toCover = isCover(w, h)
        screenW = w; screenH = h
        if (fromCover == toCover) return   // rotation etc., not a fold switch

        val now = SystemClock.uptimeMillis()
        log("switched to ${if (toCover) "cover" else "inner"} screen ${w}x$h at ${targetAngle.roundToInt()}°")

        if (state == State.ANIMATING) {
            if (switchedAt == 0L) {
                switchedAt = now
                expandFromCover = fromCover
                // Opening: prefer the cover-screen picture if what we have is from the big screen
                if (fromCover) useCoverCacheIfBetter()
            }
        } else if (fromCover && !toCover) {
            // Opening and the big screen turned on before (or without) a fold gesture
            main.removeCallbacks(snapshotTimeout)
            val cached = coverCache
            if (cached != null && SystemClock.uptimeMillis() - coverCacheAt < 60_000) {
                pauseCapture()
                snapshot?.recycle()
                snapshot = cached
                coverCache = null
                startAngle = targetAngle
                shownAngle = targetAngle
                beginAnimation()
                switchedAt = now
                expandFromCover = true
                log("opening animation from cover picture")
            } else {
                log("no cover picture yet, skipping opening animation")
                if (state == State.CAPTURING) beginAnimation()
            }
        }
        updateLive()
    }

    private fun useCoverCacheIfBetter() {
        val cached = coverCache ?: return
        val snap = snapshot
        if (snap == null || !isCover(snap.width, snap.height)) {
            snap?.recycle()
            snapshot = cached
            coverCache = null
            snapView?.bitmap = snapshot
        }
    }

    // =====================================================================
    // Animation
    // =====================================================================

    private fun requestSnapshot() {
        state = State.CAPTURING
        if (virtualDisplay == null) {
            log("no screen capture, blur only")
            beginAnimation()
            return
        }
        // On the cover screen the mirror is already running and may not produce a new frame
        // if nothing changes, so use the cached picture.
        val cached = coverCache
        if (live && cached != null) {
            pauseCapture()
            snapshot?.recycle()
            snapshot = cached
            coverCache = null
            log("snapshot from cover cache")
            beginAnimation()
            return
        }
        if (!attachSurface()) {
            beginAnimation()
            return
        }
        main.postDelayed(snapshotTimeout, SNAPSHOT_TIMEOUT_MS)
    }

    private fun startPreview() {
        if (state != State.IDLE) return
        preview = true
        startAngle = 10f
        shownAngle = 10f
        requestSnapshot()
    }

    private fun beginAnimation() {
        main.removeCallbacks(snapshotTimeout)
        pauseCapture()
        state = State.ANIMATING
        animStartAt = SystemClock.uptimeMillis()
        fade = 1f
        switchedAt = 0L
        expandFromCover = false
        attach()
        if (root == null) log("overlay FAILED to show")
        snapView?.bitmap = snapshot
        if (!frameRunning) {
            frameRunning = true
            Choreographer.getInstance().postFrameCallback(frame)
        }
    }

    private fun tick() {
        val now = SystemClock.uptimeMillis()
        checkScreenSwitch()   // in case the display callback is late

        val moving: Boolean
        if (preview) {
            val e = (now - animStartAt).toFloat()
            val k = (e / PREVIEW_MS).coerceIn(0f, 1f)
            shownAngle = startAngle + (180f - startAngle) * smooth(k)
            if (switchedAt == 0L && k >= 0.5f) {
                switchedAt = now
                expandFromCover = true
            }
            moving = e < PREVIEW_MS
        } else {
            shownAngle += (targetAngle - shownAngle) * 0.35f
            moving = now - lastMoveAt < STOP_MS
        }

        val opening = startAngle < 90f
        val endAngle = if (opening) 180f else 0f
        val span = abs(endAngle - startAngle).coerceAtLeast(20f)
        val u = (abs(shownAngle - startAngle) / span).coerceIn(0f, 1f)

        val done: Boolean
        if (switchedAt > 0L) {
            val k = ((now - switchedAt) / DISSOLVE_MS).coerceIn(0f, 1f)
            renderAfterSwitch(k)
            done = k >= 1f
        } else {
            val reachedEnd = opening && shownAngle >= OPEN_END
            if (!moving || reachedEnd) fade += (0f - fade) * 0.3f
            renderBeforeSwitch(u, fade)
            done = (!moving || reachedEnd) && fade < 0.01f
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
        val hadSwitch = switchedAt > 0L
        detach()
        state = State.IDLE
        preview = false
        restAngle = targetAngle
        switchedAt = 0L
        expandFromCover = false
        waitForRest = hadSwitch
        snapshot?.recycle()
        snapshot = null
        updateLive()
    }

    /** u: how far through the fold (0..1). f: fade-out when you stop moving. */
    private fun renderBeforeSwitch(u: Float, f: Float) {
        val e = smooth((u / 0.45f).coerceIn(0f, 1f))
        applyLook(
            snapAlpha = f, fill = 1f,
            scale = 1f + 0.10f * e,
            snapBlur = 60f * e,
            frost = 0.20f * e * f,
            behindBlur = Prefs.maxBlur(this) * e * f
        )
    }

    /** k: 0 right at the switch, 1 when the dissolve is complete. */
    private fun renderAfterSwitch(k: Float) {
        val s = smooth(k)
        val out = 1f - s
        if (expandFromCover) {
            // Cover picture starts at its own shape in the middle, grows to fill, and dissolves
            applyLook(
                snapAlpha = 1f - smooth(((k - 0.1f) / 0.9f).coerceIn(0f, 1f)),
                fill = s,
                scale = 1f,
                snapBlur = 12f + 48f * s,
                frost = 0.15f * out,
                behindBlur = Prefs.maxBlur(this) * out
            )
        } else {
            // Big-screen picture dissolves on the cover screen
            applyLook(
                snapAlpha = out, fill = 1f,
                scale = 1.10f - 0.10f * s,
                snapBlur = 60f * out,
                frost = 0.20f * out,
                behindBlur = Prefs.maxBlur(this) * out
            )
        }
    }

    private fun applyLook(
        snapAlpha: Float, fill: Float, scale: Float,
        snapBlur: Float, frost: Float, behindBlur: Float
    ) {
        val sv = snapView ?: return
        val fv = frostView ?: return
        val r = root ?: return
        val p = params ?: return

        sv.alpha = snapAlpha.coerceIn(0f, 1f)
        sv.fill = fill
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
