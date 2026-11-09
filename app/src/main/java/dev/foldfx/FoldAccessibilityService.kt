package dev.foldfx

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.Executors

/**
 * Only used to take a single screenshot when a fold starts, and to host the effect as an
 * accessibility overlay (which Android lets be fully opaque, unlike normal app overlays).
 * Screenshots stay in memory for one animation and are never saved or sent.
 */
class FoldAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: FoldAccessibilityService? = null
            private set

        private val worker = Executors.newSingleThreadExecutor()
        private val main = Handler(Looper.getMainLooper())

        /** Takes one screenshot of the main display. cb runs on the main thread (null on failure). */
        fun capture(cb: (Bitmap?) -> Unit): Boolean {
            val s = instance ?: return false
            if (Build.VERSION.SDK_INT < 30) return false
            return try {
                s.takeScreenshot(Display.DEFAULT_DISPLAY, worker, object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        val buf = result.hardwareBuffer
                        val bmp = try {
                            Bitmap.wrapHardwareBuffer(buf, result.colorSpace)
                                ?.copy(Bitmap.Config.ARGB_8888, false)
                        } catch (e: Exception) {
                            null
                        } finally {
                            buf.close()
                        }
                        main.post { cb(bmp) }
                    }

                    override fun onFailure(errorCode: Int) {
                        main.post {
                            FoldOverlayService.log("screenshot failed (code $errorCode)")
                            cb(null)
                        }
                    }
                })
                true
            } catch (e: Exception) {
                FoldOverlayService.log("screenshot error: ${e.javaClass.simpleName}")
                false
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        FoldOverlayService.log("screenshot access connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        FoldOverlayService.log("screenshot access disconnected")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}
