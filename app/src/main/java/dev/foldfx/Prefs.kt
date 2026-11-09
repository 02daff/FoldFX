package dev.foldfx

import android.content.Context

object Prefs {
    private const val FILE = "foldfx"

    /** Max background blur in px (Android 12+ cross-window blur). */
    fun maxBlur(c: Context): Int =
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getInt("blur3", 72)

    fun setMaxBlur(c: Context, value: Int) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putInt("blur3", value).apply()
    }

    /** Which half of the big screen swings when you fold. */
    fun movingHalfLeft(c: Context): Boolean =
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("left", true)

    fun setMovingHalfLeft(c: Context, value: Boolean) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean("left", value).apply()
    }

    /** How much the content stays fixed in space while the half swings, 0..100 %. */
    fun projection(c: Context): Int =
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getInt("proj", 35)

    fun setProjection(c: Context, value: Int) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putInt("proj", value).apply()
    }
}
