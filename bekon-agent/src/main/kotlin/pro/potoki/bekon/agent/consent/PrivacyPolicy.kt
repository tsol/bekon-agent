package pro.potoki.bekon.agent.consent

/**
 * Where the privacy policy lives.
 *
 * The in-app button always shows the bundled copy ([ASSET]), so it works offline and even when
 * the website is down. [PUBLIC_URL] is the address given to Google Play Console and offered as
 * a secondary "Open web version" link; change it here only.
 * The bundled file is a copy of play-store-pack/privacy.html (PrivacyPolicyAssetTest keeps them equal).
 */
object PrivacyPolicy {
    const val PUBLIC_URL = "https://bekon.potoki.pro/privacy"
    const val ASSET = "privacy.html"
    const val ASSET_URL = "file:///android_asset/$ASSET"
}
