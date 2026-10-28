package pro.potoki.bekon.agent.coach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlacementGovernorTest {
    private fun sig(v: Int, noise: java.util.Random? = null) = IntArray(34 * 73) { v + (noise?.nextInt(5)?.minus(2) ?: 0) }

    /** Owner bug: static screen, competing spots with near-equal costs → the pill must not move at all. */
    @Test fun staticScreenZeroMovesOver30s() {
        val g = PlacementGovernor(); val rnd = java.util.Random(1)
        var moves = 0; var first = true
        var t = 0L
        while (t <= 30_000L) {
            g.onSignature(sig(150, rnd), t)
            if (g.shouldRecompute(t)) {
                // Best spot alternates between two places, ±0.1 in cost (what made it jitter before).
                val here = 0.40f; val best = if ((t / 500) % 2 == 0L) 0.30f else 0.32f
                if (g.decide(t, here, best, occupied = false, firstPlacement = first)) { if (!first) moves++; g.moved(t) }
                first = false
            }
            t += 450                      // sampler cadence
        }
        assertEquals(0, moves)
    }

    @Test fun realChangeMovesOnceAfterSettling() {
        val g = PlacementGovernor()
        var t = 0L
        g.onSignature(sig(150), t); assertTrue(g.shouldRecompute(t + 1000)); g.decide(t + 1000, 0f, 0f, false, firstPlacement = true); g.moved(t + 1000)
        t = 5_000
        // screen changes and keeps animating for 1 s: no recompute while moving
        var recomputes = 0; var moves = 0
        for (i in 0 until 60) {
            val now = t + i * 50L
            g.onSignature(sig(if (now < t + 1000) 60 + (i % 5) * 20 else 60), now)
            if (g.shouldRecompute(now)) { recomputes++; if (g.decide(now, 1.0f, 0.3f, occupied = false)) { moves++; g.moved(now) } }
        }
        assertTrue("recomputed only after settling", recomputes >= 1)
        assertEquals(1, moves)
    }

    @Test fun noMoveWhileTouching() {
        val g = PlacementGovernor()
        g.onSignature(sig(150), 0); g.decide(1000, 0f, 0f, false, true); g.moved(1000)
        g.onSignature(sig(40), 5_000)
        g.touched(5_600)
        assertTrue(!g.shouldRecompute(6_000))
        assertTrue(g.shouldRecompute(6_700))
    }
}
