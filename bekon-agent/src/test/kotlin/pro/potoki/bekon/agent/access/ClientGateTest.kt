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

    @Test fun approvalIssuesRandomTokenThenLostTokenNeedsANewPrompt() {
        var asks = 0
        val replacing = ArrayList<String?>()
        val gate = ClientGate(Auto(), paused = { false }, ask = { _, _, rep -> asks++; replacing += rep; true })
        val tok = allowed(gate.check("alpha", "1.1.1.1", null)).issuedToken
        assertNotNull(tok)
        assertTrue("32 bytes base64url", tok!!.length == 43 && tok.matches(Regex("[A-Za-z0-9_-]+")))
        // valid token: allowed, no new token, no new prompt
        assertNull(allowed(gate.check("alpha", "1.1.1.1", tok)).issuedToken)
        assertEquals(1, asks)
        // approved id without token (lost it): the owner IS asked again; Allow issues a NEW token, the old one dies
        val tok2 = allowed(gate.check("alpha", "1.1.1.1", null)).issuedToken!!
        assertEquals(2, asks)
        assertEquals("the prompt says it is the same agent asking again", "alpha", replacing.last())
        assertNotEquals(tok, tok2)
        assertEquals(401, rejected(gate.check("alpha", "1.1.1.1", tok)).status)
        assertEquals(ClientGate.Status.ALIVE, gate.status("alpha", tok2))
    }

    @Test fun lostTokenPromptDeniedKeepsTheOldSessionAndNeverLeaksIt() {
        val answers = java.util.ArrayDeque(listOf(true, false, false, false))
        var asks = 0
        val gate = ClientGate(Auto(), paused = { false }, ask = { _, _, _ -> asks++; answers.poll() ?: false })
        val tok = allowed(gate.check("alpha", "1.1.1.1", null)).issuedToken!!
        repeat(3) {
            val r = rejected(gate.check("alpha", "6.6.6.6", null))
            assertEquals(ClientGate.PROMPT_CLOSED, r.reason)
            assertTrue(!r.reason.contains(tok))
        }
        assertEquals("prompted every time, never silently", 4, asks)
        assertEquals(ClientGate.Status.ALIVE, gate.status("alpha", tok))
        assertNull(allowed(gate.check("alpha", "1.1.1.1", tok)).issuedToken)
    }

    @Test fun wrongTokenOrOtherIdsTokenIs401() {
        val gate = ClientGate(Auto(), paused = { false }, ask = { _, _, _ -> true })
        val tok = allowed(gate.check("alpha", "1.1.1.1", null)).issuedToken!!
        assertEquals(401, rejected(gate.check("alpha", "1.1.1.1", tok + "x")).status)
        assertEquals(ClientGate.INVALID_TOKEN, rejected(gate.check("beta", "2.2.2.2", tok)).reason)
    }

    @Test fun deniedGetsNoToken() {
        val gate = ClientGate(Auto(), paused = { false }, ask = { _, _, _ -> false })
        val r = rejected(gate.check("beta", "8.8.8.8", null))
        assertEquals(ClientGate.PROMPT_CLOSED, r.reason)
        assertTrue(r.reason.startsWith(ClientGate.DENIED))
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
            // Every id-only call is a fresh owner prompt; Allow gives a NEW token, never the old one back.
            val t = allowed(gate.check("stateless-1", "9.9.9.9", null)).issuedToken!!
            assertNotEquals(tok, t)
        }
        assertEquals(4, asks)
        val cur = gate.tokenFor("stateless-1")!!
        assertEquals(401, rejected(gate.check("stateless-1", "1.1.1.1", tok)).status)
        // id + valid token = silent reconnect of the same session (no prompt, no new token, same scope).
        assertNull(allowed(gate.check("stateless-1", "1.1.1.1", cur)).issuedToken)
        assertEquals(4, asks)
        // Stopped session: token dies, status says invalid, re-initialize asks the owner again.
        gate.reset()
        assertEquals(ClientGate.Status.INVALID, gate.status("stateless-1", cur))
        assertEquals(401, rejected(gate.check("stateless-1", "1.1.1.1", cur)).status)
        assertNotEquals(cur, allowed(gate.check("stateless-1", "1.1.1.1", null)).issuedToken)
        assertEquals(5, asks)
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

    /** Owner prompt the test answers by hand. */
    private class Owner {
        val asked = java.util.concurrent.atomic.AtomicInteger()
        @Volatile var answer: Boolean? = null
        val shown = java.util.concurrent.CountDownLatch(1)
        val ask: (String, String, String?) -> Boolean = { _, _, _ ->
            asked.incrementAndGet(); shown.countDown()
            while (answer == null) Thread.sleep(5)
            answer!!
        }
    }

    @Test fun slowOwnerStatelessAgentGetsTokenOnFirstCallAndPollsWithIt() {
        val owner = Owner()
        val gate = ClientGate(Auto(), paused = { false }, holdMs = 50, ask = owner.ask)
        // 1st stateless call: owner hasn't tapped yet -> pending WITH the token (nothing to lose).
        val p = gate.check("madisson-geo", "1.1.1.1", null) as ClientGate.Decision.Pending
        assertEquals(ClientGate.Status.PENDING, gate.status("madisson-geo", p.token))
        // The pending token is not usable yet anywhere.
        assertNull(gate.tokenFor("madisson-geo")); assertNull(gate.clientForToken(p.token))
        // Poll with the token: still pending, same token, no second prompt.
        assertEquals(p.token, (gate.check("madisson-geo", "1.1.1.1", p.token) as ClientGate.Decision.Pending).token)
        // A different new id while the prompt is open: approval_pending, no token, no prompt.
        assertEquals(ClientGate.APPROVAL_PENDING, rejected(gate.check("other-new", "2.2.2.2", null)).reason)
        owner.answer = true
        // Next poll with the token: approved, token delivered (same value), now valid.
        val ok = allowed(gate.check("madisson-geo", "1.1.1.1", p.token))
        assertEquals(p.token, ok.issuedToken)
        assertEquals(p.token, gate.tokenFor("madisson-geo"))
        assertNull(allowed(gate.check("madisson-geo", "1.1.1.1", p.token)).issuedToken)
        assertEquals(ClientGate.Status.ALIVE, gate.status("madisson-geo", p.token))
        // The id alone never gets the token after approval: it is a new owner prompt (here: Deny).
        owner.answer = false
        val r = rejected(gate.check("madisson-geo", "9.9.9.9", null))
        assertEquals(ClientGate.PROMPT_CLOSED, r.reason); assertTrue(!r.reason.contains(p.token))
        assertEquals(2, owner.asked.get())
        assertEquals(ClientGate.Status.ALIVE, gate.status("madisson-geo", p.token))
    }

    @Test fun ownerAllowsWithinHoldTheSameRequestGetsTheToken() {
        val owner = Owner()
        val gate = ClientGate(Auto(), paused = { false }, holdMs = 5_000, ask = owner.ask)
        Thread { owner.shown.await(); Thread.sleep(50); owner.answer = true }.start()
        val tok = allowed(gate.check("fast", "1.1.1.1", null)).issuedToken
        assertNotNull(tok); assertEquals(tok, gate.tokenFor("fast"))
    }

    @Test fun deniedOrStoppedPendingTokenIsVoid() {
        val owner = Owner()
        val gate = ClientGate(Auto(), paused = { false }, holdMs = 30, ask = owner.ask)
        val p = gate.check("d", "1.1.1.1", null) as ClientGate.Decision.Pending
        owner.answer = false
        Thread.sleep(50)
        assertEquals(ClientGate.PROMPT_CLOSED, rejected(gate.check("d", "1.1.1.1", p.token)).reason)
        assertNull(gate.tokenFor("d"))
        // Stop while pending: a late Allow issues nothing.
        val owner2 = Owner()
        val g2 = ClientGate(Auto(), paused = { false }, holdMs = 30, ask = owner2.ask)
        val p2 = g2.check("s", "1.1.1.1", null) as ClientGate.Decision.Pending
        g2.reset()
        owner2.answer = true
        Thread.sleep(50)
        assertNull(g2.tokenFor("s"))
        assertTrue(g2.check("s", "1.1.1.1", p2.token) is ClientGate.Decision.Reject)
        assertEquals(ClientGate.Status.INVALID, g2.status("s", p2.token))
    }
}
