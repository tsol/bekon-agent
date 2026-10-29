package pro.potoki.bekon.agent.access

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AgentConnectionsTest {
    private var now = 1_000_000L

    @Before fun setUp() { AgentConnections.clock = { now }; AgentConnections.resetAll() }
    @After fun tearDown() { AgentConnections.resetAll(); AgentConnections.clock = { System.currentTimeMillis() } }

    @Test fun liveFor15MinutesAfterTheLastCallAndAnyCallExtendsIt() {
        AgentConnections.mark("madison")
        assertEquals(listOf("madison"), AgentConnections.connected.value)
        now += 14 * 60_000L; AgentConnections.checkIdle()
        assertTrue("a slow agent thinking for minutes stays connected", AgentConnections.state.value.live)
        AgentConnections.seen("madison")               // e.g. session_info
        now += 14 * 60_000L; AgentConnections.checkIdle()
        assertTrue(AgentConnections.state.value.live)
        now += 2 * 60_000L; AgentConnections.checkIdle()
        assertFalse(AgentConnections.state.value.live)
        assertEquals(emptyList<String>(), AgentConnections.connected.value)
        AgentConnections.mark("madison")
        assertTrue(AgentConnections.state.value.live)
    }

    @Test fun oldTwoMinuteWindowNoLongerDropsTheAgent() {
        AgentConnections.mark("a")
        now += 3 * 60_000L; AgentConnections.checkIdle()
        assertEquals(listOf("a"), AgentConnections.connected.value)
    }

    @Test fun disconnectDropsTheNameAtOnceForCardAndPill() {
        AgentConnections.mark("a")
        AgentConnections.clear()
        val s = AgentConnections.state.value
        assertFalse(s.live); assertEquals(null, s.active); assertTrue(s.everConnected)
        assertEquals(emptyList<String>(), AgentConnections.connected.value)
    }

    @Test fun seenFromAnotherIdDoesNotConnectIt() {
        AgentConnections.mark("a")
        AgentConnections.seen("b")
        assertEquals("a", AgentConnections.active())
        AgentConnections.clear()
        AgentConnections.seen("a")
        assertEquals(null, AgentConnections.active())
    }

    @Test fun connectedIsAlwaysDerivedFromState() {
        AgentConnections.mark("a")
        repeat(3) {
            val s = AgentConnections.state.value
            assertEquals(if (s.live) listOf(s.active) else emptyList<String>(), AgentConnections.connected.value)
            now += 16 * 60_000L; AgentConnections.checkIdle()
        }
    }
}
