package pro.potoki.bekon.agent.ocr

import java.text.Normalizer

/**
 * Text matching for find_text that survives the on-device OCR model.
 *
 * The bundled ML Kit recogniser is Latin-only: Cyrillic labels come back as visual
 * look-alikes ("Пароли и безопасность" -> "laponMu 6esonaCHOCTb"). Plain `contains`
 * never matches them. When the query has non-Latin letters we fold both sides into a
 * coarse "glyph skeleton" (letters that look the same share a class) and accept an
 * approximate substring match with a small edit budget. Pure-ASCII queries keep the
 * exact case-insensitive behaviour.
 */
object TextMatch {
    private val CYR = mapOf(
        'а' to "a", 'б' to "6", 'в' to "b", 'г' to "r", 'д' to "g", 'е' to "e", 'ё' to "e",
        'ж' to "x", 'з' to "3", 'и' to "n", 'й' to "n", 'к' to "k", 'л' to "n", 'м' to "n",
        'н' to "n", 'о' to "o", 'п' to "n", 'р' to "p", 'с' to "c", 'т' to "t", 'у' to "y",
        'ф' to "o", 'х' to "x", 'ц' to "n", 'ч' to "y", 'ш' to "w", 'щ' to "w", 'ъ' to "b",
        'ы' to "bi", 'ь' to "b", 'э' to "3", 'ю' to "io", 'я' to "r",
    )
    private val LAT = mapOf(
        'd' to 'g', 'q' to 'g', '9' to 'g', 'h' to 'n', 'm' to 'n', 'u' to 'n',
        'l' to 'i', '1' to 'i', '|' to 'i', 'j' to 'i', '!' to 'i', 'f' to 't',
        'v' to 'y', '4' to 'y', '0' to 'o', 's' to '3', 'z' to '3',
    )

    fun hasNonLatin(s: String) = s.any { it.isLetter() && it.code > 0x24F }

    /** Glyph skeleton: lowercase, diacritics stripped, spaces/punctuation dropped, look-alikes merged. */
    fun skeleton(s: String): String {
        val sb = StringBuilder()
        for (ch0 in s.lowercase()) {
            val direct = CYR[ch0]
            if (direct != null) { sb.append(direct); continue }
            val base = Normalizer.normalize(ch0.toString(), Normalizer.Form.NFD)
                .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
            for (ch in base) {
                CYR[ch]?.let { sb.append(it) } ?: run {
                    if (ch == '|' || ch == '!' || ch.isLetterOrDigit()) sb.append(LAT[ch] ?: ch)
                }
            }
        }
        return sb.toString()
    }

    private fun budget(n: Int) = maxOf(1, Math.round(n * 0.2f))

    /** Minimum edit distance of [q] against any substring of [t] (semi-global). */
    internal fun substringDistance(q: String, t: String): Int {
        var prev = IntArray(t.length + 1) // 0: free start anywhere in t
        for (i in 1..q.length) {
            val cur = IntArray(t.length + 1); cur[0] = i
            for (j in 1..t.length) {
                val c = if (q[i - 1] == t[j - 1]) 0 else 1
                cur[j] = minOf(prev[j - 1] + c, prev[j] + 1, cur[j - 1] + 1)
            }
            prev = cur
        }
        return prev.min()
    }

    internal fun distance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1); cur[0] = i
            for (j in 1..b.length) {
                val c = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j - 1] + c, prev[j] + 1, cur[j - 1] + 1)
            }
            prev = cur
        }
        return prev[b.length]
    }

    fun matches(label: String, query: String, contains: Boolean = true): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return false
        if (if (contains) label.contains(q, true) else label.trim().equals(q, true)) return true
        if (!hasNonLatin(q)) return false
        val sq = skeleton(q); val st = skeleton(label)
        if (sq.length < 4 || st.isEmpty()) return false
        val b = budget(sq.length)
        return if (contains) substringDistance(sq, st) <= b else distance(sq, st) <= b
    }
}
