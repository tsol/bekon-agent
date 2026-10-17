package pro.potoki.bekon.agent.core

import org.junit.Assert.assertEquals
import org.junit.Test

class NodeNameResolverTest {

    private val all = listOf(
        (intArrayOf(0, 0, 1080, 120) to "Status bar"),
        (intArrayOf(390, 810, 690, 990) to "YouTube"),
        (intArrayOf(50, 220, 200, 300) to null),
    )

    @Test
    fun `clickable node borrows text of a node inside its bounds`() {
        // clickable row whose bounds contain the "YouTube" text node
        val row = intArrayOf(380, 800, 700, 1000)
        val name = NodeNameResolver.resolve(row, null, null, true, all)
        assertEquals("YouTube", name)
    }

    @Test
    fun `own text wins over neighbor`() {
        val name = NodeNameResolver.resolve(
            intArrayOf(380, 800, 700, 1000), "Bekon Agent", null, true, all,
        )
        assertEquals("Bekon Agent", name)
    }

    @Test
    fun `clickable with no text anywhere falls back to clickable`() {
        val name = NodeNameResolver.resolve(
            intArrayOf(1000, 2000, 1040, 2030), null, null, true, all,
        )
        assertEquals("clickable", name)
    }

    @Test
    fun `non-clickable with no text has generic label`() {
        val name = NodeNameResolver.resolve(intArrayOf(300, 400, 320, 420), null, null, false, all)
        assertEquals("clickable", name)
    }

    @Test
    fun `unlabeled control uses class and view id`() {
        val name = NodeNameResolver.resolve(
            intArrayOf(1000, 2000, 1040, 2030), null, null, true, all,
            viewId = "com.app:id/send", cls = "android.widget.ImageButton",
        )
        assertEquals("button (id: send)", name)
    }

    @Test
    fun `bare switch takes the label of its row`() {
        val row = listOf(
            (intArrayOf(0, 1409, 1080, 1579) to "Bekon Agent"),
            (intArrayOf(231, 1457, 548, 1531) to "Bekon Agent"),
            (intArrayOf(231, 1627, 614, 1701) to "Bekon Gateway"),
        )
        val name = NodeNameResolver.resolve(intArrayOf(868, 1449, 1003, 1538), null, null, true, row, checkable = true)
        assertEquals("Bekon Agent", name)
    }
}
