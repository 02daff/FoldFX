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
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.view.Choreographer
import android.view.WindowManager
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Listens to the hinge angle and, while the phone is moving between closed and open,
 * shows a full-screen, non-touchable overlay that blurs and frosts whatever is underneath.
 */
class FoldOverlayService : Service(), SensorEventListener {

    companion object {
        @Volatile var running = false
            private set

        const val ACTION_PREVIEW = "dev.foldfx.PREVIEW"
        private const val CHANNEL = "foldfx"
        private const val PREVIEW_MS = 4000L
        private const val HOLD_MS = 450L      // how long after the last movement the effect stays
        private const val MIN_ANGLE = 4f      // below this = closed
        private const val MAX_ANGLE = 176f    // above this = open
    }

    private lateinit var wm: WindowManager
    private lateinit var sensors: SensorManager
    private var hinge: Sensor? = null

    private var view: FoldEffectView? = null
    private var params: WindowManager.LayoutParams? = null

    private var hasReading = false
    private var targetAngle = 180f
    private var shownAngle = 180f
    private var lastMoveAt = 0L
    private var fade = 0f
    private var frameRunning = false
    private var lastBlur = -1
    private var previewUntil = 0L

    private val frame = Choreographer.FrameCallback { tick() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()

        wm = getSystemService(WindowManager::class.java)
        sensors = getSystemService(SensorManager::class.java)
        hinge = sensors.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)

        if (hinge == null || !Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        sensors.registerListener(this, hinge, SensorManager.SENSOR_DELAY_FASTEST)
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        view?.movingSideRight = Prefs.movingSideRight(this)
        if (intent?.action == ACTION_PREVIEW) {
            previewUntil = SystemClock.uptimeMillis() + PREVIEW_MS
            startFrames()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        if (::sensors.isInitialized) sensors.unregisterListener(this)
        Choreographer.getInstance().removeFrameCallback(frame)
        frameRunning = false
        detach()
        super.onDestroy()
    }

    // ---- Sensor ----

    override fun onSensorChanged(event: SensorEvent) {
        val a = event.values[0].coerceIn(0f, 180f)
        if (!hasReading) {
            hasReading = true
            targetAngle = a
            shownAngle = a
            return
        }
        if (abs(a - targetAngle) > 0.5f) lastMoveAt = SystemClock.uptimeMillis()
        targetAngle = a
        if (SystemClock.uptimeMillis() - lastMoveAt < HOLD_MS) startFrames()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ---- Animation loop ----

    private fun startFrames() {
        if (frameRunning) return
        frameRunning = true
        Choreographer.getInstance().postFrameCallback(frame)
    }

    private fun tick() {
        val now = SystemClock.uptimeMillis()

        val previewing = now < previewUntil
        val moving: Boolean
        if (previewing) {
            // Fake a fold going back and forth between 30° and 150°
            val phase = ((now % 2000L) / 2000f)
            val tri = if (phase < 0.5f) phase * 2f else (1f - phase) * 2f
            shownAngle = 30f + 120f * tri
            moving = true
        } else {
            // Smooth the sensor (it may report at a lower rate than the screen refreshes)
            shownAngle += (targetAngle - shownAngle) * 0.3f
            moving = now - lastMoveAt < HOLD_MS && shownAngle > MIN_ANGLE && shownAngle < MAX_ANGLE
        }
        val goal = if (moving) 1f else 0f
        fade += (goal - fade) * (if (moving) 0.25f else 0.12f)

        val t = (shownAngle / 180f).coerceIn(0f, 1f)
        // Strongest in the middle of the fold, nothing when fully open/closed
        val intensity = sin(PI * t).toFloat().coerceAtLeast(0f).pow(0.6f) * fade

        render(intensity, t)

        val settled = !moving && fade < 0.01f && abs(targetAngle - shownAngle) < 0.5f
        if (settled) {
            fade = 0f
            detach()
            frameRunning = false
        } else {
            Choreographer.getInstance().postFrameCallback(frame)
        }
    }

    private fun render(intensity: Float, progress: Float) {
        if (intensity < 0.003f && view == null) return
        val v = attach() ?: return
        val p = params ?: return

        v.intensity = intensity
        v.progress = progress
        v.blurActive = blurAvailable()
        v.invalidate()

        if (blurAvailable()) {
            // Quantize to steps of 2px so we don't relayout the window every frame for nothing
            val r = ((intensity * Prefs.maxBlur(this)) / 2f).roundToInt() * 2
            if (r != lastBlur) {
                lastBlur = r
                p.blurBehindRadius = r
                p.flags = if (r > 0) p.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                else p.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
                runCatching { wm.updateViewLayout(v, p) }
            }
        }
    }

    private fun blurAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && wm.isCrossWindowBlurEnabled

    // ---- Overlay window ----

    private fun attach(): FoldEffectView? {
        view?.let { return it }
        if (!Settings.canDrawOverlays(this)) return null

        val v = FoldEffectView(this).apply { movingSideRight = Prefs.movingSideRight(this@FoldOverlayService) }
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
        }
        return try {
            wm.addView(v, p)
            view = v
            params = p
            lastBlur = -1
            v
        } catch (e: Exception) {
            null
        }
    }

    private fun detach() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
        params = null
        lastBlur = -1
    }

    // ---- Foreground notification ----

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
