package pro.potoki.bekon.agent.session

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import pro.potoki.bekon.agent.SetupStep
import pro.potoki.bekon.agent.device.touch.GestureOverlay

/**
 * Part C — Coach / guided setup.
 * Trigger: screen capture works (screenshot available) AND preferably tunnel up, but at
 * least one checklist capability is still off. Path 1: a11y overlay highlight. Path 2:
 * annotated screenshot in the chat/agent. Pure coordinator, JVM-testable via lambdas.
 */
class CoachController(
    private val overlayAvailable: () -> Boolean,
    private val annotate: (Rect, String) -> Bitmap?,
    private val onHintSent: (String) -> Unit,
) {
    companion object { private const val TAG = "CoachController" }

    data class Hint(
        val step: SetupStep,
        val title: String,
        val body: String,
        val imageJpegBase64: String? = null,
        val deepLink: String,
    )

    fun hintFor(step: SetupStep): Hint? {
        if (step == SetupStep.DONE || step == SetupStep.COACH) return null
        return try {
            if (overlayAvailable()) {
                GestureOverlay.showCoach(null, step.body)
                onHintSent("overlay:" + step.label)
                Hint(step, step.label, step.body, deepLink = step.deepLink)
            } else {
                val bmp = annotate(Rect(0, 0, 1, 1), step.label)
                val b64 = bmp?.let(::bitmapToJpegB64)
                onHintSent("image:" + step.label)
                Hint(step, step.label, step.body, b64, step.deepLink)
            }
        } catch (e: Exception) {
            Log.w(TAG, "hintFor ${step.name}: ${e.message}")
            null
        }
    }

    private fun bitmapToJpegB64(bmp: Bitmap): String? {
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 60, out)
        return java.util.Base64.getEncoder().encodeToString(out.toByteArray())
    }
}
