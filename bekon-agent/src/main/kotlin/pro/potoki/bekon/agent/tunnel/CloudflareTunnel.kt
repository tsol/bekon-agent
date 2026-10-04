package pro.potoki.bekon.agent.tunnel

import android.content.Context
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
        val proc = try {
            ProcessBuilder(
                bin,
                "tunnel",
                "--url", target,
                "--no-autoupdate",
                "--edge-ip-version", "4",
                "--protocol", "http2",
            )
                .redirectErrorStream(true)
                .start()
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
            val m = Regex("https://[a-z0-9-]+\\.trycloudflare\\.com").find(line)
            if (m != null) {
                url = m.value
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
