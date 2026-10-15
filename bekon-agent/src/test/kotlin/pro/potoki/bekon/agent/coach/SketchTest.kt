package pro.potoki.bekon.agent.coach

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SketchTest {
    private fun parse(s: String) = Sketch.parse(Json.parseToJsonElement(s) as JsonArray)

    @Test fun parsesAllKinds() {
        val shapes = parse("""[
          {"type":"box","x":10,"y":20,"w":100,"h":50,"label":"tap here"},
          {"type":"circle","x":500,"y":600,"r":90,"color":"green"},
          {"type":"arrow","x1":0,"y1":0,"x2":300,"y2":400},
          {"type":"underline","bounds":[10,10,200,60]},
          {"type":"text","x":5,"y":5,"label":"now back"}
        ]""")
        assertEquals(5, shapes.size)
        assertEquals(Shape(Shape.Kind.BOX, 10f, 20f, 110f, 70f, "tap here"), shapes[0])
        assertEquals(410f, shapes[1].x1); assertEquals(0xFF22C55E.toInt(), shapes[1].color)
        assertEquals(68f, shapes[3].y1)
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsUnknown() { parse("""[{"type":"blob"}]""") }
    @Test(expected = IllegalArgumentException::class) fun rejectsMissingCoord() { parse("""[{"type":"arrow","x1":1}]""") }

    @Test fun hexColor() { assertEquals(0xFF112233.toInt(), Sketch.parseColor("#112233")) }

    @Test fun strokesStayNearShapeAndAreDeterministic() {
        val c = Shape(Shape.Kind.CIRCLE, 100f, 100f, 300f, 200f)
        val a = Sketch.strokes(c, 1); val b = Sketch.strokes(c, 1)
        assertEquals(a, b)
        for ((x, y) in a.flatten()) { assertTrue(x in 50f..350f); assertTrue(y in 60f..240f) }
        assertEquals(3, Sketch.strokes(Shape(Shape.Kind.ARROW, 0f, 0f, 100f, 100f), 2).size)
        assertEquals(4, Sketch.strokes(Shape(Shape.Kind.BOX, 0f, 0f, 100f, 100f), 2).size)
    }
}
