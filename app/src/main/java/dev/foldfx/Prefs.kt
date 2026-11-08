package dev.foldfx

import android.content.Context

object Prefs {
    private const val FILE = "foldfx"

    fun movingSideRight(c: Context): Boolean =
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("right", true)

    fun setMovingSideRight(c: Context, value: Boolean) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean("right", value).apply()
    }

    /** Max background blur in px (Android 12+ cross-window blur). */
    fun maxBlur(c: Context): Int =
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getInt("blur2", 120)

    fun setMaxBlur(c: Context, value: Int) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putInt("blur2", value).apply()
    }
}
