package pro.potoki.bekon.agent.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import pro.potoki.bekon.agent.BuildConfig
import pro.potoki.bekon.agent.R

/** Public source of exactly this build: the release tag in tsol/bekon-agent. */
object SourceCode {
    const val REPO = "https://github.com/tsol/bekon-agent"
    val url: String get() = "$REPO/tree/v${BuildConfig.VERSION_NAME}"

    fun prompt(ctx: Context): String = ctx.getString(R.string.audit_prompt, BuildConfig.VERSION_NAME, url)

    /** Copy the audit prompt and offer the share sheet so the human can send it to their agent. */
    fun askAgentToAudit(ctx: Context) {
        val text = prompt(ctx)
        try {
            (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("bekon audit prompt", text))
            Toast.makeText(ctx, ctx.getString(R.string.audit_copied), Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {}
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        try { ctx.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
    }

    fun open(ctx: Context) {
        try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
    }
}
