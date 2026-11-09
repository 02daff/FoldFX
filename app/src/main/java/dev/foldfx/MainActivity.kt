package dev.foldfx

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

/**
 * Simple, modern single-screen UI built from cards. Light and dark mode follow the system.
 */
class MainActivity : Activity(), SensorEventListener {

    companion object {
        private const val REQ_WALLPAPER = 7
        private val MATERIALS = listOf("Light frost", "Liquid glass", "Smoked", "Tinted", "Clear")
        private val ENTRANCES = listOf("Sweep", "Blur-in", "Soft fade", "Depth")
    }

    // ---- palette (light / dark) ----
    private var dark = false
    private val cBg get() = if (dark) Color.rgb(16, 17, 20) else Color.rgb(242, 243, 247)
    private val cCard get() = if (dark) Color.rgb(28, 29, 34) else Color.WHITE
    private val cText get() = if (dark) Color.rgb(236, 237, 241) else Color.rgb(22, 23, 27)
    private val cMuted get() = if (dark) Color.rgb(150, 152, 160) else Color.rgb(110, 112, 120)
    private val cChip get() = if (dark) Color.rgb(44, 46, 54) else Color.rgb(236, 237, 242)
    private val accent = Color.rgb(76, 110, 245)
    private val green = Color.rgb(52, 178, 104)
    private val orange = Color.rgb(240, 150, 40)

    private lateinit var sensors: SensorManager
    private var hinge: Sensor? = null
    private val handler = Handler(Looper.getMainLooper())

    // views updated by refresh()
    private lateinit var angleText: TextView
    private lateinit var statusPill: TextView
    private lateinit var mainButton: TextView
    private lateinit var setupRows: LinearLayout
    private lateinit var contentSeg: LinearLayout
    private lateinit var materialChips: LinearLayout
    private lateinit var entranceChips: LinearLayout
    private lateinit var halfSeg: LinearLayout
    private lateinit var wallpaperRow: View
    private lateinit var blurValue: TextView
    private lateinit var projValue: TextView
    private lateinit var logText: TextView
    private lateinit var logBox: View

    private val logUpdater = object : Runnable {
        override fun run() {
            if (logBox.visibility == View.VISIBLE) {
                logText.text = FoldOverlayService.logText().ifEmpty { "Nothing yet." }
            }
            handler.postDelayed(this, 700)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        window.statusBarColor = cBg
        window.navigationBarColor = cBg
        if (!dark) window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        sensors = getSystemService(SensorManager::class.java)
        hinge = sensors.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(32))
        }

        // ---- header ----
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "FoldFX"
            textSize = 30f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(cText)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        statusPill = TextView(this).apply {
            textSize = 13f
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        header.addView(statusPill)
        col.addView(header)

        // ---- hero: hinge angle + main button ----
        val hero = card()
        angleText = TextView(this).apply {
            textSize = 56f
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setTextColor(cText)
            gravity = Gravity.CENTER
        }
        hero.addView(angleText)
        hero.addView(label("Hinge angle", center = true))
        mainButton = primaryButton("") { toggleService() }
        hero.addView(mainButton, lp(top = 18))
        hero.addView(secondaryButton("Preview effect") {
            startForegroundService(Intent(this, FoldOverlayService::class.java).setAction(FoldOverlayService.ACTION_PREVIEW))
            handler.postDelayed({ refresh() }, 400)
        }, lp(top = 10))
        col.addView(hero, lp(top = 18))

        // ---- setup ----
        col.addView(section("Setup"))
        val setup = card()
        setupRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setup.addView(setupRows)
        col.addView(setup)

        // ---- look ----
        col.addView(section("Look"))
        val look = card()
        look.addView(label("Content"))
        contentSeg = chipRow()
        look.addView(contentSeg, lp(top = 8))
        wallpaperRow = secondaryButton("Choose wallpaper image") {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
            }
            startActivityForResult(i, REQ_WALLPAPER)
        }
        look.addView(wallpaperRow, lp(top = 10))
        look.addView(label("Material"), lp(top = 18))
        materialChips = chipRow()
        look.addView(materialChips, lp(top = 8))
        look.addView(label("Entrance"), lp(top = 18))
        entranceChips = chipRow()
        look.addView(entranceChips, lp(top = 8))
        look.addView(label("Folding half"), lp(top = 18))
        halfSeg = chipRow()
        look.addView(halfSeg, lp(top = 8))
        col.addView(look)

        // ---- tuning ----
        col.addView(section("Tuning"))
        val tuning = card()
        blurValue = TextView(this)
        tuning.addView(sliderRow("Blur", blurValue, 200, Prefs.maxBlur(this)) { v ->
            Prefs.setMaxBlur(this, v); blurValue.text = "$v px"
        })
        projValue = TextView(this)
        tuning.addView(sliderRow("Screen stays put", projValue, 100, Prefs.projection(this)) { v ->
            Prefs.setProjection(this, v); projValue.text = "$v%"
        }, lp(top = 14))
        col.addView(tuning)

        // ---- log ----
        col.addView(section("Diagnostics"))
        val diag = card()
        val logHeader = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        logHeader.addView(TextView(this).apply {
            text = "Event log"
            textSize = 16f
            setTextColor(cText)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        logHeader.addView(smallButton("Show") { b ->
            logBox.visibility = if (logBox.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            (b as TextView).text = if (logBox.visibility == View.VISIBLE) "Hide" else "Show"
            logText.text = FoldOverlayService.logText().ifEmpty { "Nothing yet." }
        })
        logHeader.addView(smallButton("Share") {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, FoldOverlayService.logText())
            }
            startActivity(Intent.createChooser(send, "Share FoldFX log"))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
        diag.addView(logHeader)
        logText = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(cMuted)
            setPadding(0, dp(10), 0, 0)
        }
        logBox = logText.apply { visibility = View.GONE }
        diag.addView(logBox)
        col.addView(diag)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(cBg)
            isFillViewport = true
            addView(col)
        })

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    // =====================================================================
    // State
    // =====================================================================

    private fun toggleService() {
        if (FoldOverlayService.running) {
            stopService(Intent(this, FoldOverlayService::class.java))
        } else {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Allow \"Display over other apps\" first", Toast.LENGTH_SHORT).show()
                openOverlaySettings()
                return
            }
            startForegroundService(Intent(this, FoldOverlayService::class.java))
        }
        handler.postDelayed({ refresh() }, 450)
    }

    private fun restartIfRunning() {
        if (FoldOverlayService.running) startForegroundService(Intent(this, FoldOverlayService::class.java))
    }

    private fun refresh() {
        val running = FoldOverlayService.running
        statusPill.text = if (running) "● Active" else "● Off"
        statusPill.setTextColor(if (running) green else cMuted)
        statusPill.background = rounded(if (running) Color.argb(36, 52, 178, 104) else cChip, 50)

        mainButton.text = if (running) "Turn off" else "Turn on"
        mainButton.background = rounded(if (running) cChip else accent, 16)
        mainButton.setTextColor(if (running) cText else Color.WHITE)

        if (hinge == null) angleText.text = "—"

        // setup rows
        setupRows.removeAllViews()
        val overlayOk = Settings.canDrawOverlays(this)
        val a11yOk = FoldAccessibilityService.instance != null
        val glass = Prefs.glassMode(this)
        setupRows.addView(setupRow(
            "Hinge sensor", if (hinge != null) "Found" else "Not found on this phone",
            if (hinge != null) green else orange, null, null
        ))
        setupRows.addView(setupRow(
            "Display over other apps", if (overlayOk) "Allowed" else "Needed for the effect",
            if (overlayOk) green else orange, if (overlayOk) null else "Allow"
        ) { openOverlaySettings() })
        setupRows.addView(setupRow(
            "Screenshot access",
            when {
                a11yOk -> "On"
                glass -> "Optional in glass mode"
                else -> "Needed for screenshot mode"
            },
            if (a11yOk) green else if (glass) cMuted else orange, if (a11yOk) null else "Open"
        ) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
        setupRows.addView(setupRow(
            "Background running", "Turn off battery optimization", cMuted, "Open"
        ) { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) })
        val wm = getSystemService(WindowManager::class.java)
        val blurOk = Build.VERSION.SDK_INT >= 31 && wm.isCrossWindowBlurEnabled
        if (!blurOk) {
            setupRows.addView(setupRow(
                "System blur", "Off (battery saver or developer setting)", orange, "Open"
            ) { runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) } })
        }

        // chips
        fillChips(contentSeg, listOf("Screenshot", "Glass box"), if (glass) 1 else 0) { i ->
            Prefs.setGlassMode(this, i == 1); restartIfRunning(); refresh()
        }
        wallpaperRow.visibility = if (glass) View.VISIBLE else View.GONE
        fillChips(materialChips, MATERIALS, Prefs.material(this)) { i -> Prefs.setMaterial(this, i); refresh() }
        fillChips(entranceChips, ENTRANCES, Prefs.entrance(this)) { i -> Prefs.setEntrance(this, i); refresh() }
        fillChips(halfSeg, listOf("Left", "Right"), if (Prefs.movingHalfLeft(this)) 0 else 1) { i ->
            Prefs.setMovingHalfLeft(this, i == 0); refresh()
        }
        blurValue.text = "${Prefs.maxBlur(this)} px"
        projValue.text = "${Prefs.projection(this)}%"
    }

    private fun openOverlaySettings() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_WALLPAPER && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val ok = WallpaperSource.savePicked(this, uri)
            Toast.makeText(this, if (ok) "Wallpaper saved" else "Couldn't read that image", Toast.LENGTH_SHORT).show()
            if (ok) restartIfRunning()
        }
    }

    private val blurListener = java.util.function.Consumer<Boolean> { refresh() }

    override fun onResume() {
        super.onResume()
        hinge?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        if (Build.VERSION.SDK_INT >= 31) {
            getSystemService(WindowManager::class.java).addCrossWindowBlurEnabledListener(mainExecutor, blurListener)
        }
        handler.post(logUpdater)
        refresh()
    }

    override fun onPause() {
        super.onPause()
        sensors.unregisterListener(this)
        handler.removeCallbacks(logUpdater)
        if (Build.VERSION.SDK_INT >= 31) {
            getSystemService(WindowManager::class.java).removeCrossWindowBlurEnabledListener(blurListener)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        angleText.text = "%.0f°".format(event.values[0])
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // =====================================================================
    // Small UI kit
    // =====================================================================

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(cCard, 22)
        setPadding(dp(18), dp(18), dp(18), dp(18))
        elevation = if (dark) 0f else dp(1).toFloat()
    }

    private fun section(title: String) = TextView(this).apply {
        text = title
        textSize = 14f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(cMuted)
        setPadding(dp(6), dp(26), 0, dp(10))
    }

    private fun label(t: String, center: Boolean = false) = TextView(this).apply {
        text = t
        textSize = 13f
        setTextColor(cMuted)
        if (center) gravity = Gravity.CENTER
    }

    private fun primaryButton(t: String, onClick: () -> Unit) = TextView(this).apply {
        text = t
        textSize = 16f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        background = rounded(accent, 16)
        setPadding(0, dp(15), 0, dp(15))
        isClickable = true
        foreground = rippleFg()
        setOnClickListener { onClick() }
    }

    private fun secondaryButton(t: String, onClick: () -> Unit) = TextView(this).apply {
        text = t
        textSize = 15f
        gravity = Gravity.CENTER
        setTextColor(cText)
        background = rounded(cChip, 16)
        setPadding(0, dp(13), 0, dp(13))
        isClickable = true
        foreground = rippleFg()
        setOnClickListener { onClick() }
    }

    private fun smallButton(t: String, onClick: (View) -> Unit) = TextView(this).apply {
        text = t
        textSize = 13f
        setTextColor(accent)
        background = rounded(cChip, 50)
        setPadding(dp(14), dp(7), dp(14), dp(7))
        isClickable = true
        foreground = rippleFg()
        setOnClickListener { onClick(it) }
    }

    private fun rippleFg(): android.graphics.drawable.Drawable {
        val attrs = intArrayOf(android.R.attr.selectableItemBackground)
        val ta = obtainStyledAttributes(attrs)
        val d = ta.getDrawable(0)!!
        ta.recycle()
        return d
    }

    private fun chipRow() = FlowLayout(this, dp(8))

    private fun fillChips(row: LinearLayout, items: List<String>, selected: Int, onPick: (Int) -> Unit) {
        row.removeAllViews()
        items.forEachIndexed { i, name ->
            val on = i == selected
            row.addView(TextView(this).apply {
                text = name
                textSize = 14f
                setTextColor(if (on) Color.WHITE else cText)
                background = rounded(if (on) accent else cChip, 50)
                setPadding(dp(16), dp(9), dp(16), dp(9))
                isClickable = true
                foreground = rippleFg()
                setOnClickListener { onPick(i) }
            })
        }
    }

    private fun setupRow(title: String, sub: String, dotColor: Int, action: String?, onAction: (() -> Unit)? = null): View {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        row.addView(View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(dotColor) }
        }, LinearLayout.LayoutParams(dp(10), dp(10)).apply { rightMargin = dp(14) })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply { text = title; textSize = 15f; setTextColor(cText) })
        texts.addView(TextView(this).apply { text = sub; textSize = 13f; setTextColor(cMuted) })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (action != null && onAction != null) row.addView(smallButton(action) { onAction() })
        return row
    }

    private fun sliderRow(title: String, value: TextView, max: Int, current: Int, onChange: (Int) -> Unit): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val top = LinearLayout(this)
        top.addView(TextView(this).apply { text = title; textSize = 15f; setTextColor(cText) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        value.textSize = 14f
        value.setTextColor(cMuted)
        top.addView(value)
        box.addView(top)
        box.addView(SeekBar(this).apply {
            this.max = max
            progress = current
            progressTintList = ColorStateList.valueOf(accent)
            thumbTintList = ColorStateList.valueOf(accent)
            progressBackgroundTintList = ColorStateList.valueOf(cChip)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) = onChange(v)
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }, lp(top = 6))
        return box
    }
}

/** A row that wraps its children onto new lines (for chips). */
class FlowLayout(context: android.content.Context, private val gap: Int) : LinearLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxW = MeasureSpec.getSize(widthMeasureSpec)
        var x = 0
        var y = 0
        var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            c.measure(MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.UNSPECIFIED)
            if (x > 0 && x + c.measuredWidth > maxW) {
                x = 0; y += rowH + gap; rowH = 0
            }
            x += c.measuredWidth + gap
            rowH = maxOf(rowH, c.measuredHeight)
        }
        setMeasuredDimension(maxW, y + rowH)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l
        var x = 0
        var y = 0
        var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (x > 0 && x + c.measuredWidth > maxW) {
                x = 0; y += rowH + gap; rowH = 0
            }
            c.layout(x, y, x + c.measuredWidth, y + c.measuredHeight)
            x += c.measuredWidth + gap
            rowH = maxOf(rowH, c.measuredHeight)
        }
    }
}
