package pro.potoki.bekon.agent.nav

import pro.potoki.bekon.agent.ocr.ScreenOcr
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Navigation breadcrumb / step journal without a11y or UsageStats.
 * Builds a screen fingerprint from OCR (title/top texts, bottom-bar texts) to detect
 * same vs changed screen. Memory only; cleared on session stop.
 * Full flavor can still overlay real package/activity from a11y when available.
 */
object NavJournal {
    data class Step(
        val atMs: Long,
        val fingerprint: String,
        val label: String?,
        val hintSummary: String?,
        val screenChanged: Boolean,
        val a11yWindow: String? = null,
    )

    data class State(
        val fingerprint: String?,
        val label: String?,
        val changed: Boolean,
        val steps: List<Step>,
    )

    private const val MAX_STEPS = 80
    private val steps = CopyOnWriteArrayList<Step>()
    @Volatile private var currentFp: String? = null
    @Volatile private var currentLabel: String? = null
    @Volatile private var lastHint: String? = null
    @Volatile private var lastChanged: Boolean = false

    fun clear() {
        steps.clear()
        currentFp = null
        currentLabel = null
        lastHint = null
        lastChanged = false
    }

    fun noteHint(summary: String?) {
        lastHint = summary?.trim()?.take(120)
    }

    /** Agent-provided label for the current screen (MCP nav_mark). */
    fun mark(label: String, fingerprint: String? = null) {
        val fp = fingerprint ?: currentFp ?: "unknown"
        currentLabel = label.trim().take(80)
        currentFp = fp
        lastChanged = false
        steps += Step(System.currentTimeMillis(), fp, currentLabel, lastHint, screenChanged = false)
        trim()
    }

    /**
     * Ingest OCR blocks (+ optional a11y window key from full flavor).
     * Returns whether the screen fingerprint changed.
     */
    fun observe(
        blocks: List<ScreenOcr.Block>,
        screenW: Int,
        screenH: Int,
        a11yWindow: String? = null,
    ): Boolean {
        val fp = fingerprint(blocks, screenW, screenH, a11yWindow)
        val prev = currentFp
        val changed = prev != null && prev != fp && !fuzzySame(prev, fp)
        if (prev == null || changed) {
            currentFp = fp
            if (changed) currentLabel = null
            lastChanged = changed
            steps += Step(System.currentTimeMillis(), fp, currentLabel, lastHint, screenChanged = changed, a11yWindow)
            trim()
        } else {
            currentFp = fp
            lastChanged = false
        }
        return changed
    }

    fun currentFingerprint(): String? = currentFp

    fun state(limit: Int = 30): State = State(
        fingerprint = currentFp,
        label = currentLabel,
        changed = lastChanged,
        steps = steps.takeLast(limit.coerceIn(1, MAX_STEPS)),
    )

    fun fingerprint(
        blocks: List<ScreenOcr.Block>,
        screenW: Int,
        screenH: Int,
        a11yWindow: String? = null,
    ): String {
        if (blocks.isEmpty() && a11yWindow == null) return "empty"
        val topBand = (screenH * 0.18f).toInt()
        val bottomBand = (screenH * 0.82f).toInt()
        fun norm(s: String) = s.trim().lowercase().replace(Regex("\\s+"), " ").take(40)
        val top = blocks.filter { it.bounds[3] <= topBand && it.bounds[1] < topBand }
            .sortedBy { it.bounds[0] }
            .map { norm(it.text) }
            .filter { it.length >= 2 }
            .distinct()
            .take(4)
        val bottom = blocks.filter { it.bounds[1] >= bottomBand }
            .sortedBy { it.bounds[0] }
            .map { norm(it.text) }
            .filter { it.length >= 2 }
            .distinct()
            .take(4)
        // Stable mid cues: a few longer strings
        val mid = blocks.filter { it.bounds[1] in topBand until bottomBand }
            .sortedByDescending { it.text.length }
            .map { norm(it.text) }
            .filter { it.length >= 6 }
            .distinct()
            .take(3)
        val parts = ArrayList<String>()
        a11yWindow?.let { parts += "w:$it" }
        if (top.isNotEmpty()) parts += "t:" + top.joinToString(",")
        if (mid.isNotEmpty()) parts += "m:" + mid.joinToString(",")
        if (bottom.isNotEmpty()) parts += "b:" + bottom.joinToString(",")
        if (parts.isEmpty()) parts += "n:" + blocks.size
        return parts.joinToString("|")
    }

    private fun fuzzySame(a: String, b: String): Boolean {
        if (a == b) return true
        val ta = a.split('|').toSet()
        val tb = b.split('|').toSet()
        val inter = ta.intersect(tb).size
        return inter >= 2 && inter * 2 >= minOf(ta.size, tb.size)
    }

    private fun trim() {
        while (steps.size > MAX_STEPS) steps.removeAt(0)
    }
}
