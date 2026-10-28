package pro.potoki.bekon.agent.coach

/**
 * Text colour for the character, computed from its EFFECTIVE fill only (WCAG relative luminance):
 * light text on a dark fill, dark text on a light fill, always ≥ 4.5:1. The border is fixed
 * (white stroke + black keyline). If the fill under the text is translucent, the effective fill is
 * the fill composited over the worst-case background (black and white are both checked), so the
 * result holds on any wallpaper. Pure, unit-tested.
 */
object ContrastPicker {
    const val LIGHT = 0xFFFFFFFF.toInt()
    const val DARK = 0xFF000000.toInt()   // pure black: every fill reaches ≥ 4.5:1 with one of the two

    fun lum(c: Int): Double {
        fun ch(v: Int): Double { val s = v / 255.0; return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4) }
        return 0.2126 * ch((c shr 16) and 0xFF) + 0.7152 * ch((c shr 8) and 0xFF) + 0.0722 * ch(c and 0xFF)
    }
    fun ratio(a: Int, b: Int): Double { val la = lum(a); val lb = lum(b); return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05) }

    /** [fill] at [alpha] (0–255) over an opaque [bg] (source-over). */
    fun composite(fill: Int, alpha: Int, bg: Int): Int {
        val a = alpha.coerceIn(0, 255) / 255.0
        fun mix(s: Int) = (((fill shr s) and 0xFF) * a + ((bg shr s) and 0xFF) * (1 - a) + 0.5).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    /** Worst contrast of [text] on [fill]@[alpha] over any background (black and white bound it). */
    fun worstRatio(text: Int, fill: Int, alpha: Int = 255): Double =
        minOf(ratio(text, composite(fill, alpha, DARK)), ratio(text, composite(fill, alpha, LIGHT)))

    /** Light text whenever it reaches 4.5:1 on the effective fill (dark fills), otherwise dark text. */
    fun textOn(fill: Int, alpha: Int = 255): Int {
        val l = worstRatio(LIGHT, fill, alpha); val k = worstRatio(DARK, fill, alpha)
        return if (l >= 4.5 || l >= k) LIGHT else DARK
    }
}

/** The character's state fills (pure ints so they are unit-testable); all dark → light text. */
object StateFills {
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    val IDLE = rgb(84, 96, 116)
    val WORKING = rgb(33, 98, 196)
    val ATTENTION = rgb(180, 84, 0)      // waiting / tap / scroll: burnt orange (white text ≥ 4.5:1)
    val DONE = rgb(24, 118, 58)
    val ERROR = rgb(200, 45, 45)
    val ALL = listOf(IDLE, WORKING, ATTENTION, DONE, ERROR)
    /** Fill opacity under the text (head of the tail gradient, pill, ring, pocket): opaque. */
    const val TEXT_ALPHA = 255
}
