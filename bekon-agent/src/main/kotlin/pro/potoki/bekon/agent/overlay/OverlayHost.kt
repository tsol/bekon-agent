package pro.potoki.bekon.agent.overlay

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.WindowManager
import pro.potoki.bekon.agent.BuildConfig

/**
 * Chooses the overlay window type for coach marks / status pill.
 * - full flavor: TYPE_ACCESSIBILITY_OVERLAY (hosted by TouchService; no SAW needed)
 * - playStore / guidance: TYPE_APPLICATION_OVERLAY (needs SYSTEM_ALERT_WINDOW)
 */
object OverlayHost {
    val guidance: Boolean get() = BuildConfig.GUIDANCE_MODE

    fun canDraw(ctx: Context): Boolean =
        if (!guidance) true
        else if (Build.VERSION.SDK_INT >= 23) Settings.canDrawOverlays(ctx)
        else true

    fun windowType(): Int =
        if (guidance && Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else if (guidance)
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        else
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY

    /** Full-screen pass-through (coach marks). */
    fun passThroughParams(): WindowManager.LayoutParams {
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            windowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        if (Build.VERSION.SDK_INT >= 28) {
            p.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        return p
    }

    /** Small touchable window (status pill). */
    fun touchableWrapParams(): WindowManager.LayoutParams {
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        if (Build.VERSION.SDK_INT >= 28) {
            p.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        return p
    }

    fun openOverlaySettings(ctx: Context) {
        val i = if (Build.VERSION.SDK_INT >= 23) {
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + ctx.packageName),
            )
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }

    /** OEM note for Xiaomi/MIUI-like: Display over other apps + sometimes "Display pop-up windows". */
    fun oemOverlayHint(ctx: Context): String {
        val m = Build.MANUFACTURER.lowercase()
        return when {
            m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") ->
                ctx.getString(pro.potoki.bekon.agent.R.string.overlay_hint_xiaomi)
            m.contains("samsung") ->
                ctx.getString(pro.potoki.bekon.agent.R.string.overlay_hint_samsung)
            else ->
                ctx.getString(pro.potoki.bekon.agent.R.string.overlay_hint_generic)
        }
    }
}
