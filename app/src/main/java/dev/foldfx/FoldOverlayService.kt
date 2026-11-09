package dev.foldfx

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ServiceInfo
import android.graphics.Color
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
import android.view.View
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * iPhone Duo-style fold effect, no screen capture.
 *
 * Big screen: only the half that swings is affected. It turns dark and blurred (smoked glass),
 * a bit stronger toward its outer edge, and clears as the phone comes flat. Driven directly by
 * the hinge angle.
 *
 * Cover screen (right after closing): it starts blurred and dim, and the sharpness sweeps from
 * the hinge side to the outer edge.
 *
 * The varying blur is made from several thin vertical overlay strips, each with its own blur.
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
        private const val STRIPS = 8
        private const val FULL_EFFECT_AT = 40f      // effect is at full strength 40° away from flat
        private const val IDLE_FADE_MS = 1200L      // held still half-open this long -> fade out
        private const val COVER_SWEEP_MS = 650f     // cover screen sharpening sweep
        private const val PREVIEW_MS = 2200L
    }

    private class Strip(val view: View, val params: WindowManager.LayoutParams) {
        var blur = -1
        var dim = -1f
        var x = -1
        var w = -1
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private lateinit var sensors: SensorManager
    private lateinit var displays: DisplayManager
    private var hinge: Sensor? = null

    private val strips = ArrayList<Strip>()
    private var lockView: View? = null

    // screenshot for the projection
    private enum class Snap { NONE, WAITING, READY }
    private var snapState = Snap.NONE
    private var snapAskedAt = 0L
    private var snapshot: android.graphics.Bitmap? = null
    private var panelHost: WindowManager? = null
    private var panel: ProjectionPanel? = null
    private var panelParams: WindowManager.LayoutParams? = null

    private var animating = false
    private var frameRunning = false
    private var hasReading = false
    private var targetAngle = 180f
    private var shownAngle = 180f
    private var lastMoveAt = 0L
    private var fade = 1f
    private var preview = false
    private var previewStart = 0L
    private var coverSweepAt = 0L
    private var screenW = 0
    private var screenH = 0

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
        sensors.registerListener(this, hinge, SensorManager.SENSOR_DELAY_FASTEST)
        displays.registerDisplayListener(this, main)
        running = true
        log("service started, screen ${screenW}x$screenH, blur ${if (blurAvailable()) "on" else "OFF"}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PREVIEW) {
            preview = true
            previewStart = SystemClock.uptimeMillis()
            log("preview")
            begin()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        if (::sensors.isInitialized) sensors.unregisterListener(this)
        if (::displays.isInitialized) displays.unregisterDisplayListener(this)
        Choreographer.getInstance().removeFrameCallback(frame)
        frameRunning = false
        removeStrips()
        removePanel()
        unlockOrientation()
        super.onDestroy()
    }

    private fun currentSize(): Pair<Int, Int> {
        val b = wm.currentWindowMetrics.bounds
        return Pair(b.width().coerceAtLeast(2), b.height().coerceAtLeast(2))
    }

    private fun isCover(w: Int, h: Int) = w < h * 0.75f

    // ---- hinge ----

    override fun onSensorChanged(event: SensorEvent) {
        val a = event.values[0].coerceIn(0f, 180f)
        val now = SystemClock.uptimeMillis()
        if (!hasReading) {
            hasReading = true
            targetAngle = a; shownAngle = a
            log("hinge at ${a.roundToInt()}°")
            return
        }
        if (abs(a - targetAngle) > 0.5f) {
            lastMoveAt = now
            if (!animating && !isCover(screenW, screenH) && a < 177f) {
                log("folding from ${targetAngle.roundToInt()}°")
                begin()
            }
        }
        targetAngle = a
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ---- screen switch ----

    override fun onDisplayAdded(displayId: Int) {}
    override fun onDisplayRemoved(displayId: Int) {}
    override fun onDisplayChanged(displayId: Int) {
        if (displayId == Display.DEFAULT_DISPLAY) checkScreenSwitch()
    }

    private fun checkScreenSwitch() {
        val (w, h) = currentSize()
        if (w == screenW && h == screenH) return
        val wasCover = isCover(screenW, screenH)
        val nowCover = isCover(w, h)
        screenW = w; screenH = h
        if (wasCover == nowCover) return
        log("switched to ${if (nowCover) "cover" else "big"} screen at ${targetAngle.roundToInt()}°")
        removeStrips()   // re-create overlays for the new screen size
        removePanel()
        clearSnapshot()
        if (nowCover) {
            coverSweepAt = SystemClock.uptimeMillis()
            begin()
        } else {
            coverSweepAt = 0L
            lastMoveAt = SystemClock.uptimeMillis()
            shownAngle = targetAngle
            begin()
        }
    }

    // ---- animation ----

    /** Ask for one screenshot of the current screen before the effect becomes visible. */
    private fun requestSnapshot() {
        if (snapState != Snap.NONE) return
        snapAskedAt = SystemClock.uptimeMillis()
        snapState = Snap.WAITING
        val asked = FoldAccessibilityService.capture { bmp ->
            if (snapState == Snap.WAITING) {
                snapshot?.recycle()
                snapshot = bmp
                snapState = Snap.READY
                log(if (bmp != null) "screenshot ${bmp.width}x${bmp.height} in ${SystemClock.uptimeMillis() - snapAskedAt} ms" else "no screenshot, using grey")
                panel?.snapshot = bmp
            } else {
                bmp?.recycle()
            }
        }
        if (!asked) {
            snapState = Snap.READY
            log("screenshot access off, using grey")
        }
    }

    private fun clearSnapshot() {
        panel?.snapshot = null
        snapshot?.recycle()
        snapshot = null
        snapState = Snap.NONE
    }

    /** A tiny invisible overlay that freezes screen rotation while the effect runs. */
    private fun lockOrientation() {
        if (lockView != null || !Settings.canDrawOverlays(this)) return
        val v = View(this)
        val lp = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            screenOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
            alpha = 0f
        }
        try {
            wm.addView(v, lp)
            lockView = v
        } catch (e: Exception) {
            log("orientation lock FAILED: ${e.javaClass.simpleName}")
        }
    }

    private fun unlockOrientation() {
        lockView?.let { runCatching { wm.removeView(it) } }
        lockView = null
    }

    private fun begin() {
        lockOrientation()
        if (!isCover(screenW, screenH)) requestSnapshot()
        animating = true
        fade = 1f
        if (!frameRunning) {
            frameRunning = true
            Choreographer.getInstance().postFrameCallback(frame)
        }
    }

    private fun finish() {
        log("effect ended")
        animating = false
        frameRunning = false
        preview = false
        coverSweepAt = 0L
        removeStrips()
        removePanel()
        unlockOrientation()
        clearSnapshot()
    }

    private fun tick() {
        val now = SystemClock.uptimeMillis()
        checkScreenSwitch()
        val cover = isCover(screenW, screenH)
        val movingLeft = Prefs.movingHalfLeft(this)
        val maxBlur = Prefs.maxBlur(this).toFloat()

        val blur = FloatArray(STRIPS)
        val dim = FloatArray(STRIPS)
        val done: Boolean

        if (cover) {
            // Cover screen: sharpen with a sweep from the hinge side outward
            if (coverSweepAt == 0L) { finish(); return }
            val t = (now - coverSweepAt) / COVER_SWEEP_MS
            for (i in 0 until STRIPS) {
                val x = (i + 0.5f) / STRIPS                 // 0 = hinge side, 1 = outer edge
                val k = smooth(((t - 0.55f * x) / 0.45f).coerceIn(0f, 1f))
                blur[i] = maxBlur * 0.8f * (1f - k)
                dim[i] = 0.30f * (1f - k)
            }
            done = t >= 1f
        } else {
            val angle: Float
            if (preview) {
                // Fake a fold: 180 -> 70 -> 180
                val e = (now - previewStart).toFloat() / PREVIEW_MS
                val tri = if (e < 0.5f) e * 2f else (1f - e) * 2f
                angle = 180f - 110f * smooth(tri.coerceIn(0f, 1f))
                shownAngle = angle
            } else {
                shownAngle += (targetAngle - shownAngle) * 0.4f
                angle = shownAngle
            }
            val still = !preview && now - lastMoveAt > IDLE_FADE_MS
            if (still) fade += (0f - fade) * 0.15f

            // fold = how far the swinging half has turned away from flat
            val fold = (180f - angle).coerceIn(0f, 179f)
            // grows all the way from flat to closed (fast at first, then keeps building)
            val strength = (fold / 160f).coerceIn(0f, 1f).pow(0.6f) * fade
            val appear = smooth((fold / 20f).coerceIn(0f, 1f)) * fade        // projection fades in off flat
            for (i in 0 until STRIPS) {
                val x = (i + 0.5f) / STRIPS                 // 0 = at hinge, 1 = outer edge
                blur[i] = maxBlur * strength * (0.15f + 0.85f * x * x)
                dim[i] = 0.65f * strength * (0.35f + 0.65f * x)
            }
            done = if (preview) now - previewStart >= PREVIEW_MS
            else (fold < 1.5f && abs(targetAngle - shownAngle) < 0.5f) || (still && fade < 0.02f)

            if (!done) {
                val waiting = snapState == Snap.WAITING && now - snapAskedAt < 250L
                if (!waiting) renderPanel(movingLeft, fold, appear, strength, maxBlur)
                Choreographer.getInstance().postFrameCallback(frame)
                return
            }
        }

        if (done) {
            finish()
            return
        }
        renderStrips(true, movingLeft, blur, dim)
        Choreographer.getInstance().postFrameCallback(frame)
    }

    private fun smooth(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    // ---- strips (overlay windows) ----

    /**
     * Strip 0 is next to the hinge, the last strip at the outer edge.
     * Big screen: strips cover the moving half. Cover screen: strips cover the whole screen,
     * with the hinge on the side opposite the moving half's outer edge.
     */
    private fun renderStrips(cover: Boolean, movingLeft: Boolean, blur: FloatArray, dim: FloatArray) {
        if (strips.isEmpty() && !createStrips()) return
        val w = screenW
        val regionStart: Int
        val regionW: Int
        val hingeOnRight: Boolean
        if (cover) {
            regionStart = 0; regionW = w
            hingeOnRight = movingLeft          // cover sits on the moving panel; its hinge edge faces the other way
        } else {
            regionW = w / 2
            regionStart = if (movingLeft) 0 else w / 2
            hingeOnRight = movingLeft          // left half moving -> hinge is on its right
        }
        val sw = regionW.toFloat() / STRIPS
        val blurOk = blurAvailable()

        for (i in 0 until STRIPS) {
            val s = strips[i]
            // position: i counts outward from the hinge
            val slot = if (hingeOnRight) STRIPS - 1 - i else i
            val x0 = (regionStart + slot * sw).roundToInt()
            val x1 = (regionStart + (slot + 1) * sw).roundToInt()
            var changed = false
            if (x0 != s.x || x1 - x0 != s.w) {
                s.x = x0; s.w = x1 - x0
                s.params.x = x0; s.params.width = (x1 - x0).coerceAtLeast(1)
                changed = true
            }
            val d = if (blurOk) dim[i] else (dim[i] * 1.3f).coerceAtMost(0.85f)
            if (abs(d - s.dim) > 0.004f) {
                s.dim = d
                s.view.setBackgroundColor(Color.argb((d * 255).roundToInt().coerceIn(0, 255), 0, 0, 0))
            }
            if (blurOk) {
                val r = (blur[i] / 2f).roundToInt() * 2
                if (r != s.blur) {
                    s.blur = r
                    s.params.blurBehindRadius = r
                    s.params.flags = if (r > 0) s.params.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                    else s.params.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
                    changed = true
                }
            }
            if (changed) runCatching { wm.updateViewLayout(s.view, s.params) }
        }
    }

    private fun createStrips(): Boolean {
        if (!Settings.canDrawOverlays(this)) return false
        for (i in 0 until STRIPS) {
            val v = View(this)
            val p = WindowManager.LayoutParams(
                1,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                alpha = 0.8f   // Android blocks touches under untrusted overlays more opaque than 0.8
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                fitInsetsTypes = 0
                gravity = Gravity.TOP or Gravity.LEFT
                screenOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
            }
            try {
                wm.addView(v, p)
                strips.add(Strip(v, p))
            } catch (e: Exception) {
                log("overlay FAILED: ${e.javaClass.simpleName}")
                removeStrips()
                return false
            }
        }
        return true
    }

    private fun removeStrips() {
        for (s in strips) runCatching { wm.removeView(s.view) }
        strips.clear()
    }

    // ---- projection panel (big screen, swinging half) ----

    private fun renderPanel(movingLeft: Boolean, fold: Float, appear: Float, strength: Float, maxBlur: Float) {
        var p = panel
        var lp = panelParams
        if (p == null || lp == null) {
            // Prefer an accessibility overlay: Android lets it be fully opaque, so the projected
            // picture fully replaces the screen. Otherwise a normal overlay capped at 80%.
            val a11y = FoldAccessibilityService.instance
            val hostCtx: android.content.Context = a11y ?: this
            val host = if (a11y != null) a11y.getSystemService(WindowManager::class.java) else wm
            if (a11y == null && !Settings.canDrawOverlays(this)) return
            p = ProjectionPanel(hostCtx)
            lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                if (a11y != null) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                alpha = if (a11y != null) 1f else 0.8f
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                fitInsetsTypes = 0
                gravity = Gravity.TOP or Gravity.LEFT
                x = 0
                y = 0
                screenOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
            }
            try {
                host.addView(p, lp)
                panel = p
                panelParams = lp
                panelHost = host
                log("projection shown (" + (if (a11y != null) "full opacity" else "80% overlay") + ")")
            } catch (e: Exception) {
                log("projection FAILED: ${e.javaClass.simpleName}")
                return
            }
        }
        p.screenW = screenW
        p.screenH = screenH
        p.movingLeft = movingLeft
        if (p.snapshot !== snapshot) p.snapshot = snapshot
        p.coverage = appear
        p.update(fold, strength, maxBlur, 0.7f)
    }

    private fun removePanel() {
        panel?.let { v -> runCatching { (panelHost ?: wm).removeView(v) } }
        panel = null
        panelParams = null
        panelHost = null
    }

    private fun blurAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && wm.isCrossWindowBlurEnabled

    // ---- foreground notification ----

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
