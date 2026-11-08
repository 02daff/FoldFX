package dev.foldfx

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast

/**
 * Invisible activity that asks for screen capture and hands the result to the service.
 * Used by the Start button and by "auto re-enable after unlock".
 */
class CaptureActivity : Activity() {

    companion object {
        private const val REQ = 42
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ) {
            val i = Intent(this, FoldOverlayService::class.java)
            if (resultCode == RESULT_OK && data != null) {
                i.putExtra(FoldOverlayService.EXTRA_CODE, resultCode)
                i.putExtra(FoldOverlayService.EXTRA_DATA, data)
            } else {
                FoldOverlayService.log("screen capture refused")
                Toast.makeText(this, "No screen capture: blur only", Toast.LENGTH_LONG).show()
            }
            startForegroundService(i)
        }
        finish()
        overridePendingTransition(0, 0)
    }
}
