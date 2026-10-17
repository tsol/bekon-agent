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

    private fun tryFind(rel: String): File? =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, rel) }.firstOrNull { it.isFile }

    @Test
    fun bundledPolicyMatchesPublishedCopy() {
        // Only meaningful inside the monorepo (play-store-pack/ is not published with the standalone app repo).
        val published = tryFind("play-store-pack/privacy.html")
        org.junit.Assume.assumeTrue("play-store-pack not present (standalone repo)", published != null)
        val asset = tryFind("apps/bekon-agent/src/main/assets/${PrivacyPolicy.ASSET}")
            ?: find("bekon-agent/src/main/assets/${PrivacyPolicy.ASSET}")
        published!!
        assertEquals("update src/main/assets/privacy.html from play-store-pack/privacy.html", published.readText(), asset.readText())
    }

    @Test
    fun publicUrlIsHttps() {
        assertTrue(PrivacyPolicy.PUBLIC_URL.startsWith("https://"))
        assertEquals("file:///android_asset/privacy.html", PrivacyPolicy.ASSET_URL)
    }
}
