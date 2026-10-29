package pro.potoki.bekon.agent.session

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pro.potoki.bekon.agent.session.StopGuard.Promote

/** 1.0.40: after Stop nothing re-posts the session notification (notification, card or pill-menu Stop). */
class StopGuardTest {

    @Test fun stopNeverRepromotes() {
        // The bug: Stop & disconnect re-promoted the FGS without the projection type -> Android ended the
        // projection -> capture-lost handler re-posted the notification after the service died.
        for (running in listOf(true, false)) for (granted in listOf(true, false))
            assertEquals(Promote.NONE, StopGuard.promoteFor(StopGuard.ACTION_STOP, running, granted))
    }

    @Test fun repostOnlyWhileRunningAndKeepsProjection() {
        assertEquals(Promote.NONE, StopGuard.promoteFor(StopGuard.ACTION_REPOST, running = false, captureGranted = false))
        assertEquals(Promote.NONE, StopGuard.promoteFor(StopGuard.ACTION_REPOST, running = false, captureGranted = true))
        assertEquals(Promote.PLAIN, StopGuard.promoteFor(StopGuard.ACTION_REPOST, running = true, captureGranted = false))
        assertEquals(Promote.WITH_PROJECTION, StopGuard.promoteFor(StopGuard.ACTION_REPOST, running = true, captureGranted = true))
    }

    @Test fun startAndStickyRestartNeverDropAProjectionInUse() {
        assertEquals(Promote.PLAIN, StopGuard.promoteFor(StopGuard.ACTION_START, running = false, captureGranted = false))
        assertEquals(Promote.WITH_PROJECTION, StopGuard.promoteFor(StopGuard.ACTION_START, running = true, captureGranted = true))
        assertEquals(Promote.PLAIN, StopGuard.promoteFor(null, running = false, captureGranted = false))
    }

    @Test fun captureLostIgnoredAfterStop() {
        assertTrue(StopGuard.shouldHandleCaptureLost(stopped = false, running = true, captureGranted = true))
        assertFalse(StopGuard.shouldHandleCaptureLost(stopped = true, running = true, captureGranted = true))
        assertFalse(StopGuard.shouldHandleCaptureLost(stopped = false, running = false, captureGranted = true))
        assertFalse(StopGuard.shouldHandleCaptureLost(stopped = false, running = true, captureGranted = false))
    }

    @Test fun noUpdateRunsAfterStop() {
        val g = StopGuard()
        assertEquals(1, g.ifRunning { 1 })
        assertTrue(g.markStopped())
        assertFalse(g.markStopped())
        assertNull(g.ifRunning { error("notification posted after Stop") })
        g.reset()
        assertEquals(2, g.ifRunning { 2 })
    }

    /** A late updater (capture thread, flow collector) racing with Stop never posts after Stop returned. */
    @Test fun racingUpdatesNeverLandAfterStop() {
        repeat(200) {
            val g = StopGuard()
            val posted = AtomicInteger(0)
            val postedAfterStop = AtomicInteger(0)
            val stopped = java.util.concurrent.atomic.AtomicBoolean(false)
            val go = CountDownLatch(1)
            val updaters = (1..4).map {
                Thread {
                    go.await()
                    repeat(200) { g.ifRunning { posted.incrementAndGet(); if (stopped.get()) postedAfterStop.incrementAndGet() } }
                }.apply { start() }
            }
            go.countDown()
            g.markStopped(); stopped.set(true)
            updaters.forEach { it.join() }
            assertEquals(0, postedAfterStop.get())
        }
    }

    /** Wiring: the service routes Stop, the capture-lost callback and every notify through the guard. */
    @Test fun serviceIsWiredThroughTheGuard() {
        val f = File("src/main/kotlin/pro/potoki/bekon/agent/session/SessionService.kt")
        val src = f.readText()
        assertTrue(src.contains("StopGuard.promoteFor(intent?.action"))
        assertTrue(src.contains("StopGuard.shouldHandleCaptureLost(guard.stopped"))
        assertTrue(src.contains("onSystemStop = { main.post { onCaptureLost() } }"))
        val notifyCalls = Regex("""nm\.notify\(""").findAll(src).count()
        assertEquals("exactly one notify() call, inside guard.ifRunning", 1, notifyCalls)
        assertTrue(Regex("""guard\.ifRunning \{\s*val nm = getSystemService\(NotificationManager::class\.java\)\s*nm\.notify""").containsMatchIn(src))
        val stop = src.substringAfter("private fun stopSession() {")
        assertTrue("stopSession marks stopped first", stop.trimStart().lines().first { !it.trim().startsWith("//") }.contains("guard.markStopped()"))
        val cap = File("src/main/kotlin/pro/potoki/bekon/agent/device/capture/ScreenCapture.kt").readText()
        assertTrue("own stop() is not a system stop", cap.contains("if (released) {"))
    }
}
