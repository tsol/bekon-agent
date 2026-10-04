package pro.potoki.bekon.agent.tunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TunnelUrlTest {
    @Test
    fun `skips the api host and keeps the quick tunnel`() {
        val log = "requesting https://api.trycloudflare.com then https://abc-def.trycloudflare.com"
        assertEquals("https://abc-def.trycloudflare.com", tunnelPublicUrl(log))
    }

    @Test
    fun `api alone is not a public url`() {
        assertNull(tunnelPublicUrl("https://api.trycloudflare.com/v1"))
    }
}
