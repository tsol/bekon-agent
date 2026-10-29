package pro.potoki.bekon.agent.coach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Redmi 9: screencap 1080x2340, status bar 76 px, nav bar 130 px (overlay window ends at 2210). */
class TargetMappingTest {
    private val top = 76f + 6f
    private val bottom = 2340f - 130f - 6f

    private fun circle(x: Float, y: Float, r: Float) = Shape(Shape.Kind.CIRCLE, x - r, y - r, x + r, y + r)

    @Test fun homeButtonCircleStaysRoundAtItsTruePlaceWithAPointer() {
        val out = TargetMapping.map(listOf(circle(540f, 2260f, 70f)), top, bottom)
        val c = out.first { it.kind == Shape.Kind.CIRCLE }
        assertEquals(540f, (c.x1 + c.x2) / 2, 0.01f); assertEquals(2260f, (c.y1 + c.y2) / 2, 0.01f)
        assertEquals("not squashed", c.x2 - c.x1, c.y2 - c.y1, 0.01f)
        val a = out.single { it.kind == Shape.Kind.ARROW }
        assertEquals(540f, a.x2, 0.01f)
        assertTrue("arrow head inside the drawable area, pointing down", a.y2 <= bottom && a.y2 > a.y1)
    }

    @Test fun safeAreaTargetsAreExactAtCornersAndCentre() {
        for ((x, y) in listOf(100f to 200f, 980f to 200f, 540f to 1170f, 100f to 2100f, 980f to 2100f)) {
            val s = circle(x, y, 60f)
            assertEquals(listOf(s), TargetMapping.map(listOf(s), top, bottom))
        }
    }

    @Test fun statusBarTargetPointsUp() {
        val out = TargetMapping.map(listOf(circle(1000f, 38f, 30f)), top, bottom)
        val a = out.single { it.kind == Shape.Kind.ARROW }
        assertTrue(a.y2 >= top && a.y2 < a.y1)
        assertEquals(38f, out.first { it.kind == Shape.Kind.CIRCLE }.let { (it.y1 + it.y2) / 2 }, 0.01f)
    }

    @Test fun contentScrolledPartlyUnderTheEdgeIsStillSquashed() {
        val s = Shape(Shape.Kind.BOX, 100f, 2150f, 500f, 2250f)   // centre 2200 < bottom 2204
        val m = TargetMapping.map(listOf(s), top, bottom).single()
        assertTrue(maxOf(m.y1, m.y2) <= bottom + 0.01f)
        assertTrue("widened", m.x2 - m.x1 > 400f)
    }

    @Test fun systemBarCheck() {
        assertTrue(TargetMapping.inSystemBar(2190f, 2330f, top, bottom))
        assertTrue(!TargetMapping.inSystemBar(2150f, 2250f, top, bottom))
    }
}
