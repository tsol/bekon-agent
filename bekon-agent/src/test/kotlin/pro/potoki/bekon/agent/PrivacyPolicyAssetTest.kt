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

    private fun check(publishedRel: String, assetDir: String) {
        val published = tryFind("play-store-pack/$publishedRel")
        org.junit.Assume.assumeTrue("play-store-pack not present (standalone repo)", published != null)
        val asset = tryFind("apps/bekon-agent/src/$assetDir/assets/${PrivacyPolicy.ASSET}")
            ?: find("bekon-agent/src/$assetDir/assets/${PrivacyPolicy.ASSET}")
        assertEquals("update src/$assetDir/assets/privacy.html from play-store-pack/$publishedRel", published!!.readText(), asset.readText())
    }

    /** Google Play (guidance) build ships the published policy; it overrides the main asset. */
    @Test
    fun bundledPolicyMatchesPublishedCopy() = check("privacy.html", "playStore")

    /** Sideloaded full build keeps the policy that also covers the optional Accessibility service. */
    @Test
    fun fullPolicyMatchesFullCopy() = check("privacy-full.html", "main")

    @Test
    fun publicUrlIsHttps() {
        assertTrue(PrivacyPolicy.PUBLIC_URL.startsWith("https://"))
        assertEquals("file:///android_asset/privacy.html", PrivacyPolicy.ASSET_URL)
    }
}
