package pro.potoki.bekon.agent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenDiffTest {
    private fun n(ref: String, name: String, y: Int, checked: Boolean? = null, sb: Boolean = false, sel: Boolean = false) =
        UiNode(ref, name, 500, y, intArrayOf(0, y - 50, 1000, y + 50), true,
            checkable = checked != null, checked = checked == true, selected = sel, statusBar = sb)

    @Test fun `status bar clock does not change token`() {
        val a = listOf(n("e1", "12:01", 20, sb = true), n("e2", "Wi-Fi", 300, false))
        val b = listOf(n("e1", "12:02", 20, sb = true), n("e2", "Wi-Fi", 300, false))
        assertEquals(ScreenDiff.token(a), ScreenDiff.token(b))
        assertTrue(ScreenDiff.diff(a, b).empty)
    }

    @Test fun `toggle and added and removed`() {
        val a = listOf(n("e1", "Wi-Fi", 300, false), n("e2", "Old", 400), n("e3", "No restrictions", 600, sel = false))
        val b = listOf(n("e1", "Wi-Fi", 300, true), n("e2", "New", 500), n("e3", "No restrictions", 600, sel = true))
        assertNotEquals(ScreenDiff.token(a), ScreenDiff.token(b))
        val d = ScreenDiff.diff(a, b)
        val s = d.summary()
        assertTrue(s, s.contains("~ Wi-Fi: off -> on"))
        assertTrue(s, s.contains("~ No restrictions: selected"))
        assertTrue(s, s.contains("+ New"))
        assertTrue(s, s.contains("- Old"))
    }
}
