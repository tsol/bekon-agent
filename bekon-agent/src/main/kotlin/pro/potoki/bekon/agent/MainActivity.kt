package pro.potoki.bekon.agent

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import pro.potoki.bekon.agent.R
import pro.potoki.bekon.agent.access.PrefsTrustedClients
import pro.potoki.bekon.agent.device.ime.BekonImeService
import pro.potoki.bekon.agent.device.touch.TouchService
import pro.potoki.bekon.agent.qr.qrBitmap
import pro.potoki.bekon.agent.session.CapturePermissionActivity
import pro.potoki.bekon.agent.shortcuts.ShortcutsScreen
import pro.potoki.bekon.agent.session.SessionService

class MainActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var bringUp = false
    private var askedCapture = false
    private var askedGestures = false
    private var askedKeyboard = false
    private var serviceWaits = 0

    private val askNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { startAndBringUp() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val state by SessionService.state.collectAsState()
                BekonHome(state = state, onIntent = ::dispatchIntent)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (bringUp) advanceBringUp()
    }

    private fun dispatchIntent(i: HomeIntent) {
        when (i) {
            HomeIntent.Start -> beginBringUp()
            HomeIntent.Stop -> {
                bringUp = false
                handler.removeCallbacksAndMessages(null)
                SessionService.stop(this)
            }
            HomeIntent.Pause -> SessionService.setPaused(true)
            HomeIntent.Resume -> SessionService.setPaused(false)
            is HomeIntent.SetReadOnly -> SessionService.setReadOnly(i.on)
            is HomeIntent.OpenSettingsScreen -> openSettings(i.row)
            HomeIntent.ShowQr -> { /* QR shown inline from state */ }
            is HomeIntent.SetTunnelKind -> { /* Cloudflare only in phase 1 */ }
        }
    }

    /** Start the session, then ask for whatever is still off. Each step is asked once. */
    private fun beginBringUp() {
        bringUp = true
        askedCapture = false
        askedGestures = false
        askedKeyboard = false
        serviceWaits = 0
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        startAndBringUp()
    }

    private fun startAndBringUp() {
        SessionService.start(this)
        advanceBringUp()
    }

    private fun advanceBringUp() {
        if (!bringUp || isFinishing) return
        if (SessionService.instance == null) {
            if (serviceWaits++ > 20) {
                bringUp = false
                return
            }
            handler.postDelayed({ advanceBringUp() }, 250)
            return
        }
        val s = SessionService.state.value
        if (!s.captureGranted && !askedCapture) {
            askedCapture = true
            startActivity(Intent(this, CapturePermissionActivity::class.java))
            return
        }
        if (!TouchService.isBound() && !askedGestures) {
            askedGestures = true
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        val keyboardReady = BekonImeService.isEnabled(contentResolver) &&
            BekonImeService.isSelected(contentResolver)
        if (!keyboardReady && !askedKeyboard) {
            askedKeyboard = true
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            return
        }
        bringUp = false
    }

    private fun openSettings(row: SettingsRow) {
        when (row) {
            SettingsRow.SCREEN -> startActivity(Intent(this, CapturePermissionActivity::class.java))
            SettingsRow.GESTURES -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            SettingsRow.KEYBOARD -> startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
    }
}

@Composable
fun BekonHome(state: HomeState, onIntent: (HomeIntent) -> Unit) {
    var showQr by remember { mutableStateOf(false) }
    var showShortcuts by remember { mutableStateOf(false) }
    Scaffold(
        bottomBar = { HomeFooter(state, onIntent) },
    ) { pad ->
        Column(
            Modifier.padding(pad).padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    painterResource(R.mipmap.ic_launcher),
                    contentDescription = "Bekon Agent",
                    Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)),
                )
                Spacer(Modifier.width(12.dp))
                Text("Bekon Agent", style = MaterialTheme.typography.headlineSmall)
            }
            Card(Modifier.padding(vertical = 8.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusRow("Screen", pill(state.captureGranted), dimmed = state.paused) { onIntent(HomeIntent.OpenSettingsScreen(SettingsRow.SCREEN)) }
                    StatusRow("Gestures", pill(state.gesturesOn), dimmed = state.paused || state.readOnly) { onIntent(HomeIntent.OpenSettingsScreen(SettingsRow.GESTURES)) }
                    StatusRow("Keyboard", pill(state.keyboardOn), dimmed = state.paused || state.readOnly) { onIntent(HomeIntent.OpenSettingsScreen(SettingsRow.KEYBOARD)) }
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Tunnel")
                        Spacer(Modifier.width(12.dp))
                        TunnelChoice(state.tunnelKind) { onIntent(HomeIntent.SetTunnelKind(it)) }
                        Spacer(Modifier.weight(1f))
                        Text(state.publicUrl?.let { "live" } ?: "off", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.width(8.dp))
                        Text("›", style = MaterialTheme.typography.titleLarge)
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.isRunning) {
                    Button(
                        onClick = { onIntent(HomeIntent.Stop) },
                        modifier = Modifier.weight(1f).height(52.dp),
                    ) { Text("Stop") }
                } else {
                    Button(
                        onClick = { onIntent(HomeIntent.Start) },
                        enabled = state.canStart,
                        modifier = Modifier.weight(1f).height(52.dp),
                    ) { Text("Start") }
                }
                OutlinedButton(
                    onClick = { showQr = true },
                    enabled = state.canShowQr,
                    modifier = Modifier.weight(1f).height(52.dp),
                ) { Text("QR for agent") }
            }

            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (showQr && state.canShowQr) {
        QrScreen(state.publicUrl!!, onDismiss = { showQr = false }, onShortcuts = { showShortcuts = true })
    }
    if (showShortcuts) {
        ShortcutsScreen(onBack = { showShortcuts = false })
    }
}

@Composable
private fun StatusRow(label: String, pillText: String, dimmed: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().alpha(if (dimmed) 0.38f else 1f).clickable(onClick = onClick).padding(vertical = 10.dp),
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
private fun QrScreen(url: String, onDismiss: () -> Unit, onShortcuts: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.padding(32.dp).fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            val prefs = remember { PrefsTrustedClients(context) }
            var autoAccept by remember { mutableStateOf(prefs.autoAcceptNext()) }
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
                Checkbox(
                    checked = autoAccept,
                    onCheckedChange = {
                        autoAccept = it
                        prefs.setAutoAcceptNext(it)
                    },
                    modifier = Modifier.offset(y = (-12).dp),
                )
                Text(
                    "Auto accept and remember next connection",
                    modifier = Modifier.weight(1f).padding(top = 2.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            val bmp = remember(url) { qrBitmap(url) }
            Image(bmp.asImageBitmap(), contentDescription = "QR", Modifier.height(280.dp))
            Spacer(Modifier.height(16.dp))
            Text(
                url,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("bekon url", url))
                Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
            }) { Text("Copy link") }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text("Close") }
                Text("|", color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onShortcuts) { Text("Shortcuts") }
            }
        }
    }
}


@Composable
private fun HomeFooter(state: HomeState, onIntent: (HomeIntent) -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FooterButton(
                label = if (state.paused) "Resume" else "Pause",
                icon = if (state.paused) R.drawable.ic_play else R.drawable.ic_pause,
                selected = state.paused,
                enabled = state.isRunning,
                onClick = { onIntent(if (state.paused) HomeIntent.Resume else HomeIntent.Pause) },
            )
            FooterButton(
                label = "Read only",
                icon = R.drawable.ic_visibility,
                selected = state.readOnly,
                enabled = state.isRunning,
                onClick = { onIntent(HomeIntent.SetReadOnly(!state.readOnly)) },
            )
            FooterButton(
                label = "Settings",
                icon = R.drawable.ic_settings,
                selected = false,
                enabled = true,
                onClick = { /* later: the rest of the menu lives here */ },
            )
        }
    }
}

@Composable
private fun FooterButton(
    label: String,
    icon: Int,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val tint = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        selected -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        Modifier
            .width(96.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(icon), contentDescription = label, tint = tint)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected && enabled) MaterialTheme.colorScheme.primary else tint,
            maxLines = 1,
        )
    }
}

/** Short status text for a permission row. */
private fun pill(on: Boolean): String = if (on) "on" else "off"
