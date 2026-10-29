package pro.potoki.bekon.agent.session

/**
 * Stop is final for a session: once [markStopped] ran, nothing may post or re-post the session
 * notification, re-promote the service, show a coach hint or treat the end of screen sharing as a
 * "capture lost" event (the projection ending is the expected result of Stop, not a loss).
 *
 * 1.0.40 fix: on Android 10+ the system ends a MediaProjection as soon as the service's foreground
 * type drops mediaProjection. The notification's "Stop & disconnect" used to go through
 * onStartCommand, which re-promoted the service WITHOUT the projection type before stopping; the
 * system then fired MediaProjection.Callback.onStop on the capture thread while Stop was running,
 * the capture-lost handler still saw a running session and called NotificationManager.notify()
 * after the service was gone: an orphan ongoing "Bekon is ready for your agent" notification.
 *
 * Pure logic (no Android types) so it is covered by plain JVM unit tests.
 */
class StopGuard {
    private val lock = Any()
    @Volatile var stopped = false
        private set

    /** Marks the session stopped. True the first time. */
    fun markStopped(): Boolean = synchronized(lock) { if (stopped) false else { stopped = true; true } }

    /** A new explicit Start on the same service instance. */
    fun reset() = synchronized(lock) { stopped = false }

    /** Runs [block] only while not stopped, atomically with respect to [markStopped]. */
    fun <T> ifRunning(block: () -> T): T? = synchronized(lock) { if (stopped) null else block() }

    enum class Promote { NONE, PLAIN, WITH_PROJECTION }

    companion object {
        const val ACTION_START = "pro.potoki.bekon.agent.action.START"
        const val ACTION_STOP = "pro.potoki.bekon.agent.action.STOP"
        const val ACTION_REPOST = "pro.potoki.bekon.agent.action.REPOST_NOTIFICATION"

        /**
         * What onStartCommand does with the foreground state before handling [action].
         *  - Stop never (re)promotes: it is delivered with startService (no startForeground needed), and a
         *    re-promotion without the projection type makes Android end the projection under our feet.
         *  - Re-post of a swiped notification only while running, keeping the projection type if sharing.
         *  - Start / sticky restart: keep the projection type while capture is granted (never drop it).
         */
        fun promoteFor(action: String?, running: Boolean, captureGranted: Boolean): Promote = when (action) {
            ACTION_STOP -> Promote.NONE
            ACTION_REPOST -> when {
                !running -> Promote.NONE
                captureGranted -> Promote.WITH_PROJECTION
                else -> Promote.PLAIN
            }
            else -> if (running && captureGranted) Promote.WITH_PROJECTION else Promote.PLAIN
        }

        /** The projection ended: react (state, hint, notification) only for a live, unstopped session that was sharing. */
        fun shouldHandleCaptureLost(stopped: Boolean, running: Boolean, captureGranted: Boolean): Boolean =
            !stopped && running && captureGranted
    }
}
