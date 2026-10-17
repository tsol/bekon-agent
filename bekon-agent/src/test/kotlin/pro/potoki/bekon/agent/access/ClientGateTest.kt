package pro.potoki.bekon.agent.access

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClientGateTest {
    @org.junit.Before fun reset() { AgentConnections.clear() }

    private class Mem : TrustedClients {
        var auto = true
        val ids = mutableSetOf<String>()
        override fun autoAcceptNext() = auto
        override fun trusted() = ids.toSet()
        override fun remember(id: String) { ids.add(id) }
    }

    @Test
    fun `first id is remembered when auto accept is on`() {
        val mem = Mem()
        val gate = ClientGate(mem, paused = { false }, ask = { _, _, _ -> false })
        assertNull(gate.rejection("alpha", "1.2.3.4"))
        assertEquals(setOf("alpha"), mem.ids)
        assertNull(gate.rejection("alpha", "9.9.9.9"))
    }

    @Test
    fun `a different id asks and a denial is not remembered`() {
        val mem = Mem()
        mem.ids.add("alpha")
        var asked = ""
        val gate = ClientGate(mem, paused = { false }, ask = { id, ip, _ ->
            asked = "$id@$ip"
            false
        })
        assertEquals(ClientGate.DENIED, gate.rejection("beta", "8.8.8.8"))
        assertEquals("beta@8.8.8.8", asked)
        assertEquals(setOf("alpha"), mem.ids)
    }

    @Test
    fun `pause rejects a trusted id`() {
        val mem = Mem()
        mem.ids.add("alpha")
        val gate = ClientGate(mem, paused = { true }, ask = { _, _, _ -> true })
        assertEquals(ClientGate.PAUSED, gate.rejection("alpha", "1.1.1.1"))
    }

    @Test
    fun `missing id is refused with the instruction`() {
        val gate = ClientGate(Mem(), paused = { false }, ask = { _, _, _ -> true })
        assertEquals(ClientGate.MISSING_ID, gate.rejection("  ", "127.0.0.1"))
    }

    @Test
    fun `second agent needs a replace prompt and the replaced one is refused`() {
        val mem = Mem(); mem.ids.addAll(listOf("alpha", "beta"))
        var replacing: String? = "none"
        var allow = false
        val gate = ClientGate(mem, paused = { false }, ask = { _, _, r -> replacing = r; allow })
        assertNull(gate.rejection("alpha", "1.1.1.1"))
        assertEquals(ClientGate.BUSY, gate.rejection("beta", "2.2.2.2"))
        assertEquals("alpha", replacing)
        allow = true
        assertNull(gate.rejection("beta", "2.2.2.2"))
        assertEquals("beta", AgentConnections.active())
        assertEquals(ClientGate.REPLACED, gate.rejection("alpha", "1.1.1.1"))
    }
}
