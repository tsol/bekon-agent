package pro.potoki.bekon.agent.ocr

import android.graphics.Bitmap
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * In-memory scroll-aware OCR accumulation for guidance mode.
 * Samples MediaProjection frames while the screen changes, OCRs them, stitches by matching
 * text lines, keeps per-frame Y offsets. Cleared on session stop / app-fingerprint change.
 * Never written to disk.
 */
object ScreenHistory {
    private const val TAG = "ScreenHistory"
    private const val MAX_BLOCKS = 800
    private const val MAX_FRAMES = 40
    private const val DEBOUNCE_MS = 450L

    data class AccBlock(
        val text: String,
        /** Screen-space box on the frame where it was seen (y already offset into page space). */
        val pageBounds: IntArray,
        val frameIndex: Int,
        val atMs: Long,
    )

    data class Frame(
        val index: Int,
        val atMs: Long,
        val offsetY: Int,
        val fingerprint: String,
        val blocks: List<ScreenOcr.Block>,
    )

    data class Snapshot(
        val frames: Int,
        val offsetMin: Int,
        val offsetMax: Int,
        val blocks: List<AccBlock>,
        val fullText: String,
        val fingerprint: String?,
    )

    private val lock = Any()
    private val frames = ArrayList<Frame>()
    private val page = ArrayList<AccBlock>()
    private var nextIndex = 0
    private var lastSampleMs = 0L
    private var lastFp: String? = null
    @Volatile private var running = false
    private val sampling = AtomicBoolean(false)

    fun start() { running = true }
    fun stop() { running = false; clear() }

    fun clear() = synchronized(lock) {
        frames.clear(); page.clear(); nextIndex = 0; lastSampleMs = 0L; lastFp = null
    }

    fun isRunning(): Boolean = running

    /**
     * Offer a new screen bitmap (already screen-sized or scaled). Debounced.
     * [fingerprint] from NavJournal — if it looks like a different "app/page", clear and restart.
     */
    fun offerFrame(bitmap: Bitmap, screenW: Int, screenH: Int, fingerprint: String?, force: Boolean = false): Boolean {
        if (!running) return false
        val now = System.currentTimeMillis()
        if (!force && now - lastSampleMs < DEBOUNCE_MS) return false
        if (!sampling.compareAndSet(false, true)) return false
        return try {
            val ocr = ScreenOcr.recognize(bitmap, timeoutMs = 6_000)
            val screenBlocks = ScreenOcr.toScreen(ocr.blocks, ocr.imageW, ocr.imageH, screenW, screenH)
            synchronized(lock) {
                if (fingerprint != null && lastFp != null && !samePage(lastFp!!, fingerprint)) {
                    Log.i(TAG, "page fingerprint changed — clearing history")
                    frames.clear(); page.clear(); nextIndex = 0
                }
                if (fingerprint != null) lastFp = fingerprint
                val offsetY = estimateOffsetY(screenBlocks, screenH)
                val fi = nextIndex++
                frames += Frame(fi, now, offsetY, fingerprint ?: "", screenBlocks)
                while (frames.size > MAX_FRAMES) frames.removeAt(0)
                for (b in screenBlocks) {
                    val pb = intArrayOf(b.bounds[0], b.bounds[1] + offsetY, b.bounds[2], b.bounds[3] + offsetY)
                    if (!isDuplicate(b.text, pb)) {
                        page += AccBlock(b.text, pb, fi, now)
                    }
                }
                while (page.size > MAX_BLOCKS) page.removeAt(0)
                lastSampleMs = now
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "offerFrame: ${e.message}")
            false
        } finally {
            sampling.set(false)
        }
    }

    fun snapshot(): Snapshot = synchronized(lock) {
        val texts = LinkedHashSet<String>()
        val ordered = page.sortedWith(compareBy({ it.pageBounds[1] }, { it.pageBounds[0] }))
        for (b in ordered) texts += b.text
        Snapshot(
            frames = frames.size,
            offsetMin = frames.minOfOrNull { it.offsetY } ?: 0,
            offsetMax = frames.maxOfOrNull { it.offsetY } ?: 0,
            blocks = ordered.toList(),
            fullText = texts.joinToString("\n"),
            fingerprint = lastFp,
        )
    }

    fun findText(query: String, contains: Boolean = true): List<AccBlock> = synchronized(lock) {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        page.filter {
            if (contains) it.text.contains(q, ignoreCase = true)
            else it.text.equals(q, ignoreCase = true)
        }.sortedBy { it.pageBounds[1] }
    }

    /** Rough guidance: positive = content is further down (user should swipe up). */
    fun scrollHintFor(query: String, viewportTop: Int, viewportBottom: Int): String? {
        val hits = findText(query)
        if (hits.isEmpty()) return null
        val y = hits.first().pageBounds[1]
        return when {
            y < viewportTop - 40 -> "above (swipe down / scroll up to reveal)"
            y > viewportBottom + 40 -> "below (swipe up / scroll down to reveal)"
            else -> "visible"
        }
    }

    private fun samePage(a: String, b: String): Boolean {
        if (a == b) return true
        // Allow small OCR jitter: share a prefix token
        val ta = a.split('|').take(3)
        val tb = b.split('|').take(3)
        return ta.count { it in tb } >= 2
    }

    /**
     * Estimate how far this frame sits relative to the previous one by matching shared lines.
     * Returns absolute page offset for this frame (0 = first / unknown).
     */
    private fun estimateOffsetY(blocks: List<ScreenOcr.Block>, screenH: Int): Int {
        if (frames.isEmpty() || blocks.isEmpty()) return 0
        val prev = frames.last()
        var bestDy: Int? = null
        var bestScore = 0
        val prevByText = prev.blocks.groupBy { normalize(it.text) }
        for (b in blocks) {
            val key = normalize(b.text)
            if (key.length < 4) continue
            val cands = prevByText[key] ?: continue
            for (p in cands) {
                val dy = p.bounds[1] - b.bounds[1] // how much content moved down on screen
                // Prefer small scroll deltas
                if (abs(dy) > screenH * 0.85) continue
                val score = key.length
                if (score > bestScore) {
                    bestScore = score
                    bestDy = dy
                }
            }
        }
        val delta = bestDy ?: 0
        return prev.offsetY + delta
    }

    private fun isDuplicate(text: String, pageBounds: IntArray): Boolean {
        val n = normalize(text)
        for (e in page) {
            if (normalize(e.text) != n) continue
            if (abs(e.pageBounds[1] - pageBounds[1]) < 24 &&
                abs(e.pageBounds[0] - pageBounds[0]) < 40
            ) return true
        }
        return false
    }

    private fun normalize(s: String) = s.trim().lowercase().replace(Regex("\\s+"), " ")
}
