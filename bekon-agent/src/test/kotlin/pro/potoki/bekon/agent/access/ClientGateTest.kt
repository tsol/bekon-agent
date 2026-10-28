package pro.potoki.bekon.agent.access

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ClientGateTest {
    private class Auto(val on: Boolean = false) : TrustedClients { override fun autoAcceptNext() = on }
    private fun allowed(d: ClientGate.Decision) = d as ClientGate.Decision.Allow
    private fun rejected(d: ClientGate.Decision) = d as ClientGate.Decision.Reject

    @Before fun setUp() = AgentConnections.clear()
    @After fun tearDown() = AgentConnections.clear()

    @Test fun approvalIssuesRandomTokenThenTokenRequired() {
        var asks = 0
        val gate = ClientGate(Auto(), paused = { false }, ask = { _, _, _ -> asks++; true })
        val tok = allowed(gate.check("alpha", "1.1.1.1", null)).issuedToken
        assertNotNull(tok)
        assertTrue("32 bytes base64url", tok!!.length == 43 && tok.matches(Regex("[A-Za-z0-9_-]+")))
        // valid token: allowed, no new token, no new prompt
        assertNull(allowed(gate.check("alpha", "1.1.1.1", tok)).issuedToken)
        // approved id without token: 401, owner NOT re-prompted
        val r = rejected(gate.check("alpha", "1.1.1.1", null))
        assertEquals(401, r.status); assertEquals(ClientGate.TOKEN_REQUIRED, r.reason)
        assertEquals(1, asks)
    }

    @Test fun wrongTokenOrOtherIdsTokenIs401() {
        val gate = ClientGate(Auto(), paused = { false }, ask = { _, _, _ -> true })
        val tok = allowed(gate.check("alpha", "1.1.1.1", null)).issuedToken!!
        assertEquals(401, rejected(gate.check("alpha", "1.1.1.1", tok + "x")).status)
        assertEquals(ClientGate.INVALID_TOKEN, rejected(gate.check("beta", "2.2.2.2", tok)).reason)
    }

    @Test fun deniedGetsNoToken() {
        val gate = ClientGate(Auto(), paused = { false }, ask = { _, _, _ -> false })
        assertEquals(ClientGate.DENIED, rejected(gate.check("beta", "8.8.8.8", null)).reason)
    }

    @Test fun pauseRejectsEvenWithValidToken() {
        var paused = false
        val gate = ClientGate(Auto(), paused = { paused }, ask = { _, _, _ -> true })
        val tok = allowed(gate.check("alpha", "1.1.1.1", null)).issuedToken!!
        paused = true
        assertEquals(ClientGate.PAUSED, rejected(gate.check("alpha", "1.1.1.1", tok)).reason)
    }

    @Test fun missingId() {
        val gate = ClientGate(Auto(), paused = { false }, ask = { _, _, _ -> true })
        assertEquals(ClientGate.MISSING_ID, rejected(gate.check("  ", "127.0.0.1", null)).reason)
    }

    @Test fun resetInvalidatesTokenAndNothingPersists() {
        var asks = 0
        val gate = ClientGate(Auto(), paused = { false }, ask = { _, _, _ -> asks++; true })
        val t1 = allowed(gate.check("alpha", "1.1.1.1", null)).issuedToken!!
        gate.reset(); AgentConnections.clear() // Stop / new session
        assertEquals(401, rejected(gate.check("alpha", "1.1.1.1", t1)).status)
        val t2 = allowed(gate.check("alpha", "1.1.1.1", null)).issuedToken!!
        assertNotEquals(t1, t2)
        assertEquals(2, asks) // re-prompted in the new session
    }

    @Test fun replacementRevokesPreviousToken() {
        var allow = false
        var replacing: String? = null
        val gate = ClientGate(Auto(), paused = { false }, ask = { id, _, r -> if (id == "alpha") true else { replacing = r; allow } })
        val ta = allowed(gate.check("alpha", "1.1.1.1", null)).issuedToken!!
        assertEquals(ClientGate.BUSY, rejected(gate.check("beta", "2.2.2.2", null)).reason)
        assertEquals("alpha", replacing)
        allow = true
        val tb = allowed(gate.check("beta", "2.2.2.2", null)).issuedToken!!
        assertNull(allowed(gate.check("beta", "2.2.2.2", tb)).issuedToken)
        assertEquals(ClientGate.REPLACED, rejected(gate.check("alpha", "1.1.1.1", ta)).reason)
    }

    @Test fun autoAcceptOnlyForFirstClient() {
        val gate = ClientGate(Auto(on = true), paused = { false }, ask = { _, _, _ -> false })
        assertNotNull(allowed(gate.check("alpha", "1.1.1.1", null)).issuedToken)
    }

    @Test fun tokenParsing() {
        assertEquals("abc", ClientGate.tokenFrom("Bearer abc", null))
        assertEquals("abc", ClientGate.tokenFrom("bearer  abc ", null))
        assertEquals("xyz", ClientGate.tokenFrom(null, "xyz"))
        assertNull(ClientGate.tokenFrom("Basic Zm9v", null))
        assertTrue(ClientGate.constantTimeEquals("same", "same"))
        assertTrue(!ClientGate.constantTimeEquals("same", "samf"))
    }

    @Test fun sameIdWithoutTokenIsNeverSilentlyReapprovedOrGivenTheToken() {
        // Proposal 1 as literally written (id-only reconnect) would hand the token to anyone who knows the id.
        var asks = 0
        val gate = ClientGate(Auto(), paused = { false }, ask = { _, _, _ -> asks++; true })
        val tok = allowed(gate.check("stateless-1", "1.1.1.1", null)).issuedToken!!
        repeat(3) {
            val r = rejected(gate.check("stateless-1", "9.9.9.9", null))
            assertEquals(401, r.status)
            assertTrue(!r.reason.contains(tok))
        }
        // id + valid token = silent reconnect of the same session (no prompt, no new token, same scope).
        assertNull(allowed(gate.check("stateless-1", "1.1.1.1", tok)).issuedToken)
        assertEquals(1, asks)
        // Stopped session: token dies, status says invalid, re-initialize asks the owner again.
        gate.reset()
        assertEquals(ClientGate.Status.INVALID, gate.status("stateless-1", tok))
        assertEquals(401, rejected(gate.check("stateless-1", "1.1.1.1", tok)).status)
        assertNotEquals(tok, allowed(gate.check("stateless-1", "1.1.1.1", null)).issuedToken)
        assertEquals(2, asks)
    }

    @Test fun statusNeverAsksIssuesOrLeaks() {
        var asks = 0
        var paused = false
        val gate = ClientGate(Auto(), paused = { paused }, ask = { _, _, _ -> asks++; true })
        assertEquals(ClientGate.Status.NO_TOKEN, gate.status("a", null))
        assertEquals(ClientGate.Status.MISSING_ID, gate.status(null, null))
        assertEquals(0, asks)
        val tok = allowed(gate.check("a", "1.1.1.1", null)).issuedToken!!
        assertEquals(ClientGate.Status.ALIVE, gate.status("a", tok))
        assertEquals(ClientGate.Status.INVALID, gate.status("a", tok + "x"))
        assertEquals(ClientGate.Status.INVALID, gate.status("b", tok))
        assertEquals(ClientGate.Status.NO_TOKEN, gate.status("a", null))
        paused = true
        assertEquals(ClientGate.Status.PAUSED, gate.status("a", tok))
        assertEquals("paused is only revealed to the token holder", ClientGate.Status.INVALID, gate.status("b", "zzz"))
        assertEquals(1, asks)
    }

    @Test fun onlyOneOwnerPromptAtATimeNewIdsGetApprovalPending() {
        val asks = java.util.concurrent.atomic.AtomicInteger()
        val release = java.util.concurrent.CountDownLatch(1)
        val entered = java.util.concurrent.CountDownLatch(1)
        val gate = ClientGate(Auto(), paused = { false }, ask = { _, _, _ -> asks.incrementAndGet(); entered.countDown(); release.await(); true })
        var first: ClientGate.Decision? = null
        val t = Thread { first = gate.check("first", "1.1.1.1", null) }.also { it.start() }
        entered.await()
        repeat(5) { i -> assertEquals(ClientGate.APPROVAL_PENDING, rejected(gate.check("fresh-$i", "1.1.1.1", null)).reason) }
        release.countDown(); t.join()
        assertNotNull(allowed(first!!).issuedToken)
        assertEquals(1, asks.get())
    }
}
