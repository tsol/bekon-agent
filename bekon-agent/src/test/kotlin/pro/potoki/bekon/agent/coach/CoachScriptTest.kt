package pro.potoki.bekon.agent.coach

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import pro.potoki.bekon.agent.core.UiNode

class CoachScriptTest {
    private val rules = CoachScript.parse(Json.parseToJsonElement("""[
      {"id":"done","when":{"checked":{"text":"Bekon Agent","value":true}},"done":true},
      {"id":"hl","when":{"textVisible":"Bekon Agent"},"draw":{"type":"circle","target":{"text":"Bekon Agent"},"label":"Нажми"}},
      {"id":"scroll","when":{"textNotVisible":"Bekon Agent","package":"com.miui.securitycenter"},"draw":{"type":"arrow_down"}}
    ]""") as JsonArray)

    private fun row(checked: Boolean, y: Int) = listOf(
        UiNode("e1", "Bekon Agent", 540, y, intArrayOf(0, y - 85, 1080, y + 85), true, true, checked),
        UiNode("e2", "Bekon Agent", 390, y, intArrayOf(231, y - 37, 548, y + 37)),
        UiNode("e3", "Bekon Agent", 935, y, intArrayOf(868, y - 45, 1003, y + 45), true, true, checked),
    )

    @Test fun scrollHintWhenNotVisible() {
        val f = CoachScript.evaluate(rules, listOf(UiNode("x", "Авито", 1, 1, intArrayOf(0, 0, 10, 10))), "com.miui.securitycenter", 1080, 2340)
        assertEquals("scroll", f!!.rule.id); assertEquals(Shape.Kind.ARROW, f.shapes[0].kind)
    }
    @Test fun circlesSmallestNodeWhenVisible() {
        val f = CoachScript.evaluate(rules, row(false, 1488), "com.miui.securitycenter", 1080, 2340)!!
        assertEquals("hl", f.rule.id); assertEquals(868, f.targetBounds!![0]) // the row switch (same label, smallest)
    }
    @Test fun doneWhenChecked() {
        assertEquals("done", CoachScript.evaluate(rules, row(true, 1488), "p", 1080, 2340)!!.rule.id)
    }
    @Test fun noRuleOtherPackage() {
        assertNull(CoachScript.evaluate(rules, emptyList(), "com.android.settings", 1080, 2340))
    }

    @Test fun backHintCirclesNavBack() {
        val r = CoachScript.parse(Json.parseToJsonElement("""[{"when":{"textVisible":"Экран блокировки"},"draw":{"type":"back_hint"}}]""") as JsonArray)
        val items = listOf(UiNode("a", "Экран блокировки", 1, 1, intArrayOf(0, 0, 100, 50)), UiNode("b", "Назад", 763, 2275, intArrayOf(681, 2210, 846, 2340), true))
        val f = CoachScript.evaluate(r, items, "p", 1080, 2340)!!
        assertEquals(Shape.Kind.CIRCLE, f.shapes[0].kind); assertEquals(681f, f.shapes[0].x1)
    }
}
