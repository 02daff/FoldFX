package dev.foldfx

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Choreographer
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Fold animation without screen capture.
 *
 * Folding: the real screen blurs (window blur) and frosts more the further you fold.
 * Screen switch:
 *  - opening (cover -> big screen): a frosted, cover-shaped glass card appears in the middle of
 *    the blurred big screen, grows to fill it and melts away while the screen sharpens.
 *  - closing (big screen -> cover): the cover screen starts blurred/frosted and sharpens.
 */
class FoldOverlayService : Service(), SensorEventListener, DisplayManager.DisplayListener {

    companion object {
        const val ACTION_PREVIEW = "dev.foldfx.PREVIEW"

        @Volatile var running = false
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
        private const val DISSOLVE_MS = 520f        // transition after the screen switch
        private const val PREVIEW_MS = 1600L
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private lateinit var sensors: SensorManager
    private lateinit var displays: DisplayManager
    private var hinge: Sensor? = null

    // overlay
    private var glass: GlassView? = null
    private var params: WindowManager.LayoutParams? = null
    private var lastBlur = -1

    // motion / screens
    private var animating = false
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
    private var toInner = false
    private var waitForRest = false
    private var screenW = 0
    private var screenH = 0
    private var coverAspect = 1140f / 2616f

    private val frame = Choreographer.FrameCallback { tick() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()

        wm = getSystemService(WindowManager::class.java)
        sensors = getSystemService(SensorManager::class.java)
        displays = getSystemService(DisplayManager::class.java)
        hinge = sensors.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)

        if (hinge == null || !Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        currentSize().let { screenW = it.first; screenH = it.second }
        if (isCover(screenW, screenH)) coverAspect = screenW.toFloat() / screenH

        sensors.registerListener(this, hinge, SensorManager.SENSOR_DELAY_FASTEST)
        displays.registerDisplayListener(this, main)
        running = true
        log("service started, screen ${screenW}x$screenH")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PREVIEW) startPreview()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        if (::sensors.isInitialized) sensors.unregisterListener(this)
        if (::displays.isInitialized) displays.unregisterDisplayListener(this)
        Choreographer.getInstance().removeFrameCallback(frame)
        frameRunning = false
        detach()
        super.onDestroy()
    }

    private fun currentSize(): Pair<Int, Int> {
        val b = wm.currentWindowMetrics.bounds
        return Pair(b.width().coerceAtLeast(2), b.height().coerceAtLeast(2))
    }

    private fun isCover(w: Int, h: Int) = w < h * 0.75f

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

        if (animating || preview) return

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
            begin()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // =====================================================================
    // Screen switch (cover <-> inner)
    // =====================================================================

    override fun onDisplayAdded(displayId: Int) {}
    override fun onDisplayRemoved(displayId: Int) {}

    override fun onDisplayChanged(displayId: Int) {
        if (displayId == Display.DEFAULT_DISPLAY) checkScreenSwitch()
    }

    private fun checkScreenSwitch() {
        val (w, h) = currentSize()
        if (w == screenW && h == screenH) return
        val fromCover = isCover(screenW, screenH)
        val nowCover = isCover(w, h)
        screenW = w; screenH = h
        if (nowCover) coverAspect = w.toFloat() / h
        if (fromCover == nowCover) return   // rotation etc., not a fold switch

        log("switched to ${if (nowCover) "cover" else "inner"} screen ${w}x$h at ${targetAngle.roundToInt()}°")
        if (!animating) {
            // The big screen often turns on before a fold gesture is detected: animate anyway
            startAngle = targetAngle
            shownAngle = targetAngle
            begin()
        }
        if (switchedAt == 0L) {
            switchedAt = SystemClock.uptimeMillis()
            toInner = !nowCover
        }
    }

    // =====================================================================
    // Animation
    // =====================================================================

    private fun startPreview() {
        if (animating) return
        preview = true
        startAngle = 10f
        shownAngle = 10f
        begin()
    }

    private fun begin() {
        animating = true
        animStartAt = SystemClock.uptimeMillis()
        fade = 1f
        switchedAt = 0L
        toInner = false
        attach()
        log(if (glass != null) "overlay shown" else "overlay FAILED to show")
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
                toInner = true
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
        waitForRest = switchedAt > 0L
        detach()
        animating = false
        preview = false
        restAngle = targetAngle
        switchedAt = 0L
    }

    /** u: how far through the fold (0..1). f: fade-out when you stop moving. */
    private fun renderBeforeSwitch(u: Float, f: Float) {
        val e = smooth((u / 0.45f).coerceIn(0f, 1f))
        applyLook(glassStrength = 0.6f * e * f, shape = 1f, blur = Prefs.maxBlur(this) * e * f)
    }

    /** k: 0 right at the switch, 1 when the transition is complete. */
    private fun renderAfterSwitch(k: Float) {
        val s = smooth(k)
        if (toInner) {
            // Cover-shaped glass card grows to fill the big screen and melts away
            val melt = 1f - smooth(((k - 0.25f) / 0.75f).coerceIn(0f, 1f))
            applyLook(glassStrength = melt, shape = s, blur = Prefs.maxBlur(this) * (1f - s))
        } else {
            // Cover screen starts frosted and sharpens
            applyLook(glassStrength = 0.6f * (1f - s), shape = 1f, blur = Prefs.maxBlur(this) * (1f - s))
        }
    }

    private fun applyLook(glassStrength: Float, shape: Float, blur: Float) {
        val g = glass ?: return
        val p = params ?: return

        g.coverAspect = coverAspect
        g.strength = if (blurAvailable()) glassStrength else (glassStrength * 1.6f).coerceAtMost(1f)
        g.shape = shape
        g.invalidate()

        if (blurAvailable()) {
            val rad = (blur / 2f).roundToInt() * 2
            if (rad != lastBlur) {
                lastBlur = rad
                p.blurBehindRadius = rad
                p.flags = if (rad > 0) p.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                else p.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
                runCatching { wm.updateViewLayout(g, p) }
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
        if (glass != null) return
        if (!Settings.canDrawOverlays(this)) return

        val g = GlassView(this)
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
            wm.addView(g, p)
            glass = g
            params = p
            lastBlur = -1
        } catch (e: Exception) {
            glass = null
        }
    }

    private fun detach() {
        glass?.let { runCatching { wm.removeView(it) } }
        glass = null
        params = null
        lastBlur = -1
    }

    // =====================================================================
    // Foreground notification
    // =====================================================================

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Fold animation", NotificationManager.IMPORTANCE_MIN)
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Fold animation is on")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }
}
