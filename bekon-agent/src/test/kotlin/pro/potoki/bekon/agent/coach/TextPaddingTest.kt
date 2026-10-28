package pro.potoki.bekon.agent.coach

import org.junit.Assert.assertTrue
import org.junit.Test

/** General rule: any text inside the body keeps ≥ 8 dp from the contour (layout constants in dp, d = 1). */
class TextPaddingTest {
    private val padH = 18f; private val padV = 9f; private val extraV = 4f; private val head = 16f
    private fun check(name: String, c: FloatArray, l: Float, t: Float, r: Float, b: Float) {
        val cl = BodyShapes.clearance(c, l, t, r, b)
        assertTrue("$name clearance $cl", cl >= 8f)
    }

    @Test fun everyShapeKeepsPadding() {
        for (textH in listOf(16f, 30f, 46f)) for (tw in listOf(40f, 120f, 220f)) {
            val ih = maxOf(textH + 2 * padV, 44f)
            // idle / working / waiting pill, eye on either side
            val ep = BodyShapes.pillPadH(ih, padV, padH, 9f)
            val w = (ih - extraV) + 4f + tw + ep
            val pill = BodyShapes.pill(0f, 0f, w, ih)
            check("pill-eyeLeft", pill, (ih - extraV) + 4f, padV, w - ep, ih - padV)
            check("pill-eyeRight", pill, ep, padV, w - (ih - extraV) - 4f, ih - padV)
            // TAP caption pocket (rounded rect, kr 14 dp)
            val pw = tw + 2 * padH
            check("pocket", BodyShapes.roundRect(0f, 0f, pw, ih, 14f), padH, padV, padH + tw, ih - padV)
            // SCROLL arrows: text in the shaft
            for (dir in listOf("up", "down", "left", "right", "back")) {
                val notch = if (dir == "back") BodyShapes.BACK_NOTCH * ih else 0f
                val sw = w + notch; val shH = ih
                val c = BodyShapes.arrow(0f, 0f, sw, shH, dir, head)
                check("arrow-$dir", c, (ih - extraV) + 4f, padV, sw - ep - notch, shH - padV)
            }
        }
    }
}
