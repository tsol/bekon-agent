package pro.potoki.bekon.agent.device.capture

import org.junit.Assert.assertEquals
import org.junit.Test
import pro.potoki.bekon.agent.core.ScreenshotDefaults

class CaptureSizeTest {
    @Test
    fun `default screenshot is four times smaller than the screen`() {
        assertEquals(0.25f, ScreenshotDefaults.SCALE)
        val (w, h) = CapturePrefs.pixelSize(1080, 2340, ScreenshotDefaults.SCALE)
        assertEquals(270, w)
        assertEquals(585, h)
    }
}
