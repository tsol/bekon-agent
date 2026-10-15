package pro.potoki.bekon.agent

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pro.potoki.bekon.agent.consent.PrivacyPolicy

/** The in-app privacy policy must exist and match the published play-store-pack copy. */
class PrivacyPolicyAssetTest {
    private fun find(rel: String): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, rel) }.firstOrNull { it.isFile }
            ?: error("not found: $rel")

    @Test
    fun bundledPolicyMatchesPublishedCopy() {
        val asset = find("apps/bekon-agent/src/main/assets/${PrivacyPolicy.ASSET}")
        val published = find("play-store-pack/privacy.html")
        assertEquals("update src/main/assets/privacy.html from play-store-pack/privacy.html", published.readText(), asset.readText())
    }

    @Test
    fun publicUrlIsHttps() {
        assertTrue(PrivacyPolicy.PUBLIC_URL.startsWith("https://"))
        assertEquals("file:///android_asset/privacy.html", PrivacyPolicy.ASSET_URL)
    }
}
