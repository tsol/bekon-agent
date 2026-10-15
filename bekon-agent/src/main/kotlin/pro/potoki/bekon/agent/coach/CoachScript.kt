package pro.potoki.bekon.agent.coach

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import pro.potoki.bekon.agent.core.UiNode

/**
 * Mini highlight script: an ordered rule list the PHONE evaluates in a tight loop against the
 * accessibility tree, so hints follow scrolling/re-sorting without an agent round-trip.
 * Each tick the FIRST rule whose `when` matches fires: its `draw` is shown (redrawn only when
 * the target moved), and `done:true` ends the script. Pure Kotlin, JVM-testable.
 *
 * Node matching is by visible text / contentDescription: `text` (exact, case-insensitive) or
 * `contains` (substring). Resource ids are not exposed by the snapshot yet.
 */
object CoachScript {
    data class Sel(val text: String?, val contains: String?) {
        fun matches(n: UiNode): Boolean {
            val l = n.label.trim()
            if (text != null && !l.equals(text, ignoreCase = true)) return false
            if (contains != null && !l.contains(contains, ignoreCase = true)) return false
            return text != null || contains != null
        }
    }
    data class When(
        val visible: Sel?, val notVisible: Sel?, val pkg: String?,
        val checked: Pair<Sel, Boolean>?, val selected: Pair<Sel, Boolean>?,
    )
    data class Draw(val type: String, val target: Sel?, val label: String?, val color: Int)
    /** Nav action (needs Accessibility): tap the target node, scroll, or a global nav key. */
    data class Action(val type: String, val target: Sel?, val settleMs: Long)
    data class Rule(val id: String, val `when`: When, val draw: Draw?, val done: Boolean, val action: Action? = null)
    data class Fired(val rule: Rule, val shapes: List<Shape>, val targetBounds: IntArray?)

    fun parse(arr: JsonArray?): List<Rule> {
        require(arr != null && arr.isNotEmpty()) { "rules is required (non-empty array)" }
        return arr.mapIndexed { i, el ->
            val o = el as? JsonObject ?: throw IllegalArgumentException("rules[$i] is not an object")
            val w = o["when"] as? JsonObject ?: JsonObject(emptyMap())
            fun sel(x: JsonObject?): Sel? = x?.let { Sel(it.s("text") ?: it.s("textVisible"), it.s("contains")) }
                ?.takeIf { it.text != null || it.contains != null }
            fun state(k: String): Pair<Sel, Boolean>? = (w[k] as? JsonObject)?.let { so ->
                val s = sel(so) ?: throw IllegalArgumentException("rules[$i].when.$k needs text or contains")
                s to ((so["value"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: true)
            }
            val visible = Sel(w.s("textVisible") ?: w.s("text"), w.s("contains") ?: w.s("textContains")).takeIf { it.text != null || it.contains != null }
            val notVisible = Sel(w.s("textNotVisible"), w.s("notContains")).takeIf { it.text != null || it.contains != null }
            val wh = When(visible, notVisible, w.s("package"), state("checked"), state("selected"))
            val d = (o["draw"] as? JsonObject)?.let { dj ->
                val type = (dj.s("type") ?: "circle").lowercase()
                require(type in DRAW_TYPES) { "rules[$i].draw.type must be one of ${DRAW_TYPES.joinToString()}" }
                val target = sel(dj["target"] as? JsonObject) ?: Sel(dj.s("targetText"), dj.s("targetContains")).takeIf { it.text != null || it.contains != null }
                Draw(type, target, dj.s("label"), Sketch.parseColor(dj.s("color")))
            }
            val a = (o["do"] as? JsonObject)?.let { aj ->
                val type = (aj.s("type") ?: "").lowercase()
                require(type in ACTION_TYPES) { "rules[$i].do.type must be one of ${ACTION_TYPES.joinToString()}" }
                val target = sel(aj["target"] as? JsonObject)
                Action(type, target, ((aj["settleMs"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 900L).coerceIn(100, 10_000))
            }
            val done = (o["done"] as? JsonPrimitive)?.content == "true"
            require(d != null || done || a != null) { "rules[$i] needs draw, do or done:true" }
            Rule(o.s("id") ?: "r${i + 1}", wh, d, done, a)
        }
    }

    val ACTION_TYPES = listOf("tap", "scroll_down", "scroll_up", "back", "home")

    /** Tap point for a tap action: explicit target, else the when node (smallest match). */
    fun actionTarget(r: Rule, items: List<UiNode>): UiNode? {
        val s = r.action?.target ?: r.`when`.visible ?: return null
        return meaningful(items).filter { it.bounds != null && s.matches(it) }
            .minByOrNull { val b = it.bounds!!; (b[2] - b[0]).toLong() * (b[3] - b[1]) }
    }

    val DRAW_TYPES = listOf("circle", "box", "underline", "arrow_up", "arrow_down", "text")

    private fun meaningful(items: List<UiNode>) = items.filter { !it.statusBar }

    fun matches(w: When, items: List<UiNode>, pkg: String?): Boolean {
        val its = meaningful(items)
        if (w.pkg != null && !w.pkg.equals(pkg, ignoreCase = true)) return false
        if (w.visible != null && its.none { w.visible.matches(it) }) return false
        if (w.notVisible != null && its.any { w.notVisible.matches(it) }) return false
        w.checked?.let { (s, v) -> val c = its.filter { it.checkable && s.matches(it) }; if (c.isEmpty() || c.none { it.checked == v }) return false }
        w.selected?.let { (s, v) -> val c = its.filter { s.matches(it) }; if (c.isEmpty() || c.none { it.selected == v }) return false }
        return true
    }

    /** Node to mark: explicit target, else the when-selector; prefer a checkable control, then the smallest. */
    fun target(r: Rule, items: List<UiNode>): UiNode? {
        val s = r.draw?.target ?: r.`when`.checked?.first ?: r.`when`.selected?.first ?: r.`when`.visible ?: return null
        val c = meaningful(items).filter { it.bounds != null && s.matches(it) }
        val pool = if (r.draw?.type in setOf("circle", "box") && r.`when`.checked != null) c.filter { it.checkable }.ifEmpty { c } else c
        return pool.minByOrNull { val b = it.bounds!!; (b[2] - b[0]).toLong() * (b[3] - b[1]) }
    }

    fun evaluate(rules: List<Rule>, items: List<UiNode>, pkg: String?, screenW: Int, screenH: Int): Fired? {
        for (r in rules) {
            if (!matches(r.`when`, items, pkg)) continue
            val d = r.draw ?: return Fired(r, emptyList(), r.action?.let { actionTarget(r, items)?.bounds })
            val t = target(r, items)?.bounds
            val shapes = shapes(d, t, screenW, screenH)
            return Fired(r, shapes, t)
        }
        return null
    }

    fun shapes(d: Draw, b: IntArray?, w: Int, h: Int): List<Shape> {
        val cx = w / 2f
        return when (d.type) {
            "arrow_down" -> listOf(Shape(Shape.Kind.ARROW, cx, h * 0.45f, cx, h * 0.75f, d.label ?: "Прокрутите вниз", d.color))
            "arrow_up" -> listOf(Shape(Shape.Kind.ARROW, cx, h * 0.6f, cx, h * 0.3f, d.label ?: "Прокрутите вверх", d.color))
            "text" -> listOf(Shape(Shape.Kind.TEXT, w * 0.08f, h * 0.5f, w * 0.08f, h * 0.5f, d.label ?: "", d.color))
            else -> {
                if (b == null) return d.label?.let { listOf(Shape(Shape.Kind.TEXT, w * 0.08f, h * 0.5f, w * 0.08f, h * 0.5f, it, d.color)) } ?: emptyList()
                val k = when (d.type) { "box" -> Shape.Kind.BOX; "underline" -> Shape.Kind.UNDERLINE; else -> Shape.Kind.CIRCLE }
                val (l, t, r, bt) = b.map { it.toFloat() }
                val s = if (k == Shape.Kind.UNDERLINE) Shape(k, l, bt + 8, r, bt + 8, d.label, d.color) else Shape(k, l, t, r, bt, d.label, d.color)
                listOf(s)
            }
        }
    }

    private fun JsonObject.s(k: String): String? = (this[k] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }
}
