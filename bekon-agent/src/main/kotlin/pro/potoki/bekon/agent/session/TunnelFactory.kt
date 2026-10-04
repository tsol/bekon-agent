package pro.potoki.bekon.agent.session

import android.content.Context
import pro.potoki.bekon.agent.tunnel.CloudflareTunnel
import pro.potoki.bekon.agent.tunnel.Tunnel

/** Constructs tunnel implementations so the session does not hard-code one. */
object TunnelFactory {
    fun cloudflare(context: Context, target: String): Tunnel = CloudflareTunnel(context, target)
}
