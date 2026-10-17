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
    const val OWNER_REPO = "tsol/bekon-agent"
    const val REPO = "https://github.com/$OWNER_REPO"
    private const val PKG = "bekon-agent/src/main/kotlin/pro/potoki/bekon/agent"

    /** Repo-relative paths (in tsol/bekon-agent) of the files an auditor should read first. Checked by SourceCodeTest. */
    val KEY_FILES = listOf(
        "SECURITY-AUDIT.md",
        "bekon-agent/src/main/AndroidManifest.xml",
        "$PKG/mcp/McpHandler.kt",
        "$PKG/access/ClientGate.kt",
        "$PKG/server/LocalServer.kt",
        "$PKG/session/SessionService.kt",
        "$PKG/tunnel/CloudflareTunnel.kt",
        "$PKG/core/DeviceExecutor.kt",
        "$PKG/device/capture/ScreenCapture.kt",
    )

    fun tag(version: String = BuildConfig.VERSION_NAME) = "v$version"
    fun treeUrl(version: String = BuildConfig.VERSION_NAME) = "$REPO/tree/${tag(version)}"
    fun zipUrl(version: String = BuildConfig.VERSION_NAME) = "$REPO/archive/refs/tags/${tag(version)}.zip"
    fun rawUrl(path: String, version: String = BuildConfig.VERSION_NAME) =
        "https://raw.githubusercontent.com/$OWNER_REPO/${tag(version)}/$path"

    val url: String get() = treeUrl()

    fun prompt(ctx: Context): String {
        val v = BuildConfig.VERSION_NAME
        val files = KEY_FILES.joinToString("\n") { "- " + rawUrl(it, v) }
        return ctx.getString(R.string.audit_prompt, v, treeUrl(v), zipUrl(v), files)
    }

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

    fun open(ctx: Context) = view(ctx, treeUrl())

    /** "Download code archive": the tag zip, to upload into a chatbot that cannot browse. */
    fun downloadZip(ctx: Context) = view(ctx, zipUrl())

    private fun view(ctx: Context, url: String) {
        try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
    }
}
