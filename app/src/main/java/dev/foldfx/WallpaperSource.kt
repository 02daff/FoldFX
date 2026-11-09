package dev.foldfx

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import java.io.File

/**
 * Wallpaper for glass-box mode: an image the user picked in FoldFX, or else the phone's own
 * wallpaper if Android lets us read it.
 */
object WallpaperSource {

    private fun file(c: Context) = File(c.filesDir, "wallpaper.jpg")

    fun hasPicked(c: Context) = file(c).exists()

    /** Copies a picked image into FoldFX's private storage. */
    fun savePicked(c: Context, uri: Uri): Boolean = runCatching {
        c.contentResolver.openInputStream(uri)?.use { input ->
            file(c).outputStream().use { input.copyTo(it) }
        }
        true
    }.getOrDefault(false)

    /** Loads the wallpaper scaled to roughly maxSide pixels on its longer side, or null. */
    fun load(c: Context, maxSide: Int): Bitmap? {
        val f = file(c)
        if (f.exists()) {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, opts)
            var sample = 1
            while (maxOf(opts.outWidth, opts.outHeight) / (sample * 2) >= maxSide) sample *= 2
            val b = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
            if (b != null) return b
        }
        // The phone's own wallpaper (may be blocked by Android/ColorOS)
        return runCatching {
            val d = WallpaperManager.getInstance(c).drawable ?: return null
            if (d is BitmapDrawable && d.bitmap != null) {
                val src = d.bitmap
                val scale = (maxSide.toFloat() / maxOf(src.width, src.height)).coerceAtMost(1f)
                Bitmap.createScaledBitmap(src, (src.width * scale).toInt().coerceAtLeast(1), (src.height * scale).toInt().coerceAtLeast(1), true)
            } else {
                val w = d.intrinsicWidth.takeIf { it > 0 } ?: maxSide
                val h = d.intrinsicHeight.takeIf { it > 0 } ?: maxSide
                val scale = (maxSide.toFloat() / maxOf(w, h)).coerceAtMost(1f)
                val bmp = Bitmap.createBitmap((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                val cv = Canvas(bmp)
                d.setBounds(0, 0, bmp.width, bmp.height)
                d.draw(cv)
                bmp
            }
        }.getOrNull()
    }
}
