package pro.potoki.bekon.agent.session

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle

/**
 * Launches the Android MediaProjection system consent dialog and forwards the result
 * to SessionService. Needed because Android 11+ forbids background activity starts, so
 * the consent must come from a foreground Activity started by the user's tap on "Экран".
 */
class CapturePermissionActivity : Activity() {
    private companion object {
        const val REQ_CAPTURE = 4001
        const val EXTRA_REQUEST_CODE = "request_code"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            startActivityForResult(mgr.createScreenCaptureIntent(), REQ_CAPTURE)
        } catch (e: Exception) {
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CAPTURE && resultCode == RESULT_OK && data != null) {
            SessionService.onCapturePermissionResult(data, resultCode)
            if (data.getBooleanExtra(EXTRA_REQUEST_CODE, false)) {} // no-op placeholder
        }
        finish()
    }
}
