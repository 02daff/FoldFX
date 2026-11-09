package dev.foldfx

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

class MainActivity : Activity(), SensorEventListener {


    private lateinit var sensors: SensorManager
    private var hinge: Sensor? = null

    private lateinit var angleText: TextView
    private lateinit var statusText: TextView
    private lateinit var blurLabel: TextView
    private lateinit var logText: TextView
    private lateinit var halfButton: Button
    private lateinit var projLabel: TextView
    private val logUpdater = object : Runnable {
        override fun run() {
            logText.text = FoldOverlayService.logText().ifEmpty { "(nothing yet)" }
            handler.postDelayed(this, 500)
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sensors = getSystemService(SensorManager::class.java)
        hinge = sensors.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)

        val pad = dp(20)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        col.addView(TextView(this).apply {
            text = "FoldFX"
            textSize = 28f
        })

        angleText = TextView(this).apply {
            textSize = 48f
            gravity = Gravity.CENTER
            setPadding(0, pad, 0, pad)
        }
        col.addView(angleText)

        statusText = TextView(this).apply { textSize = 15f }
        col.addView(statusText)

        col.addView(button("1. Allow \"display over other apps\"") {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        })

        col.addView(button("Turn on screenshot access (Accessibility)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })

        col.addView(button("2. Start fold animation") {
            startForegroundService(Intent(this, FoldOverlayService::class.java))
            handler.postDelayed({ refresh() }, 500)
        })

        col.addView(button("Test effect (no folding needed)") {
            startForegroundService(
                Intent(this, FoldOverlayService::class.java).setAction(FoldOverlayService.ACTION_PREVIEW)
            )
            handler.postDelayed({ refresh() }, 400)
        })

        halfButton = button("") {
            Prefs.setMovingHalfLeft(this, !Prefs.movingHalfLeft(this))
            refresh()
        }
        col.addView(halfButton)

        col.addView(button("Open Developer options (blur setting)") {
            runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
        })

        col.addView(button("Stop fold animation") {
            stopService(Intent(this, FoldOverlayService::class.java))
            handler.postDelayed({ refresh() }, 400)
        })

        blurLabel = TextView(this).apply { setPadding(0, pad, 0, 0) }
        col.addView(blurLabel)
        col.addView(SeekBar(this).apply {
            max = 200
            progress = Prefs.maxBlur(this@MainActivity)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, value: Int, fromUser: Boolean) {
                    Prefs.setMaxBlur(this@MainActivity, value)
                    blurLabel.text = "Max blur: $value px"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })

        projLabel = TextView(this).apply { setPadding(0, pad, 0, 0) }
        col.addView(projLabel)
        col.addView(SeekBar(this).apply {
            max = 100
            progress = Prefs.projection(this@MainActivity)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, value: Int, fromUser: Boolean) {
                    Prefs.setProjection(this@MainActivity, value)
                    projLabel.text = "Content stays fixed in space: $value%"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })

        col.addView(button("Turn off battery optimization (keeps it running)") {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        })

        col.addView(TextView(this).apply {
            text = "Event log (do a full close + open, then share this)"
            textSize = 16f
            setPadding(0, pad, 0, dp(4))
        })
        col.addView(button("Share log") {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, FoldOverlayService.logText())
            }
            startActivity(Intent.createChooser(send, "Share FoldFX log"))
        })
        logText = TextView(this).apply {
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, dp(8), 0, pad)
        }
        col.addView(logText)

        setContentView(ScrollView(this).apply { addView(col) })

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private val blurListener = java.util.function.Consumer<Boolean> { refresh() }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= 31) {
            getSystemService(WindowManager::class.java).addCrossWindowBlurEnabledListener(mainExecutor, blurListener)
        }
        hinge?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        handler.post(logUpdater)
        refresh()
    }

    override fun onPause() {
        super.onPause()
        if (Build.VERSION.SDK_INT >= 31) {
            getSystemService(WindowManager::class.java).removeCrossWindowBlurEnabledListener(blurListener)
        }
        sensors.unregisterListener(this)
        handler.removeCallbacks(logUpdater)
    }

    override fun onSensorChanged(event: SensorEvent) {
        angleText.text = "%.0f°".format(event.values[0])
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun refresh() {
        val wm = getSystemService(WindowManager::class.java)
        val blurOk = Build.VERSION.SDK_INT >= 31 && wm.isCrossWindowBlurEnabled

        if (hinge == null) angleText.text = "No hinge sensor"
        else if (angleText.text.isNullOrEmpty()) angleText.text = "—"

        statusText.text = buildString {
            appendLine("Hinge sensor: " + (hinge?.let { "found (${it.name})" } ?: "NOT FOUND, this won't work"))
            appendLine("Overlay permission: " + if (Settings.canDrawOverlays(this@MainActivity)) "granted" else "not granted")
            appendLine("Background blur: " + when {
                blurOk -> "ON"
                Build.VERSION.SDK_INT < 31 -> "OFF (needs Android 12+)"
                else -> "OFF. Turn off Battery saver / power saving, and in Developer options enable \"Allow window-level blurs\". Frost-only until then."
            })
            appendLine("Animation service: " + if (FoldOverlayService.running) "running" else "stopped")
            appendLine("Screenshot access: " + if (FoldAccessibilityService.instance != null) "ON (real screen in the effect)" else "off (grey placeholder)")
            append("\nFold the phone slowly and watch the angle above. It should change smoothly.")
        }
        blurLabel.text = "Max blur: ${Prefs.maxBlur(this)} px"
        projLabel.text = "Content stays fixed in space: ${Prefs.projection(this)}%"
        halfButton.text = "Half that swings: " + (if (Prefs.movingHalfLeft(this)) "left" else "right") + " (tap to change)"
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
