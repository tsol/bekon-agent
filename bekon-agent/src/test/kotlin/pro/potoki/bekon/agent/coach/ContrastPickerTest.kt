package pro.potoki.bekon.agent.coach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContrastPickerTest {
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test fun defaultIdleFillGetsLightText() {
        val fill = StateFills.IDLE   // the actual default fill, rgb(84, 96, 116)
        assertEquals(rgb(84, 96, 116), fill)
        assertEquals(ContrastPicker.LIGHT, ContrastPicker.textOn(fill, StateFills.TEXT_ALPHA))
        assertTrue(ContrastPicker.worstRatio(ContrastPicker.LIGHT, fill, StateFills.TEXT_ALPHA) >= 4.5)
    }

    /** Every state fill (idle, working, waiting/tap/scroll, done, error) is dark → light text ≥ 4.5:1 on any background. */
    @Test fun everyStateFillGetsLightTextAtLeast45() {
        for (f in StateFills.ALL) {
            val tc = ContrastPicker.textOn(f, StateFills.TEXT_ALPHA)
            val r = ContrastPicker.worstRatio(tc, f, StateFills.TEXT_ALPHA)
            println("fill #%06x text #%06x worst ratio %.2f".format(f and 0xFFFFFF, tc and 0xFFFFFF, r))
            assertEquals("fill %06x".format(f and 0xFFFFFF), ContrastPicker.LIGHT, tc)
            assertTrue("fill %06x ratio $r".format(f and 0xFFFFFF), r >= 4.5)
        }
    }

    @Test fun lightFillGetsDarkText() {
        assertEquals(ContrastPicker.DARK, ContrastPicker.textOn(rgb(240, 240, 235)))
        assertEquals(ContrastPicker.DARK, ContrastPicker.textOn(rgb(255, 214, 90)))
    }

    @Test fun translucentFillUsesWorstCaseComposite() {
        // 60 % mid-grey over black vs over white differ a lot; the pick must be checked against both.
        val f = rgb(120, 120, 120)
        assertEquals(rgb(72, 72, 72), ContrastPicker.composite(f, 153, ContrastPicker.DARK))
        assertTrue(ContrastPicker.worstRatio(ContrastPicker.textOn(f, 153), f, 153) <= ContrastPicker.ratio(ContrastPicker.textOn(f, 153), f) + 1e-9)
    }

    @Test fun sweepOpaqueNeverBelow45() {
        for (r in 0..255 step 17) for (g in 0..255 step 17) for (b in 0..255 step 17) {
            val f = rgb(r, g, b)
            assertTrue(ContrastPicker.ratio(ContrastPicker.textOn(f), f) >= 4.5)
        }
    }
}
