package pro.potoki.bekon.agent.coach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class BodyShapesTest {
    /** Arc-length uniform: no chord longer than the step, and short chords only at the few sharp corners. */
    private fun uniform(p: FloatArray): Boolean {
        val n = p.size / 2; val ds = FloatArray(n)
        for (i in 0 until n) { val j = (i + 1) % n; ds[i] = hypot(p[2 * j] - p[2 * i], p[2 * j + 1] - p[2 * i + 1]) }
        val mean = ds.average().toFloat()
        return ds.max() <= mean * 1.15f && ds.count { it < mean * 0.6f } <= 8
    }

    @Test fun everyGeneratorResamplesToNUniformSimplePoints() {
        val shapes = listOf(
            BodyShapes.pill(100f, 100f, 400f, 160f),
            BodyShapes.ellipse(50f, 50f, 20f, 12f),
            BodyShapes.drop(200f, 200f, 40f, Math.PI / 2),
            BodyShapes.arrow(100f, 100f, 400f, 160f, "up", 40f),
            BodyShapes.arrow(100f, 100f, 400f, 160f, "down", 40f),
            BodyShapes.arrow(100f, 100f, 400f, 160f, "left", 40f),
            BodyShapes.arrow(100f, 100f, 400f, 160f, "right", 40f),
            BodyShapes.arrow(100f, 100f, 400f, 160f, "back", 40f),
            BodyShapes.ringPocket(floatArrayOf(100f, 1800f, 260f, 1900f), 20f, floatArrayOf(300f, 1500f, 700f, 1600f), 30f, 12f, 5f, 16f).first,
            BodyShapes.ringPocket(floatArrayOf(100f, 400f, 260f, 480f), 20f, floatArrayOf(80f, 600f, 500f, 690f), 30f, 12f, 5f, 16f).first,
            BodyShapes.ringPocket(floatArrayOf(100f, 400f, 260f, 480f), 20f, floatArrayOf(400f, 380f, 800f, 470f), 30f, 12f, 5f, 16f).first,
        )
        for ((k, s) in shapes.withIndex()) {
            val r = BodyShapes.resample(s)
            assertEquals(2 * BodyShapes.N, r.size)
            assertTrue("uniform #$k", uniform(r))
            assertFalse("simple #$k", BodyShapes.selfIntersects(r))
        }
    }

    @Test fun ringPocketReturnsTailAxisAndCoversBoth() {
        val (c, axis) = BodyShapes.ringPocket(floatArrayOf(100f, 1800f, 260f, 1900f), 20f, floatArrayOf(300f, 1500f, 700f, 1600f), 30f, 12f, 5f, 16f)
        val b = BodyShapes.bounds(c)
        assertTrue(axis != null)
        assertEquals(100f, b[0], 1f); assertEquals(700f, b[2], 1f); assertEquals(1500f, b[1], 1f); assertEquals(1900f, b[3], 1f)
    }

    @Test fun alignPicksStartAndOrientation() {
        val a = BodyShapes.resample(BodyShapes.pill(0f, 0f, 200f, 50f))
        val rev = FloatArray(a.size); val n = a.size / 2
        for (i in 0 until n) { val j = (n - 1 - i + 37) % n; rev[2 * i] = a[2 * j]; rev[2 * i + 1] = a[2 * j + 1] }
        val al = BodyShapes.align(a, rev)
        var err = 0f; for (i in a.indices) err = maxOf(err, kotlin.math.abs(al[i] - a[i]))
        assertTrue("err $err", err < 6f)
    }

    @Test fun springConvergesToTarget() {
        val s = BodySpring()
        val p = BodyShapes.resample(BodyShapes.pill(0f, 0f, 200f, 50f)); val e = BodyShapes.resample(BodyShapes.ellipse(20f, 25f, 8f, 8f))
        s.setTargets(p, e, 0, false)
        val ring = BodyShapes.resample(BodyShapes.roundRect(500f, 500f, 700f, 600f, 20f)); val hole = BodyShapes.resample(BodyShapes.roundRect(520f, 520f, 680f, 580f, 8f))
        s.setTargets(ring, hole, 0, true)
        var t = 0L; var moving = true
        while (moving && t < 3000) { t += 16; moving = s.step(16f, t) }
        assertFalse(moving); assertTrue("settles < 1.5 s, took $t", t < 1500)
        val b = BodyShapes.bounds(s.outer)
        assertEquals(500f, b[0], 1f); assertEquals(700f, b[2], 1f)
    }
}
