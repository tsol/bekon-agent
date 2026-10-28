package pro.potoki.bekon.agent.coach

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SameScreenTest {
    @Test fun ownBadgesDoNotChangeScreen() {
        assertTrue(ScreenKey.sameScreen(
            "fp:t:settings|m:system apps updater,sim cards & mobile,q search settings|b:vpn",
            "fp:t:settingso|m:system apps updater6,d-20 )|b:vpn,annantian 0 aharina"))
    }
    @Test fun captionWordsDoNotChangeScreen() {
        assertTrue(ScreenKey.sameScreen(
            "fp:m:wed 0ctober 28,play store,instagram",
            "fp:m:your turn: pick a time, then tap next,instagram play store,wed 0ctober 28"))
    }
    @Test fun realChangeDetected() {
        assertFalse(ScreenKey.sameScreen(
            "fp:t:settings|m:system apps updater,sim cards & mobile,q search settings|b:vpn",
            "fp:t:wi-fi|m:wi-fi assistant,available networks|b:add network"))
        assertFalse(ScreenKey.sameScreen("win:a", "win:b"))
    }
}
