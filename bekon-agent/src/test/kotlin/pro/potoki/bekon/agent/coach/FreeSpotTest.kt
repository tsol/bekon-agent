package pro.potoki.bekon.agent.coach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeSpotTest {
    private val sw = 1080; private val sh = 2340

    /** Busy (striped) everywhere except a plain band [plainTop, plainBottom) in screen px. */
    private fun grid(plainTop: Int, plainBottom: Int, text: List<IntArray> = emptyList()): FreeSpot.Grid {
        val w = sw / 4; val h = sh / 4
        val luma = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            val sy = y * 4
            if (sy in plainTop until plainBottom) 240 else if ((x / 2 + y / 3) % 2 == 0) 20 else 230
        }
        return FreeSpot.build(luma, w, h, 4f, sw, sh, text)
    }

    /** Regression (device crash after rotation): a portrait grid with a landscape safe rect must not index out of bounds. */
    @Test fun staleGridFromOtherOrientationDoesNotCrash() {
        val g = grid(1400, 1700)
        val s = FreeSpot.find(g, 400, 150, intArrayOf(1500, 300, 1900, 400), safe = intArrayOf(0, 0, 2340, 1080))
        if (s != null) assertTrue(s.rect[2] <= sw && s.rect[3] <= sh)
    }

    @Test fun picksPlainArea() {
        val g = grid(1400, 1700)
        val s = assertNotNullSpot(FreeSpot.find(g, 400, 150, intArrayOf(340, 600, 740, 700)))
        assertTrue("spot ${s.rect.toList()} must be in the plain band", s.rect[1] >= 1400 && s.rect[3] <= 1700)
        assertTrue(s.cost < 0.2f)
    }

    @Test fun nearestOfTwoPlainAreas() {
        val w = sw / 4; val h = sh / 4
        val luma = IntArray(w * h) { i -> val y = (i / w) * 4; val x = i % w
            if (y in 300 until 600 || y in 1800 until 2100) 240 else if ((x / 2 + i / w / 3) % 2 == 0) 20 else 230 }
        val g = FreeSpot.build(luma, w, h, 4f, sw, sh, emptyList())
        val s = FreeSpot.find(g, 300, 120, intArrayOf(400, 1700, 700, 1760))!!
        assertTrue("near the lower plain area: ${s.rect.toList()}", s.rect[1] >= 1800)
    }

    @Test fun avoidsObstaclesAndAnchor() {
        val g = grid(0, sh)
        val target = intArrayOf(100, 1000, 600, 1100)
        val s = FreeSpot.find(g, 300, 100, target, anchorIsObstacle = true)!!
        val overlap = s.rect[0] < target[2] && s.rect[2] > target[0] && s.rect[1] < target[3] && s.rect[3] > target[1]
        assertTrue("caption must not cover the target", !overlap)
        // Right next to it (within ~150 px).
        assertTrue(minOf(kotlin.math.abs(s.rect[1] - target[3]), kotlin.math.abs(target[1] - s.rect[3])) < 150 || s.rect[0] >= target[2])
    }

    @Test fun ocrTextCountsAsOccupied() {
        val text = (0 until 20).map { intArrayOf(0, it * 110 + 80, sw, it * 110 + 150) }
        val g = grid(0, sh, text)
        val s = FreeSpot.find(g, 300, 40, intArrayOf(400, 1000, 700, 1040))!!
        val onText = text.any { s.rect[1] < it[3] && s.rect[3] > it[1] }
        assertTrue("prefers the gaps between text lines: ${s.rect.toList()}", !onText)
    }

    @Test fun respectsSafeArea() {
        val g = grid(0, sh)
        val safe = intArrayOf(0, 76, sw, 2210)
        val s = FreeSpot.find(g, 500, 200, intArrayOf(0, 2200, sw, 2340), safe = safe)!!
        assertTrue(s.rect[3] <= 2210 - 16 && s.rect[1] >= 76)
    }

    @Test fun placesLabelsBesideFramesNotOnThem() {
        val g = grid(0, sh)
        val box = Shape(Shape.Kind.BOX, 90f, 400f, 330f, 470f, "Your turn: tap Wi-Fi")
        val out = FreeSpot.placeLabels(listOf(box), g, intArrayOf(0, 76, sw, 2210))
        val s = out.single()
        assertEquals(box.x1, s.x1); assertEquals(box.y2, s.y2) // the frame itself never moves
        assertTrue(!s.lx.isNaN())
        val (lw, lh) = FreeSpot.labelSize(box.label!!, sw)
        val overlap = s.lx < box.x2 && s.lx + lw > box.x1 && s.ly < box.y2 && s.ly + lh > box.y1
        assertTrue("caption beside the frame", !overlap)
        assertTrue("caption close to the frame", kotlin.math.abs(s.ly - box.y2) < 200 || kotlin.math.abs(box.y1 - (s.ly + lh)) < 200 || s.lx >= box.x2)
    }

    @Test fun noGridLeavesShapesUnchanged() {
        val box = Shape(Shape.Kind.BOX, 1f, 2f, 3f, 4f, "x")
        assertEquals(listOf(box), FreeSpot.placeLabels(listOf(box), null))
    }

    private fun assertNotNullSpot(s: FreeSpot.Spot?): FreeSpot.Spot { assertNotNull(s); return s!! }
}
