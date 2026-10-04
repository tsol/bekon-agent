package pro.potoki.bekon.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeStateTest {

    @Test
    fun `idle state cannot start and has no qr`() {
        val s = HomeState()
        assertTrue(s.canStart)
        assertFalse(s.canShowQr)
        assertFalse(s.isRunning)
        assertEquals(SessionPhase.IDLE, s.phase)
    }

    @Test
    fun `serving state with url can show qr`() {
        val s = HomeState(phase = SessionPhase.SERVING, active = true, publicUrl = "https://x.trycloudflare.com")
        assertTrue(s.isRunning)
        assertTrue(s.canShowQr)
        assertFalse(s.canStart)
    }

    @Test
    fun `default tunnel kind is cloudflare`() {
        assertEquals(TunnelKind.CLOUDFLARE, HomeState().tunnelKind)
    }
}
