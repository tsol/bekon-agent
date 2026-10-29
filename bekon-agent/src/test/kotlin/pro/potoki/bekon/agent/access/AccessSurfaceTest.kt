package pro.potoki.bekon.agent.access

import org.junit.Assert.assertEquals
import org.junit.Test

class AccessSurfaceTest {
    @Test fun backgroundWithOverlayPermissionUsesTheOverlayPrompt() {
        assertEquals(AccessSurface.Ui.OVERLAY, AccessSurface.plan(foreground = false, canOverlay = true))
    }
    @Test fun foregroundKeepsTheInAppDialog() {
        assertEquals(AccessSurface.Ui.ACTIVITY, AccessSurface.plan(foreground = true, canOverlay = true))
        assertEquals(AccessSurface.Ui.ACTIVITY, AccessSurface.plan(foreground = true, canOverlay = false))
    }
    @Test fun backgroundWithoutOverlayFallsBackToActivityPlusNotification() {
        assertEquals(AccessSurface.Ui.ACTIVITY, AccessSurface.plan(foreground = false, canOverlay = false))
    }
}
