package pro.potoki.bekon.agent.status

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserEventsSessionTest {
    @Test fun newSessionDropsEventsOfThePreviousSession() {
        UserEvents.newSession()
        UserEvents.add("cancel_all", "agent disconnected by the owner")
        assertTrue(UserEvents.pending())
        val s = UserEvents.session
        UserEvents.newSession()                       // new token approved
        assertFalse("a phantom cancel_all must not reach the new session", UserEvents.pending())
        assertEquals(s + 1, UserEvents.session)
        assertEquals(emptyList<UserEvents.Event>(), UserEvents.drain())
        UserEvents.add("new_goal", "x")
        assertEquals(listOf("new_goal"), UserEvents.drain().map { it.type })
    }
}

class StatusSurfaceTest {
    @Test fun guidanceWithOverlayPermissionIsOnScreenNeverNotificationOnly() {
        val k = StatusSurface.decide(guidance = true, a11yOverlay = false, sawAllowed = true)
        assertEquals(StatusSurface.Kind.SAW_PILL, k)
        assertTrue(StatusSurface.onScreen(k))
        val d = StatusSurface.describe(k, true)
        assertFalse(d.contains("notification only")); assertFalse(d.contains("Accessibility"))
        assertFalse(d.contains("OFF"))
    }

    @Test fun guidanceWithoutOverlayPermissionNamesTheRealSwitch() {
        val k = StatusSurface.decide(guidance = true, a11yOverlay = false, sawAllowed = false)
        assertEquals(StatusSurface.Kind.NOTIFICATION, k)
        val d = StatusSurface.describe(k, true)
        assertTrue(d.contains("Display over other apps")); assertFalse(d.contains("Accessibility"))
    }

    @Test fun fullFlavorUsesTheAccessibilityOverlay() {
        assertEquals(StatusSurface.Kind.A11Y_PILL, StatusSurface.decide(false, a11yOverlay = true, sawAllowed = false))
        val off = StatusSurface.decide(false, a11yOverlay = false, sawAllowed = true)
        assertEquals(StatusSurface.Kind.NOTIFICATION, off)
        assertTrue(StatusSurface.describe(off, false).contains("Accessibility"))
    }
}
