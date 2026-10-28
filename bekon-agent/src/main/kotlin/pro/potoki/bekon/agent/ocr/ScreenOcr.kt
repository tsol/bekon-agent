package pro.potoki.bekon.agent.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * On-device ML Kit text recognition (bundled Latin model; works for most Latin + Cyrillic UI fonts).
 * Returns boxes in the bitmap's pixel space — caller maps to screen when scale != 1.
 */
object ScreenOcr {
    data class Block(
        val text: String,
        /** left, top, right, bottom in image pixels */
        val bounds: IntArray,
        val confidence: Float? = null,
    )

    data class Result(
        val blocks: List<Block>,
        val fullText: String,
        val imageW: Int,
        val imageH: Int,
        val elapsedMs: Long,
    )

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    /** Synchronous OCR for MCP/server threads. Caps work with timeoutMs. */
    fun recognize(bitmap: Bitmap, timeoutMs: Long = 8_000): Result {
        val t0 = System.currentTimeMillis()
        val image = InputImage.fromBitmap(bitmap, 0)
        val box = AtomicReference<Result?>(null)
        val err = AtomicReference<Exception?>(null)
        val latch = CountDownLatch(1)
        recognizer.process(image)
            .addOnSuccessListener { text ->
                val blocks = ArrayList<Block>()
                for (block in text.textBlocks) {
                    for (line in block.lines) {
                        val r: Rect = line.boundingBox ?: continue
                        val t = line.text?.trim().orEmpty()
                        if (t.isEmpty()) continue
                        blocks += Block(t, intArrayOf(r.left, r.top, r.right, r.bottom))
                    }
                }
                // Also keep block-level if no lines
                if (blocks.isEmpty()) {
                    for (block in text.textBlocks) {
                        val r = block.boundingBox ?: continue
                        val t = block.text?.trim().orEmpty()
                        if (t.isEmpty()) continue
                        blocks += Block(t, intArrayOf(r.left, r.top, r.right, r.bottom))
                    }
                }
                box.set(
                    Result(
                        blocks = blocks,
                        fullText = text.text?.trim().orEmpty(),
                        imageW = bitmap.width,
                        imageH = bitmap.height,
                        elapsedMs = System.currentTimeMillis() - t0,
                    ),
                )
                latch.countDown()
            }
            .addOnFailureListener { e ->
                err.set(e)
                latch.countDown()
            }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw RuntimeException("ocr timeout after ${timeoutMs}ms")
        }
        err.get()?.let { throw it }
        return box.get() ?: Result(emptyList(), "", bitmap.width, bitmap.height, System.currentTimeMillis() - t0)
    }

    /** Map image-space blocks into screen coordinates. */
    fun toScreen(blocks: List<Block>, imageW: Int, imageH: Int, screenW: Int, screenH: Int, pillInFrame: Boolean = true): List<Block> {
        if (imageW <= 0 || imageH <= 0) return OwnOverlay.filter(blocks, pillInFrame)
        val sx = screenW.toFloat() / imageW
        val sy = screenH.toFloat() / imageH
        return OwnOverlay.filter(blocks.map { b ->
            val bb = b.bounds
            b.copy(
                bounds = intArrayOf(
                    (bb[0] * sx).toInt(),
                    (bb[1] * sy).toInt(),
                    (bb[2] * sx).toInt(),
                    (bb[3] * sy).toInt(),
                ),
            )
        }, pillInFrame)
    }
}
