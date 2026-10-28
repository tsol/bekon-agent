package pro.potoki.bekon.agent.coach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShiftEstimatorTest {
    private val w = 90; private val h = 195
    /** A "list": rows of text-like blobs on a light background, as a page of length [len]. */
    private fun page(len: Int): IntArray { val r = java.util.Random(3); return IntArray(w * len) { i -> val y = i / w; val x = i % w; if (y % 16 < 6 && x in 10..(20 + (y / 16 * 7) % 60)) 40 + r.nextInt(20) else 235 } }
    private fun view(p: IntArray, off: Int, dx: Int = 0) = IntArray(w * h) { i -> val y = i / w; val x = i % w - dx; if (x < 0 || x >= w) 235 else p[(y + off) * w + x] }

    @Test fun detectsVerticalScroll() {
        val p = page(600)
        val r = ShiftEstimator.estimate(view(p, 100), view(p, 118), w, h)
        assertTrue(r.isShift); assertEquals(-18, r.dy); assertEquals(0, r.dx)   // finger up → content moves up
    }

    @Test fun detectsSidewaysSwipe() {
        val p = page(400)
        val r = ShiftEstimator.estimate(view(p, 50), view(p, 50, dx = 12), w, h)
        assertTrue(r.isShift); assertEquals(12, r.dx)
    }

    @Test fun staticAndNewScreen() {
        val p = page(600)
        assertTrue(ShiftEstimator.estimate(view(p, 0), view(p, 0), w, h).isStatic)
        val other = IntArray(w * h) { if ((it / w) % 23 < 9) 20 else 200 }
        val r = ShiftEstimator.estimate(view(p, 0), other, w, h)
        assertFalse(r.isShift); assertFalse(r.isStatic)
    }
}
