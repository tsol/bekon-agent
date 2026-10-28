package pro.potoki.bekon.agent.coach

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangeWatchTest {
    @After fun tearDown() { ChangeWatch.disarm(); ChangeWatch.onChange = null; ChangeWatch.touched(0) }

    @Test fun firesWithinTwoFramesOfAreaChange() {
        var firedAt = -1L
        ChangeWatch.onChange = { firedAt = it }
        ChangeWatch.arm(listOf(intArrayOf(100, 100, 400, 200)), 1080, now = 0)
        var screen = 200
        for (t in 300L..1000L step 16) assertFalse(ChangeWatch.offer(t) { _, _ -> screen })
        screen = 120            // tap ripple / new screen
        ChangeWatch.offer(1016) { _, _ -> screen }
        assertTrue(ChangeWatch.offer(1032) { _, _ -> screen })
        assertEquals(1032L, firedAt)
        assertFalse(ChangeWatch.active)
    }

    @Test fun touchBoostFiresOnFirstSmallChange() {
        ChangeWatch.arm(listOf(intArrayOf(0, 0, 1000, 500)), 1080, now = 0)
        ChangeWatch.offer(400) { _, _ -> 200 }
        ChangeWatch.touched(500)
        // 10% of the probes change (icon press) — below the normal 15 %, above the boosted 6 %.
        // First boosted hit waits one frame (a scroll may follow); the next moving frame confirms.
        assertFalse(ChangeWatch.offer(520) { x, _ -> if (x < 100) 100 else 200 })
        assertTrue(ChangeWatch.offer(536, motion = true) { x, _ -> if (x < 100) 100 else 200 })
    }

    @Test fun pressHighlightThenScrollDoesNotFire() {
        ChangeWatch.arm(listOf(intArrayOf(0, 0, 1000, 500)), 1080, now = 0)
        ChangeWatch.offer(400) { _, _ -> 200 }
        ChangeWatch.touched(500)
        assertFalse(ChangeWatch.offer(520) { x, _ -> if (x < 100) 100 else 200 })   // row press highlight
        ChangeWatch.rebase(536)                                                    // the content scrolled
        assertFalse(ChangeWatch.offer(552) { _, _ -> 200 })
        assertFalse(ChangeWatch.offer(568) { _, _ -> 200 })
        assertTrue(ChangeWatch.active)
    }

    @Test fun staticAndNoisyAreaNeverFires() {
        var fired = false
        ChangeWatch.onChange = { fired = true }
        ChangeWatch.arm(listOf(intArrayOf(0, 0, 300, 100)), 1080, now = 0)
        val rnd = java.util.Random(7)
        for (t in 300L..30_000L step 16) ChangeWatch.offer(t) { _, _ -> 180 + rnd.nextInt(9) - 4 }
        assertFalse(fired)
    }

    @Test fun changeOutsideAreaOrMaskedIgnored() {
        var fired = false
        ChangeWatch.onChange = { fired = true }
        ChangeWatch.arm(listOf(intArrayOf(0, 0, 100, 100)), 1080, now = 0)
        ChangeWatch.offer(400) { _, _ -> 200 }
        for (t in 416L..2000L step 16) ChangeWatch.offer(t) { x, _ -> if (x < 50) -1 else 200 }
        assertFalse(fired)
    }
}
