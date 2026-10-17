package pro.potoki.bekon.agent.consent

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.Settings
import pro.potoki.bekon.agent.BuildConfig

/**
 * Google Play "Prominent disclosure and consent" for the Accessibility API (and the optional IME).
 * Settings screens for these capabilities are only ever opened through here, after Accept.
 * Decline leaves the capability off and the disclosure can be shown again any time.
 */
object Consent {
    /** Public (Play Console) URL; see [PrivacyPolicy]. The in-app button uses the bundled copy. */
    const val PRIVACY_POLICY_URL = PrivacyPolicy.PUBLIC_URL

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
        openAccessibilityDirect(ctx)
    }

    /**
     * Land as close as possible to "Bekon Gestures": Android 11+ details screen for our service,
     * then the list with our row highlighted (AOSP/Pixel/Samsung honour :settings:fragment_args_key),
     * then the plain list.
     */
    fun openAccessibilityDirect(ctx: Context) {
        val cn = android.content.ComponentName(ctx, pro.potoki.bekon.agent.device.touch.TouchService::class.java).flattenToString()
        val candidates = buildList {
            if (android.os.Build.VERSION.SDK_INT >= 30) add(
                Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").putExtra(Intent.EXTRA_COMPONENT_NAME, cn),
            )
            add(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                val args = android.os.Bundle().apply { putString(":settings:fragment_args_key", cn) }
                putExtra(":settings:fragment_args_key", cn)
                putExtra(":settings:show_fragment_args", args)
            })
            add(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        for (i in candidates) {
            if (ctx !is Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { ctx.startActivity(i); return } catch (_: Exception) {}
        }
    }

    fun openKeyboardSettings(ctx: Context) {
        if (!imeAvailable()) return
        if (!accepted(ctx, Kind.IME)) return showDisclosure(ctx, Kind.IME)
        start(ctx, Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
    }

    /** Opens the bundled policy in-app (offline-safe); never depends on the website being up. */
    fun openPrivacyPolicy(ctx: Context) = start(ctx, Intent(ctx, PrivacyPolicyActivity::class.java))

    internal fun start(ctx: Context, i: Intent) {
        if (ctx !is Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(i) } catch (_: Exception) {}
    }
}
