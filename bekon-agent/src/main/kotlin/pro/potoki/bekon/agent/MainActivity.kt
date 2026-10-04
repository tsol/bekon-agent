package pro.potoki.bekon.agent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import pro.potoki.bekon.agent.qr.qrBitmap
import pro.potoki.bekon.agent.session.SessionService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val state by SessionService.state.collectAsState()
                BekonHome(state = state, onIntent = ::dispatchIntent)
            }
        }
    }

    private fun dispatchIntent(i: HomeIntent) {
        when (i) {
            HomeIntent.Start -> SessionService.start(this)
            HomeIntent.Stop -> SessionService.stop(this)
            is HomeIntent.OpenSettingsScreen -> openSettings(i.row)
            HomeIntent.ShowQr -> { /* QR shown inline from state */ }
            is HomeIntent.SetTunnelKind -> { /* Cloudflare only in phase 1 */ }
        }
    }

    private fun openSettings(row: SettingsRow) {
        when (row) {
            SettingsRow.SCREEN -> {
                startActivity(Intent(this, pro.potoki.bekon.agent.session.CapturePermissionActivity::class.java))
            }
            SettingsRow.GESTURES -> {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            SettingsRow.KEYBOARD -> {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            }
        }
    }
}

@Composable
fun BekonHome(state: HomeState, onIntent: (HomeIntent) -> Unit) {
    var showQr by remember { mutableStateOf(false) }
    Scaffold { pad ->
        Column(
            Modifier.padding(pad).padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusRow("Экран", pill(state.captureGranted)) { onIntent(HomeIntent.OpenSettingsScreen(SettingsRow.SCREEN)) }
                    StatusRow("Жесты", pill(state.gesturesOn)) { onIntent(HomeIntent.OpenSettingsScreen(SettingsRow.GESTURES)) }
                    StatusRow("Клавиатура", pill(state.keyboardOn)) { onIntent(HomeIntent.OpenSettingsScreen(SettingsRow.KEYBOARD)) }
                    // Туннель row with Cloudflare choice (drops to next line on narrow screens)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Туннель", Modifier.weight(1f))
                        Text(state.publicUrl?.let { "жив" } ?: "выкл", Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium)
                        TunnelChoice(state.tunnelKind) { onIntent(HomeIntent.SetTunnelKind(it)) }
                    }
                }
            }

            if (state.isRunning) {
                Button(
                    onClick = { onIntent(HomeIntent.Stop) },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text("Остановить") }
            } else {
                Button(
                    onClick = { onIntent(HomeIntent.Start) },
                    enabled = state.canStart,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text("Запустить") }
            }

            OutlinedButton(
                onClick = { showQr = true },
                enabled = state.canShowQr,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) { Text("QR для агента") }

            TextButton(onClick = { /* stub settings screen */ }, modifier = Modifier.fillMaxWidth()) {
                Text("Настройки")
            }

            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (showQr && state.canShowQr) {
        QrScreen(state.publicUrl!!, onDismiss = { showQr = false })
    }
}

@Composable
private fun StatusRow(label: String, pillText: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Text(pillText, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(8.dp))
        Text("›", style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun TunnelChoice(kind: TunnelKind, onSet: (TunnelKind) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.wrapContentWidth()) {
        Text("Cloudflare", Modifier.clickable { expanded = true }.padding(4.dp))
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Cloudflare") },
                onClick = { onSet(TunnelKind.CLOUDFLARE); expanded = false },
            )
        }
    }
}

@Composable
private fun QrScreen(url: String, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.padding(32.dp).fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            val bmp = remember(url) { qrBitmap(url) }
            Image(bmp.asImageBitmap(), contentDescription = "QR", Modifier.height(280.dp))
            Spacer(Modifier.height(16.dp))
            Text(url, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(16.dp))
            Button(onClick = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("bekon url", url))
                Toast.makeText(context, "Ссылка скопирована", Toast.LENGTH_SHORT).show()
            }) { Text("Скопировать ссылку") }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        }
    }
}

/** Short status text: "да" / "нет" with on/off prefixes. */
private fun pill(on: Boolean): String = if (on) "✓ да" else "— нет"
