package pro.potoki.bekon.agent.status

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AgentStatusTest {
    @After fun tearDown() { AgentStatus.clear() }

    @Test fun defaultsToWorking() {
        val r = AgentStatus.parse("  Работаю   над  задачей ", null, null, null)
        assertEquals(AgentStatus.State.WORKING, r.state)
        assertEquals("Работаю над задачей", r.text)
        assertFalse(r.clear); assertFalse(r.truncated)
    }

    @Test fun statesParseCaseInsensitive() {
        for (s in listOf("working", "DONE", "Waiting", "error")) {
            assertEquals(s.lowercase(), AgentStatus.parse("x", s, null, null).state.wire)
        }
    }

    @Test(expected = IllegalArgumentException::class) fun badState() { AgentStatus.parse("x", "finished", null, null) }
    @Test(expected = IllegalArgumentException::class) fun emptyTextRejected() { AgentStatus.parse("  ", "done", null, null) }

    @Test fun clearNeedsNoText() { assertTrue(AgentStatus.parse(null, null, null, true).clear) }

    @Test fun longTextTruncated() {
        val r = AgentStatus.parse("a".repeat(200), "done", null, null)
        assertTrue(r.truncated)
        assertEquals(AgentStatus.MAX_CHARS, r.text.length)
        assertTrue(r.text.endsWith("…"))
    }

    @Test fun ttlClamped() {
        assertEquals(0L, AgentStatus.parse("x", null, -5, null).ttlMs)
        assertEquals(AgentStatus.MAX_TTL_MS, AgentStatus.parse("x", null, Long.MAX_VALUE, null).ttlMs)
    }

    @Test fun applyAndClearAndTtl() {
        val s = AgentStatus.apply(AgentStatus.parse("Готово", "done", null, null))
        assertEquals(s, AgentStatus.current.value)
        AgentStatus.clear()
        assertNull(AgentStatus.current.value)
        AgentStatus.apply(AgentStatus.parse("brief", "working", 150, null))
        Thread.sleep(600)
        assertNull(AgentStatus.current.value)
    }

    @Test fun ruAndEnStatusStringsMatch() {
        fun keys(f: String) = Regex("name=\"([a-z_]+)\"").findAll(File(f).readText()).map { it.groupValues[1] }.toSet()
        val en = keys("src/main/res/values/strings_status.xml")
        val ru = keys("src/main/res/values-ru/strings_status.xml")
        assertTrue(en.contains("notif_agent_finished"))
        assertEquals(en, ru)
    }
}
