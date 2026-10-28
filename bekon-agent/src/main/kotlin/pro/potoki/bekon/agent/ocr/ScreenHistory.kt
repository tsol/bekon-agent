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
    fun offerFrame(
        bitmap: Bitmap, screenW: Int, screenH: Int, fingerprint: String?, force: Boolean = false,
        /** Already-recognized screen-space blocks for this bitmap (skips a second OCR pass). */
        blocks: List<ScreenOcr.Block>? = null,
    ): Boolean {
        if (!running) return false
        val now = System.currentTimeMillis()
        if (!force && now - lastSampleMs < DEBOUNCE_MS) return false
        if (!sampling.compareAndSet(false, true)) return false
        return try {
            val screenBlocks = blocks ?: ScreenOcr.recognize(bitmap, timeoutMs = 6_000).let {
                ScreenOcr.toScreen(it.blocks, it.imageW, it.imageH, screenW, screenH)
            }
            synchronized(lock) {
                // Same page when the new frame shares lines with the previous one (a scroll) or the
                // fingerprints overlap; only a real screen change clears the accumulated history.
                val shared = sharedLines(screenBlocks, screenH)
                val sameTitle = sameTopTitle(lastFp, fingerprint)
                if (fingerprint != null && lastFp != null && !samePage(lastFp!!, fingerprint) &&
                    shared < 2 && !sameTitle
                ) {
                    Log.i(TAG, "page fingerprint changed — clearing history")
                    frames.clear(); page.clear(); nextIndex = 0
                }
                if (fingerprint != null) lastFp = fingerprint
                val prevFrame = frames.lastOrNull()
                val offsetY = when {
                    prevFrame == null -> 0
                    // Same titled screen but nothing in common in the scrolling middle (a long fling):
                    // assume it moved on by most of a screen.
                    else -> estimateOffsetY(screenBlocks, screenH) ?: (prevFrame.offsetY + (screenH * 0.7f).toInt())
                }
                val fi = nextIndex++
                Log.d(TAG, "frame $fi blocks=${screenBlocks.size} shared=$shared sameTitle=$sameTitle offset=$offsetY")
                frames += Frame(fi, now, offsetY, fingerprint ?: "", screenBlocks)
                while (frames.size > MAX_FRAMES) frames.removeAt(0)
                val sb = (screenH * 0.035f).toInt()
                val moved = prevFrame != null && offsetY != prevFrame.offsetY
                for (b in screenBlocks) {
                    if (b.bounds[1] < sb) continue // status bar: clock/battery are not page content
                    // Sticky toolbars/tabs stay put while the list scrolls: keep only their first copy.
                    if (moved && prevFrame!!.blocks.any { normalize(it.text) == normalize(b.text) && abs(it.bounds[1] - b.bounds[1]) < 16 }) continue
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
            TextMatch.matches(it.text, q, contains)
        }.sortedBy { it.pageBounds[1] }
    }

    /** Rough guidance: positive = content is further down (user should swipe up). */
    fun scrollHintFor(query: String, viewportTop: Int, viewportBottom: Int): String? {
        val hits = findText(query)
        if (hits.isEmpty()) return null
        val off = currentOffset()
        val y = hits.minByOrNull { abs((it.pageBounds[1] - off) - (viewportTop + viewportBottom) / 2) }!!.pageBounds[1] - off
        return when {
            y < viewportTop - 40 -> "above (swipe down / scroll up to reveal)"
            y > viewportBottom + 40 -> "below (swipe up / scroll down to reveal)"
            else -> "visible"
        }
    }

    /** Fingerprints overlap enough (token-level, any band) to be the same scrollable page. */
    internal fun samePage(a: String, b: String): Boolean {
        if (a == b) return true
        fun toks(s: String) = s.split('|').flatMap { seg -> seg.substringAfter(':').split(',') }
            .map { it.trim() }.filter { it.length >= 3 }.toSet()
        val ta = toks(a); val tb = toks(b)
        if (ta.isEmpty() || tb.isEmpty()) return false
        val inter = ta.intersect(tb).size
        return inter >= 2 && inter * 2 >= minOf(ta.size, tb.size)
    }

    /** Both fingerprints carry the same screen title (top band) token: same screen, just scrolled. */
    internal fun sameTopTitle(a: String?, b: String?): Boolean {
        if (a == null || b == null) return false
        fun top(s: String) = s.split('|').firstOrNull { it.startsWith("t:") }?.substringAfter("t:")
            ?.split(',')?.map { it.trim() }?.filter { it.length >= 4 && !it.contains(Regex("\\d{1,2}:\\d{2}")) }?.toSet() ?: emptySet()
        return top(a).intersect(top(b)).isNotEmpty()
    }

    /** Lines (>=4 chars, outside the status bar) the frame shares with the previous frame. */
    private fun sharedLines(blocks: List<ScreenOcr.Block>, screenH: Int): Int {
        val prev = frames.lastOrNull() ?: return 0
        val sb = (screenH * 0.035f).toInt()
        val prevKeys = prev.blocks.filter { it.bounds[1] >= sb }.map { normalize(it.text) }.filter { it.length >= 4 }.toSet()
        return blocks.filter { it.bounds[1] >= sb }.map { normalize(it.text) }.filter { it.length >= 4 }.toSet().count { it in prevKeys }
    }

    /** Page-space Y offset of the most recent frame (what is on screen now). */
    fun currentOffset(): Int = synchronized(lock) { frames.lastOrNull()?.offsetY ?: 0 }

    /**
     * Estimate how far this frame sits relative to the previous one by matching shared lines.
     * Returns absolute page offset for this frame (0 = first / unknown).
     */
    /** Scroll offset from lines shared with the previous frame, or null if the scrolling middle shares none. */
    private fun estimateOffsetY(blocks: List<ScreenOcr.Block>, screenH: Int): Int? {
        if (frames.isEmpty() || blocks.isEmpty()) return null
        val prev = frames.last()
        // Only the scrolling middle votes: toolbars/titles (top) and tab/nav bars (bottom) are sticky.
        val lo = (screenH * 0.12f).toInt(); val hi = (screenH * 0.9f).toInt()
        fun mid(b: ScreenOcr.Block) = b.bounds[1] >= lo && b.bounds[3] <= hi
        val sb = lo
        val prevByText = prev.blocks.filter { mid(it) }.groupBy { normalize(it.text) }
        // Vote: every shared line proposes a scroll delta; the most common delta wins (headers that
        // collapse or sticky bars move differently from the list and are out-voted).
        val votes = HashMap<Int, Int>()
        val weight = HashMap<Int, Int>()
        for (b in blocks) {
            if (!mid(b)) continue
            val key = normalize(b.text)
            if (key.length < 4) continue
            val cands = prevByText[key] ?: continue
            for (p in cands) {
                val dy = p.bounds[1] - b.bounds[1]
                if (abs(dy) > screenH * 0.85) continue
                val bucket = Math.round(dy / 12f)
                votes[bucket] = (votes[bucket] ?: 0) + 1
                weight[bucket] = (weight[bucket] ?: 0) + key.length
            }
        }
        // A lone dy=0 vote is usually a sticky element (pinned row/header in the middle band), not "no scroll".
        val best = votes.keys.maxWithOrNull(compareBy<Int>({ votes[it] ?: 0 }, { weight[it] ?: 0 })) ?: return null
        if (best == 0 && (votes[0] ?: 0) <= 1 && votes.size > 1) {
            votes.remove(0)
            val alt = votes.keys.maxWithOrNull(compareBy<Int>({ votes[it] ?: 0 }, { weight[it] ?: 0 }))!!
            return prev.offsetY + alt * 12
        }
        return prev.offsetY + best * 12
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
