package pro.potoki.bekon.agent.session

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Supervises the tunnel + local server so the session survives component death.
 *
 * Loop (while alive):
 *   - ask [control.ensureUp()] to (re)start anything down and return the public URL;
 *   - on success: reset backoff, report URL via [onUrl], then poll [control.isHealthy()]
 *     every [pollMs];
 *   - on failure/health-loss: report down via [onUrl]/[onSessionDown], back off, retry.
 *
 * Pure Kotlin + coroutines, no Android types — JVM-testable with a fake [TunnelControl].
 */
class SessionSupervisor(
    private val scope: CoroutineScope,
    private val control: TunnelControl,
    private val onUrl: (String?) -> Unit,
    private val onSessionDown: (String) -> Unit,
    private val pollMs: Long = 2_000L,
    private val backoffMs: List<Long> = listOf(1_000L, 2_000L, 5_000L, 15_000L, 30_000L),
) {
    companion object { private const val TAG = "SessionSupervisor" }

    private var job: Job? = null
    @Volatile var alive: Boolean = false; private set
    private var attempt = 0

    fun start() {
        stop()
        alive = true
        attempt = 0
        job = scope.launch { supervise() }
    }

    private suspend fun supervise() {
        while (alive) {
            val url = try {
                control.ensureUp()
            } catch (e: Exception) {
                Log.w(TAG, "ensureUp: ${e.message}")
                null
            }
            if (url != null) {
                attempt = 0
                onUrl(url)
                // Serving: poll until health drops, then re-loop to restart.
                while (alive && control.isHealthy()) delay(pollMs)
                if (!alive) return
                onSessionDown("health lost while serving")
                Log.i(TAG, "supervisor: health lost, restarting")
            } else {
                onUrl(null)
                val d = backoffMs.getOrElse(attempt) { backoffMs.last() }
                attempt++
                Log.i(TAG, "supervisor: start failed, backing off ${d}ms (attempt $attempt)")
                delay(d)
            }
        }
    }

    /** External signal that a component died (e.g. MediaProjection revoked). */
    fun signalDown(reason: String) {
        if (!alive) return
        scope.launch { onSessionDown(reason) }
    }

    fun stop() {
        alive = false
        job?.cancel()
        job = null
    }
}

/**
 * How the supervisor talks to the real session. Implemented by SessionService.
 * This stays JVM-testable: no Android types except the Log call above.
 */
interface TunnelControl {
    /** Start tunnel/server if not up; return the public URL while serving (null if failed). */
    fun ensureUp(): String?
    /** True while the session should keep serving. Polled between polls. */
    fun isHealthy(): Boolean
}
