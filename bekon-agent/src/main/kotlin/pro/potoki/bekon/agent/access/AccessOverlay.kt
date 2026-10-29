package pro.potoki.bekon.agent.access

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Where the Allow/Deny prompt is shown. MIUI (and Android 10+) block activity starts from the background and MIUI
 * hides heads-up notifications of new apps by default, so with Bekon in the background the activity + notification
 * alone are often invisible. When "Display over other apps" is allowed the prompt is drawn as an overlay dialog.
 */
object AccessSurface {
    enum class Ui { ACTIVITY, OVERLAY }

    /** Notification is always posted as well (Allow/Deny actions + full-screen intent). */
    fun plan(foreground: Boolean, canOverlay: Boolean): Ui = when {
        foreground -> Ui.ACTIVITY      // our activity is visible: an in-app dialog is the normal UI
        canOverlay -> Ui.OVERLAY       // background + SAW: always visible, unlike a blocked activity start
        else -> Ui.ACTIVITY            // best effort (may be blocked); the notification is the fallback
    }

    fun canOverlay(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 23 || try { Settings.canDrawOverlays(ctx) } catch (_: Throwable) { false }
}

/** The prompt as a TYPE_APPLICATION_OVERLAY dialog (main thread only). */
internal object AccessOverlay {
    private const val TAG = "BekonAccess"
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var view: View? = null
    @Volatile private var shownToken: String? = null

    fun show(ctx: Context, token: String, title: String, message: String, allow: String, deny: String): Boolean {
        val app = ctx.applicationContext
        if (!AccessSurface.canOverlay(app)) return false
        main.post { addView(app, token, title, message, allow, deny) }
        return true
    }

    fun hide(ctx: Context) {
        main.post {
            val v = view ?: return@post
            view = null; shownToken = null
            try { ctx.applicationContext.getSystemService(WindowManager::class.java).removeView(v) } catch (_: Throwable) {}
        }
    }

    private fun dp(ctx: Context, v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt()

    private fun addView(ctx: Context, token: String, title: String, message: String, allow: String, deny: String) {
        val wm = ctx.getSystemService(WindowManager::class.java)
        view?.let { old -> try { wm.removeView(old) } catch (_: Throwable) {} }
        view = null
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 22f), dp(ctx, 20f), dp(ctx, 22f), dp(ctx, 12f))
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(ctx, 18f).toFloat() }
            elevation = dp(ctx, 12f).toFloat()
        }
        card.addView(TextView(ctx).apply {
            text = title; setTextColor(Color.BLACK); setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        card.addView(TextView(ctx).apply {
            text = message; setTextColor(0xFF333333.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(0, dp(ctx, 10f), 0, dp(ctx, 14f))
        })
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        fun btn(label: String, ok: Boolean) = Button(ctx).apply {
            text = label
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            // Tapjacking guard: ignore taps while another window covers this one.
            filterTouchesWhenObscured = true
            setOnClickListener { AccessPromptActivity.deliverFromNotification(ctx, token, ok) }
        }
        row.addView(btn(deny, false))
        row.addView(btn(allow, true).apply { setTextColor(0xFFB3261E.toInt()) })
        card.addView(row)
        val root = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER
            setBackgroundColor(0x99000000.toInt())
            setPadding(dp(ctx, 24f), 0, dp(ctx, 24f), 0)
            addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            contentDescription = title
        }
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.CENTER; setTitle("BekonAccessPrompt") }
        try {
            wm.addView(root, lp)
            view = root; shownToken = token
            Log.i(TAG, "access prompt shown as overlay")
        } catch (e: Throwable) {
            Log.w(TAG, "overlay prompt failed: $e")
        }
    }
}
