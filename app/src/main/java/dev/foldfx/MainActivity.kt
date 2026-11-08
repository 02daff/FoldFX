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
    private lateinit var sideButton: Button
    private lateinit var blurLabel: TextView

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

        col.addView(button("2. Start fold animation") {
            startForegroundService(Intent(this, FoldOverlayService::class.java))
            handler.postDelayed({ refresh() }, 400)
        })

        col.addView(button("Stop fold animation") {
            stopService(Intent(this, FoldOverlayService::class.java))
            handler.postDelayed({ refresh() }, 400)
        })

        sideButton = button("") {
            Prefs.setMovingSideRight(this, !Prefs.movingSideRight(this))
            if (FoldOverlayService.running) {
                startForegroundService(Intent(this, FoldOverlayService::class.java))
            }
            refresh()
        }
        col.addView(sideButton)

        blurLabel = TextView(this).apply { setPadding(0, pad, 0, 0) }
        col.addView(blurLabel)
        col.addView(SeekBar(this).apply {
            max = 150
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

        col.addView(button("Turn off battery optimization (keeps it running)") {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        })

        setContentView(ScrollView(this).apply { addView(col) })

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        hinge?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        refresh()
    }

    override fun onPause() {
        super.onPause()
        sensors.unregisterListener(this)
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
            appendLine("Background blur: " + if (blurOk) "supported" else "not available (frost only)")
            appendLine("Animation service: " + if (FoldOverlayService.running) "running" else "stopped")
            append("\nFold the phone slowly and watch the angle above. It should change smoothly.")
        }
        sideButton.text = "Moving half: " + (if (Prefs.movingSideRight(this)) "right" else "left") + " (tap to flip)"
        blurLabel.text = "Max blur: ${Prefs.maxBlur(this)} px"
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
