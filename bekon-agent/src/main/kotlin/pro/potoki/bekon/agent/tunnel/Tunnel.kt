package pro.potoki.bekon.agent.tunnel

/**
 * Tunnel lifecycle as a seam so phase 2 can swap in WLYA without a rewrite.
 * CloudflareTunnel is the only phase-1 implementation.
 */
interface Tunnel {
    /** Start the tunnel, returning the public URL (or null if it failed). */
    fun start(): String?
    fun stop()
    /** True while the tunnel process is alive and serving the URL. */
    fun isAlive(): Boolean
    val publicUrl: String?
}
