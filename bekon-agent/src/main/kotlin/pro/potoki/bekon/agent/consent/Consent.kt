package pro.potoki.bekon.agent.consent

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import pro.potoki.bekon.agent.BuildConfig

/**
 * Google Play "Prominent disclosure and consent" for the Accessibility API (and the optional IME).
 * Settings screens for these capabilities are only ever opened through here, after Accept.
 * Decline leaves the capability off and the disclosure can be shown again any time.
 */
object Consent {
    // TODO(play): replace with the real hosted privacy policy before submitting to Play.
    const val PRIVACY_POLICY_URL = "https://bekon.potoki.pro/privacy"

    enum class Kind { A11Y, IME }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences("bekon_consent", Context.MODE_PRIVATE)

    fun accepted(ctx: Context, kind: Kind): Boolean = prefs(ctx).getBoolean(kind.name, false)

    fun set(ctx: Context, kind: Kind, ok: Boolean) {
        prefs(ctx).edit().putBoolean(kind.name, ok).apply()
    }

    fun imeAvailable(): Boolean = BuildConfig.HAS_IME

    /** Show the disclosure (always), then on Accept continue to system settings. */
    fun showDisclosure(ctx: Context, kind: Kind, thenOpenSettings: Boolean = true) {
        val i = Intent(ctx, DisclosureActivity::class.java)
            .putExtra(DisclosureActivity.EXTRA_KIND, kind.name)
            .putExtra(DisclosureActivity.EXTRA_OPEN, thenOpenSettings)
        if (ctx !is Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }

    fun openAccessibilitySettings(ctx: Context) {
        if (!accepted(ctx, Kind.A11Y)) return showDisclosure(ctx, Kind.A11Y)
        start(ctx, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    fun openKeyboardSettings(ctx: Context) {
        if (!imeAvailable()) return
        if (!accepted(ctx, Kind.IME)) return showDisclosure(ctx, Kind.IME)
        start(ctx, Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
    }

    fun openPrivacyPolicy(ctx: Context) = start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_POLICY_URL)))

    internal fun start(ctx: Context, i: Intent) {
        if (ctx !is Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(i) } catch (_: Exception) {}
    }
}
