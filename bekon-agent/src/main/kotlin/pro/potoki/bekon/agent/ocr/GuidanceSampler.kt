package pro.potoki.bekon.agent.ocr

import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import pro.potoki.bekon.agent.nav.NavJournal

/**
 * While a guidance session is serving, periodically grab the latest capture frame,
 * OCR it, update NavJournal + ScreenHistory. In-memory only.
 */
object GuidanceSampler {
    private const val TAG = "GuidanceSampler"
    private var job: Job? = null

    fun start(
        scope: CoroutineScope,
        latestBitmap: () -> Bitmap?,
        screenSize: () -> Pair<Int, Int>,
        intervalMs: Long = 900L,
    ) {
        stop()
        ScreenHistory.start()
        job = scope.launch(Dispatchers.Default) {
            var lastHash = 0
            while (isActive) {
                try {
                    val bmp = latestBitmap()
                    if (bmp != null && !bmp.isRecycled) {
                        val (sw, sh) = screenSize()
                        // Cheap change gate: sample a coarse grid hash
                        val h = coarseHash(bmp)
                        if (h != lastHash) {
                            lastHash = h
                            val copy = bmp.copy(Bitmap.Config.ARGB_8888, false)
                            try {
                                val ocr = ScreenOcr.recognize(copy, timeoutMs = 5_000)
                                val screenBlocks = ScreenOcr.toScreen(ocr.blocks, ocr.imageW, ocr.imageH, sw, sh)
                                val changed = NavJournal.observe(screenBlocks, sw, sh)
                                if (changed) {
                                    try {
                                        pro.potoki.bekon.agent.coach.CoachOverlay.onScreenFingerprintChanged(
                                            NavJournal.currentFingerprint() ?: "",
                                        )
                                    } catch (_: Exception) {}
                                }
                                ScreenHistory.offerFrame(copy, sw, sh, NavJournal.currentFingerprint(), force = true)
                            } finally {
                                if (copy !== bmp) copy.recycle()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "sample: ${e.message}")
                }
                delay(intervalMs)
            }
        }
    }

    fun stop() {
        job?.cancel(); job = null
        ScreenHistory.stop()
        NavJournal.clear()
    }

    private fun coarseHash(b: Bitmap): Int {
        val w = b.width; val h = b.height
        if (w < 8 || h < 8) return 0
        var acc = 17
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                val px = b.getPixel((x + 0.5f).toInt() * w / 8, (y + 0.5f).toInt() * h / 8)
                acc = 31 * acc + ((px shr 16) and 0xFF) + ((px shr 8) and 0xFF) + (px and 0xFF)
            }
        }
        return acc
    }
}
