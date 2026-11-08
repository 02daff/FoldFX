package dev.foldfx

import android.content.Context

object Prefs {
    private const val FILE = "foldfx"

    /** Max background blur in px (Android 12+ cross-window blur). */
    fun maxBlur(c: Context): Int =
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getInt("blur2", 120)

    fun setMaxBlur(c: Context, value: Int) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putInt("blur2", value).apply()
    }

    fun autoReenable(c: Context): Boolean =
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("auto", false)

    fun setAutoReenable(c: Context, value: Boolean) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean("auto", value).apply()
    }
}
