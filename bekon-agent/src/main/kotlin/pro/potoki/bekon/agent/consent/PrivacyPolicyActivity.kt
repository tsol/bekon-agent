package pro.potoki.bekon.agent.consent

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/** Shows the privacy policy bundled in assets (works offline); the hosted copy is a secondary link. */
class PrivacyPolicyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                  androidx.compose.foundation.layout.Box(Modifier.safeDrawingPadding()) {
                    Column(Modifier.fillMaxSize()) {
                        AndroidView(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    settings.javaScriptEnabled = false
                                    settings.allowFileAccess = false // android_asset still loads
                                    webViewClient = object : WebViewClient() {
                                        // Any link inside the policy (mailto:, https:) leaves the app.
                                        override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                                            openExternal(r.url)
                                            return true
                                        }
                                    }
                                    loadUrl(PrivacyPolicy.ASSET_URL)
                                }
                            },
                        )
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            TextButton(onClick = { openExternal(Uri.parse(PrivacyPolicy.PUBLIC_URL)) }) { Text("Open web version") }
                            TextButton(onClick = { finish() }) { Text("Close") }
                        }
                    }
                }
            }
                }
        }
    }

    private fun openExternal(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: Exception) {
            Toast.makeText(this, "No app to open $uri", Toast.LENGTH_SHORT).show()
        }
    }
}
