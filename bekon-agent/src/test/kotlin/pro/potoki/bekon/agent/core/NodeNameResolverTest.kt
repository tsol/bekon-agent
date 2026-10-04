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
}
