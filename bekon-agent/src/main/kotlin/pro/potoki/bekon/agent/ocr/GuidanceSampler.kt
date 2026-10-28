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
        intervalMs: Long = 450L,
    ) {
        stop()
        ScreenHistory.start()
        job = scope.launch(Dispatchers.Default) {
            var lastDone: IntArray? = null
            var prevSig: IntArray? = null
            while (isActive) {
                try {
                    val bmp = latestBitmap()
                    if (bmp != null && !bmp.isRecycled) {
                        val (sw, sh) = screenSize()
                        // Cheap change gate on a coarse luma grid (status bar excluded, small noise tolerated).
                        // Only OCR a settled frame (no change since the previous tick): frames grabbed
                        // mid-scroll are motion-blurred and produce garbage text.
                        val sig = signature(bmp)
                        val settled = prevSig != null && near(sig, prevSig!!)
                        prevSig = sig
                        if (settled && (lastDone == null || !near(sig, lastDone!!))) {
                            lastDone = sig
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
                                pro.potoki.bekon.agent.coach.FreeSpot.updateFromFrame(copy, sw, sh, screenBlocks.map { it.bounds })
                                ScreenHistory.offerFrame(copy, sw, sh, NavJournal.currentFingerprint(), force = true, blocks = screenBlocks)
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

    private const val GRID = 12

    private fun signature(b: Bitmap): IntArray {
        val w = b.width; val h = b.height
        val out = IntArray(GRID * GRID)
        if (w < GRID || h < GRID) return out
        val top = (h * 0.04f).toInt()
        // Our own character moving must not look like a screen change (that was the jitter loop).
        val own = OwnOverlay.pillRect
        val sx = w.toFloat() / android.content.res.Resources.getSystem().displayMetrics.widthPixels.coerceAtLeast(1)
        for (y in 0 until GRID) for (x in 0 until GRID) {
            val qx = ((x + 0.5f) * w / GRID).toInt(); val qy = (top + (y + 0.5f) * (h - top) / GRID).toInt()
            if (own != null && own.contains((qx / sx).toInt(), (qy / sx).toInt())) { out[y * GRID + x] = -1; continue }
            val px = b.getPixel(((x + 0.5f) * w / GRID).toInt().coerceAtMost(w - 1), (top + (y + 0.5f) * (h - top) / GRID).toInt().coerceAtMost(h - 1))
            out[y * GRID + x] = (((px shr 16) and 0xFF) * 3 + ((px shr 8) and 0xFF) * 6 + (px and 0xFF)) / 10
        }
        return out
    }

    private fun near(a: IntArray, b: IntArray): Boolean {
        var bad = 0
        for (i in a.indices) if (a[i] >= 0 && b[i] >= 0 && kotlin.math.abs(a[i] - b[i]) > 10) bad++
        return bad <= 1
    }
}
