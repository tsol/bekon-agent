package pro.potoki.bekon.agent.tunnel

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Cloudflare quick tunnel running on the phone. Starts the bundled arm64 cloudflared
 * binary aimed at the local HTTP server, parses the trycloudflare.com URL from stdout,
 * and tears the process down on stop. Quick tunnels mint a new URL every start.
 */
class CloudflareTunnel(private val context: Context, private val target: String) : Tunnel {
    companion object {
        private const val TAG = "CloudflareTunnel"
    }

    @Volatile private var process: Process? = null
    @Volatile private var url: String? = null

    override val publicUrl: String? get() = url

    override fun start(): String? {
        if (url != null) return url
        val bin = locateBinary() ?: return null
        val dir = prepareRuntime()
        val proc = try {
            ProcessBuilder(
                bin,
                "tunnel",
                "--url", target,
                "--no-autoupdate",
                "--edge-ip-version", "4",
                "--protocol", "http2",
            ).apply {
                redirectErrorStream(true)
                directory(dir)
                environment()["SSL_CERT_FILE"] = File(dir, "ca.crt").absolutePath
            }.start()
        } catch (e: Exception) {
            Log.e(TAG, "cloudflared start: ${e.message}")
            return null
        }
        process = proc
        // Read stdout looking for the trycloudflare.com URL (mints within ~5s).
        val reader = proc.inputStream.bufferedReader()
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            if (!proc.isAlive) {
                Log.e(TAG, "cloudflared exited early rc=${proc.exitValue()}")
                process = null
                return null
            }
            val line = reader.readLine() ?: break
            Log.i(TAG, "cloudflared: $line")
            val found = tunnelPublicUrl(line)
            if (found != null) {
                url = found
                // keep draining so the process doesn't block on a full pipe
                cancelDrain(reader)
                Log.i(TAG, "public url: $url")
                return url
            }
        }
        Log.e(TAG, "cloudflared: no URL from stdout in time")
        return null
    }

    private fun cancelDrain(reader: java.io.BufferedReader) {
        // move reading to a background thread after we have the URL
        Thread {
            try { while (reader.readLine() != null) { /* drain */ } } catch (_: Exception) {}
        }.start()
    }

    /**
     * cloudflared is a linux binary: it reads /etc/resolv.conf (missing on Android, so it
     * queries [::1]:53) and a system CA bundle. The bundled binary is patched to open
     * /proc/self/cwd/r instead, and we point SSL_CERT_FILE at a CA bundle in that directory.
     */
    private fun prepareRuntime(): File {
        val dir = context.cacheDir
        val servers = systemDns().ifEmpty { listOf("1.1.1.1", "8.8.8.8") }
        File(dir, "r").writeText(servers.joinToString("\n") { "nameserver $it" } + "\n")
        context.assets.open("ca-certificates.crt").use { input ->
            File(dir, "ca.crt").outputStream().use { input.copyTo(it) }
        }
        return dir
    }

    private fun systemDns(): List<String> {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val raw = cm.getLinkProperties(cm.activeNetwork)?.dnsServers ?: return emptyList()
        return raw.mapNotNull { addr ->
            val bare = addr.hostAddress?.substringBefore('%') ?: return@mapNotNull null
            if (bare.startsWith("fe80")) null else bare
        }
    }

    private fun locateBinary(): String? {
        // Per the Android W^X fix: bundle as native library (jniLibs/arm64-v8a/libcloudflared.so),
        // extractNativeLibs=true means it lands in nativeLibraryDir which IS executable.
        val nativePath = File(context.applicationInfo.nativeLibraryDir, "libcloudflared.so")
        if (nativePath.exists()) {
            nativePath.setExecutable(true)
            return nativePath.absolutePath
        }
        Log.e(TAG, "no libcloudflared.so in nativeLibraryDir")
        return null
    }

    override fun stop() {
        val p = process ?: return
        try { p.destroy() } catch (_: Exception) {}
        try { p.destroyForcibly() } catch (_: Exception) {}
        if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly()
        process = null
        url = null
    }
}

/** Quick-tunnel hostname. Skips https://api.trycloudflare.com, which cloudflared logs first. */
internal fun tunnelPublicUrl(text: String): String? {
    val re = Regex("https://([a-z0-9-]+)\\.trycloudflare\\.com")
    return re.findAll(text).map { it.value }.firstOrNull { !it.startsWith("https://api.") }
}
