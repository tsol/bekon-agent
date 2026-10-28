package pro.potoki.bekon.agent.coach

object ScreenKey {
    /**
     * Guidance fingerprints come from OCR, and our own marks/captions bleed into it ("settings" →
     * "settingso", a caption word added). Same screen when most old words (letters only, ≥3) still
     * appear in the new one (prefix-tolerant). Non-OCR keys (a11y windows) compare exactly.
     */
    fun sameScreen(a: String?, b: String?): Boolean {
        if (a == null || b == null) return a == b
        if (a == b) return true
        if (!a.startsWith("fp:") || !b.startsWith("fp:")) return false
        fun words(s: String) = Regex("\\p{L}{3,}").findAll(s.removePrefix("fp:").replace(Regex("\\b[tmb]:"), " ").lowercase())
            .map { it.value }.toSet()
        val wa = words(a); val wb = words(b)
        if (wa.isEmpty() || wb.isEmpty()) return false
        val hit = wa.count { w -> wb.any { it == w || (minOf(it.length, w.length) >= 4 && (it.startsWith(w) || w.startsWith(it))) } }
        return hit * 2 >= wa.size
    }

}
