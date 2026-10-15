package pro.potoki.bekon.agent

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
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
import pro.potoki.bekon.agent.BuildConfig
import pro.potoki.bekon.agent.access.PrefsTrustedClients
import pro.potoki.bekon.agent.consent.Consent
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
        // targetSdk 35+: edge-to-edge is enforced; draw behind bars and pad content to safeDrawing.
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                val state by SessionService.state.collectAsState()
                Box(
                    Modifier.fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .safeDrawingPadding(),
                ) {
                    BekonHome(state = state, onIntent = ::dispatchIntent)
                }
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
            HomeIntent.AdvanceStep -> SessionService.instance?.advanceStep()
            HomeIntent.RetryStep -> SessionService.instance?.retryStep()
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
            Consent.openAccessibilitySettings(this)
            return
        }
        // The Bekon Keys keyboard is optional (typing uses accessibility SET_TEXT); never auto-prompt it.
        SessionService.instance?.reconcileSteps()
        bringUp = false
    }

    private fun openSettings(row: SettingsRow) {
        when (row) {
            SettingsRow.SCREEN -> startActivity(Intent(this, CapturePermissionActivity::class.java))
            SettingsRow.GESTURES -> Consent.openAccessibilitySettings(this)
            SettingsRow.KEYBOARD -> Consent.openKeyboardSettings(this)
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
            if (state.isRunning) {
                // Always-visible remote-session indicator with a one-tap Stop (Play policy).
                Card(
                    colors = androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = androidx.compose.ui.graphics.Color(0xFFE8A07A),
                    ),
                ) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Remote session active — the connected agent can see the screen and act on this phone.",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { onIntent(HomeIntent.Stop) }) { Text("Stop") }
                    }
                }
            }
            Card(Modifier.padding(vertical = 8.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusRow("Screen", pill(state.captureGranted), dimmed = state.paused) { onIntent(HomeIntent.OpenSettingsScreen(SettingsRow.SCREEN)) }
                    StatusRow("Gestures", pill(state.gesturesOn), dimmed = state.paused || state.readOnly) { onIntent(HomeIntent.OpenSettingsScreen(SettingsRow.GESTURES)) }
                    if (Consent.imeAvailable()) {
                        StatusRow("Keyboard (optional)", pill(state.keyboardOn), dimmed = state.paused || state.readOnly) { onIntent(HomeIntent.OpenSettingsScreen(SettingsRow.KEYBOARD)) }
                    }
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

            // Part B/C: frictionless onboarding checklist — one big CTA, progress N/M.
            SetupCard(state, onIntent)

            val ctx = androidx.compose.ui.platform.LocalContext.current
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                TextButton(onClick = { Consent.showDisclosure(ctx, Consent.Kind.A11Y) }) { Text("Accessibility disclosure") }
                TextButton(onClick = { Consent.openPrivacyPolicy(ctx) }) { Text("Privacy policy") }
            }
        }
    }

    // System Back closes the in-app overlays instead of leaving the app.
    androidx.activity.compose.BackHandler(enabled = showQr || showShortcuts) {
        if (showShortcuts) showShortcuts = false else showQr = false
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
    val prompt = remember(url) { pro.potoki.bekon.agent.handoff.Handoff.text(url) }
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(androidx.compose.foundation.rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Connect your AI agent",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Scan with the agent's camera, or copy the text below.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(20.dp))
            val bmp = remember(prompt) { qrBitmap(prompt) }
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = androidx.compose.ui.graphics.Color.White,
                tonalElevation = 2.dp,
                shadowElevation = 2.dp,
            ) {
                Image(bmp.asImageBitmap(), contentDescription = "QR", Modifier.padding(12.dp).size(256.dp))
            }
            Spacer(Modifier.height(20.dp))
            Text(
                "Share only with an agent you trust. You approve it on this phone and can stop any time.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    // break after "://" and before the first dot so the long host wraps predictably
                    url.replaceFirst("://", "://\u200B").replaceFirst(".", "\u200B."),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(24.dp))
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            Button(
                onClick = {
                    cm.setPrimaryClip(ClipData.newPlainText("bekon handoff", prompt))
                    Toast.makeText(context, "Agent prompt + link copied", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) { Text("Copy for agent") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    cm.setPrimaryClip(ClipData.newPlainText("bekon url", url))
                    Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) { Text("Copy URL only") }
            Spacer(Modifier.height(20.dp))
            val prefs = remember { PrefsTrustedClients(context) }
            var autoAccept by remember { mutableStateOf(prefs.autoAcceptNext()) }
            // Whole row toggles, so the checkbox carries its label for taps and accessibility.
            if (!BuildConfig.PLAY_STORE) Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().toggleable(
                    value = autoAccept,
                    role = androidx.compose.ui.semantics.Role.Checkbox,
                    onValueChange = {
                        autoAccept = it
                        prefs.setAutoAcceptNext(it)
                    },
                ),
            ) {
                Checkbox(checked = autoAccept, onCheckedChange = null)
                Text(
                    "Auto accept and remember next connection",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
            }
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
            SettingsMenuButton()
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

/**
 * Part B: frictionless onboarding checklist. Shows progress N/M, the current missing
 * capability, and ONE big CTA that deep-links into the right settings screen. On FAILED
 * it shows Retry instead.
 */
@Composable
private fun SetupCard(state: HomeState, onIntent: (HomeIntent) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    if (state.phase == SessionPhase.FAILED) {
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Session failed", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                state.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                Button(onClick = { onIntent(HomeIntent.Start) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Retry")
                }
            }
        }
        return
    }
    val step = state.currentStep
    if (step == SetupStep.DONE || step == SetupStep.COACH || step == SetupStep.STARTING) return
    // Idle: no service has checked anything yet, so the step would be a stale default. Start asks for what is missing.
    if (state.phase == SessionPhase.IDLE) return
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(state.stepProgress, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(step.label, style = MaterialTheme.typography.titleMedium)
            Text(step.body, style = MaterialTheme.typography.bodyMedium)
            Button(
                onClick = {
                    try {
                        val action = settingsActionFor(step)
                        if (step == SetupStep.GESTURES) { Consent.openAccessibilitySettings(context); return@Button }
                        if (step == SetupStep.KEYBOARD) { Consent.openKeyboardSettings(context); return@Button }
                        val i = if (action == "package:") {
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = android.net.Uri.parse("package:${context.packageName}")
                            }
                        } else if (action == "media_projection") {
                            Intent(context, CapturePermissionActivity::class.java)
                        } else {
                            Intent(action).apply {
                                // APP_NOTIFICATION_SETTINGS closes immediately without the package.
                                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                putExtra("app_package", context.packageName)
                                putExtra("app_uid", context.applicationInfo.uid)
                            }
                        }
                        context.startActivity(i)
                    } catch (e: Exception) {
                        try { context.startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (_: Exception) {}
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Open settings: ${step.label}")
            }
        }
    }
}

/** Map a SetupStep to a concrete settings deep-link intent action string. */
private fun settingsActionFor(step: SetupStep): String = when (step) {
    SetupStep.NOTIFICATION -> Settings.ACTION_APP_NOTIFICATION_SETTINGS
    SetupStep.BATTERY -> "package:"
    SetupStep.SCREEN -> "media_projection"
    SetupStep.GESTURES -> Settings.ACTION_ACCESSIBILITY_SETTINGS
    SetupStep.KEYBOARD -> Settings.ACTION_INPUT_METHOD_SETTINGS
    else -> Settings.ACTION_SETTINGS
}


/** Footer "Settings": a small menu with the policy, disclosures, Android app info and the version. */
@Composable
private fun SettingsMenuButton() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var open by remember { mutableStateOf(false) }
    Box {
        FooterButton(
            label = "Settings",
            icon = R.drawable.ic_settings,
            selected = open,
            enabled = true,
            onClick = { open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Privacy policy") }, onClick = { open = false; Consent.openPrivacyPolicy(ctx) })
            DropdownMenuItem(
                text = { Text("Accessibility disclosure") },
                onClick = { open = false; Consent.showDisclosure(ctx, Consent.Kind.A11Y, thenOpenSettings = false) },
            )
            if (Consent.imeAvailable()) DropdownMenuItem(
                text = { Text("Keyboard disclosure") },
                onClick = { open = false; Consent.showDisclosure(ctx, Consent.Kind.IME, thenOpenSettings = false) },
            )
            DropdownMenuItem(
                text = { Text("App info (Android settings)") },
                onClick = {
                    open = false
                    Consent.start(
                        ctx,
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${ctx.packageName}")),
                    )
                },
            )
            DropdownMenuItem(
                text = { Text("Version ${BuildConfig.VERSION_NAME} (${if (BuildConfig.PLAY_STORE) "Play" else "full"})") },
                onClick = { open = false },
                enabled = false,
            )
        }
    }
}
