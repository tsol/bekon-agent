package pro.potoki.bekon.agent.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import pro.potoki.bekon.agent.HomeIntent
import pro.potoki.bekon.agent.HomeState
import pro.potoki.bekon.agent.R
import pro.potoki.bekon.agent.SessionPhase
import pro.potoki.bekon.agent.handoff.Handoff
import pro.potoki.bekon.agent.session.CapturePermissionActivity
import pro.potoki.bekon.agent.setup.DeviceSetupGuide

/**
 * Onboarding wizard. Step 1 gets the human to an agent link as fast as possible (Start ->
 * screen consent + tunnel -> share card); the rest is a live checklist from DeviceSetupGuide
 * (single source of truth) that the human and the agent finish together.
 * [tick] changes on every Activity resume so statuses are re-read after visiting Settings.
 */
/** Core steps the wizard drives itself (no AI) until the agent can SEE and DRAW. */
enum class CoreStep { SESSION, NOTIFICATIONS, RESTRICTED, A11Y, READY }

/** Connection card states (one card on the home screen). */
enum class ConnState { NOT_READY, READY, DISCONNECTED, CONNECTED }

/**
 * Home screen body for the simplified build: ONE connection status card (state machine) and, while
 * not ready, ONE wizard step (short title + one sentence + one primary button). Statuses are re-read
 * on every resume ([tick]); the next step appears by itself.
 */
@Composable
fun SetupWizard(state: HomeState, agents: List<String>, tick: Int, onIntent: (HomeIntent) -> Unit, onShowQr: () -> Unit) {
    val ctx = LocalContext.current
    val guide = remember { DeviceSetupGuide(ctx, projectionActive = { state.captureGranted }) }
    val conn by pro.potoki.bekon.agent.access.AgentConnections.state.collectAsState()
    val notifOk = remember(tick) { androidx.core.app.NotificationManagerCompat.from(ctx).areNotificationsEnabled() }
    val a11yOk = state.gesturesOn || pro.potoki.bekon.agent.device.touch.TouchService.isBound()
    val restrictedNeeded = remember(tick, a11yOk) {
        !a11yOk && android.os.Build.VERSION.SDK_INT >= 33 && try { guide.status("restricted_settings") == false } catch (_: Exception) { false }
    }
    val sessionOk = state.isRunning && !state.publicUrl.isNullOrBlank() && state.captureGranted
    val core = buildList {
        add(CoreStep.SESSION); add(CoreStep.NOTIFICATIONS)
        if (restrictedNeeded) add(CoreStep.RESTRICTED)
        add(CoreStep.A11Y)
    }
    fun ok(c: CoreStep) = when (c) {
        CoreStep.SESSION -> sessionOk; CoreStep.NOTIFICATIONS -> notifOk
        CoreStep.RESTRICTED -> !restrictedNeeded; CoreStep.A11Y -> a11yOk; CoreStep.READY -> true
    }
    val current = core.firstOrNull { !ok(it) } ?: CoreStep.READY
    androidx.compose.runtime.LaunchedEffect(current) { pro.potoki.bekon.agent.status.AgentAlert.clearHint(ctx) }

    val cs = when {
        state.isRunning && conn.active != null && !conn.idle -> ConnState.CONNECTED
        current != CoreStep.READY -> ConnState.NOT_READY
        conn.everConnected -> ConnState.DISCONNECTED
        else -> ConnState.READY
    }
    ConnectionCard(cs, conn.active, current, core, ::ok, state, onIntent, onShowQr)
    if (cs == ConnState.NOT_READY) StepCard(current, state, onIntent)
}

@Composable
private fun ConnectionCard(
    cs: ConnState, agent: String?, current: CoreStep, core: List<CoreStep>, ok: (CoreStep) -> Boolean,
    state: HomeState, onIntent: (HomeIntent) -> Unit, onShowQr: () -> Unit,
) {
    val green = androidx.compose.ui.graphics.Color(0xFF1E8E3E)
    val container = when (cs) {
        ConnState.CONNECTED -> androidx.compose.ui.graphics.Color(0xFFE6F4EA)
        ConnState.READY -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Card(Modifier.fillMaxWidth(), shape = CARD_SHAPE, colors = CardDefaults.cardColors(containerColor = container)) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val title = when (cs) {
                ConnState.NOT_READY -> stringResource(R.string.conn_not_ready)
                ConnState.READY -> stringResource(R.string.conn_ready)
                ConnState.DISCONNECTED -> stringResource(R.string.conn_disconnected)
                ConnState.CONNECTED -> stringResource(R.string.conn_connected, agent ?: "")
            }
            Text(title, style = MaterialTheme.typography.titleLarge,
                color = if (cs == ConnState.CONNECTED) green else MaterialTheme.colorScheme.onSurface)
            when (cs) {
                ConnState.NOT_READY -> {
                    Text(stringResource(R.string.conn_missing, stringResource(coreTitle(current))),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    StepDots(core, ok)
                    // Any running session (e.g. tunnel + gestures on but capture lost) keeps a one-tap Stop.
                    if (state.sessionActive) StopButton({ onIntent(HomeIntent.Stop) }, Modifier.fillMaxWidth())
                }
                ConnState.READY, ConnState.DISCONNECTED -> {
                    if (!state.publicUrl.isNullOrBlank()) ShareButtons(state.publicUrl, onShowQr, { onIntent(HomeIntent.Stop) })
                    else if (state.sessionActive) StopButton({ onIntent(HomeIntent.Stop) }, Modifier.fillMaxWidth())
                }
                ConnState.CONNECTED -> {
                    // Play policy: always-visible remote-session indicator with one-tap Stop.
                    Text(stringResource(R.string.conn_connected_body), style = MaterialTheme.typography.bodyMedium)
                    // Same grid as the share row: equal-width buttons, Stop in red (always-visible Stop, Play policy).
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (state.canShowQr) OutlinedButton(onClick = onShowQr, modifier = Modifier.weight(1f).height(BTN_H), shape = BTN_SHAPE, contentPadding = TIGHT) {
                            BtnLabel(stringResource(R.string.show_qr_again))
                        }
                        StopButton({ onIntent(HomeIntent.Stop) }, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** Completed = green circle with a white check; pending = outlined circle; current = filled primary. */
@Composable
private fun StepDots(core: List<CoreStep>, ok: (CoreStep) -> Boolean) {
    val green = androidx.compose.ui.graphics.Color(0xFF1E8E3E)
    val ctx = LocalContext.current
    var currentSeen = false
    // Each dot sits in a 48dp tap target; tap explains the step and its state (also read by TalkBack).
    Row(verticalAlignment = Alignment.CenterVertically) {
        core.forEach { c ->
            val done = ok(c)
            val isCurrent = !done && !currentSeen
            if (isCurrent) currentSeen = true
            val label = stringResource(dotLabel(c, done))
            androidx.compose.foundation.layout.Box(
                Modifier.size(48.dp)
                    .semantics { contentDescription = label; role = androidx.compose.ui.semantics.Role.Button }
                    .clickable { Toast.makeText(ctx, label, Toast.LENGTH_SHORT).show() },
                contentAlignment = Alignment.Center,
            ) {
            androidx.compose.foundation.layout.Box(
                Modifier.size(24.dp).then(
                    when {
                        done -> Modifier.background(green, androidx.compose.foundation.shape.CircleShape)
                        isCurrent -> Modifier.background(MaterialTheme.colorScheme.primary, androidx.compose.foundation.shape.CircleShape)
                        else -> Modifier.border(2.dp, MaterialTheme.colorScheme.outline, androidx.compose.foundation.shape.CircleShape)
                    }
                ),
                contentAlignment = Alignment.Center,
            ) {
                if (done) Text("✓", color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.labelLarge)
            }
            }
        }
    }
}

private fun dotLabel(c: CoreStep, done: Boolean) = when (c) {
    CoreStep.SESSION -> if (done) R.string.dot_session_on else R.string.dot_session_off
    CoreStep.NOTIFICATIONS -> if (done) R.string.dot_notif_on else R.string.dot_notif_off
    CoreStep.RESTRICTED -> if (done) R.string.dot_restricted_on else R.string.dot_restricted_off
    CoreStep.A11Y, CoreStep.READY -> if (done) R.string.dot_a11y_on else R.string.dot_a11y_off
}

/** One step: short title, one sentence, one filled primary button with a verb. */
@Composable
private fun StepCard(current: CoreStep, state: HomeState, onIntent: (HomeIntent) -> Unit) {
    val ctx = LocalContext.current
    val section = stringResource(when {
        android.os.Build.MANUFACTURER.equals("samsung", true) -> R.string.core_a11y_section_samsung
        android.os.Build.MANUFACTURER.lowercase().let { it.contains("xiaomi") || it.contains("redmi") || it.contains("poco") } -> R.string.core_a11y_section_xiaomi
        else -> R.string.core_a11y_section_generic
    })
    val hintTitle = stringResource(R.string.core_hint_title)
    fun hint(text: String) = pro.potoki.bekon.agent.status.AgentAlert.hint(ctx, hintTitle, text)
    val hNotif = stringResource(R.string.core_hint_notifications)
    val hRestr = stringResource(R.string.core_hint_restricted)
    val hA11y = stringResource(R.string.core_hint_a11y, section)
    val guided = current == CoreStep.A11Y || current == CoreStep.RESTRICTED
    Card(Modifier.fillMaxWidth(), shape = CARD_SHAPE) {
        // Guided steps are compact so card + guide + button fit one screen above the bottom bar.
        Column(Modifier.padding(if (guided) 18.dp else 24.dp), verticalArrangement = Arrangement.spacedBy(if (guided) 10.dp else 16.dp)) {
            Text(stringResource(coreTitle(current)), style = MaterialTheme.typography.titleMedium)
            val captureLost = current == CoreStep.SESSION && state.isRunning && !state.captureGranted
            if (!guided) Text(if (captureLost) stringResource(R.string.capture_lost_body) else stringResource(coreSentence(current), section),
                style = MaterialTheme.typography.bodyLarge)
            val busy = current == CoreStep.SESSION && (state.phase == SessionPhase.STARTING || (state.isRunning && state.captureGranted))
            val verb = if (captureLost) R.string.capture_lost_verb else coreVerb(current, state)
            // Before opening Settings: illustrated, OEM-adapted path (coach overlays need Accessibility, so they can't help yet).
            if (current == CoreStep.A11Y || current == CoreStep.RESTRICTED) {
                val g = remember { DeviceSetupGuide(ctx, projectionActive = { state.captureGranted }) }
                val oem = remember { guideOem(g) }
                IllustratedGuide(if (current == CoreStep.RESTRICTED) restrictedSteps(oem) else a11ySteps(oem))
            }
            val btn = if (current == CoreStep.A11Y || current == CoreStep.RESTRICTED) R.string.g_open_settings else verb
            if (busy) Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp)); Spacer(Modifier.width(12.dp)); Text(stringResource(R.string.wiz_starting))
            } else Button(
                onClick = {
                    when (current) {
                        CoreStep.SESSION ->
                            if (state.isRunning && !state.captureGranted) ctx.startActivity(Intent(ctx, CapturePermissionActivity::class.java))
                            else onIntent(HomeIntent.Start)
                        CoreStep.NOTIFICATIONS -> {
                            hint(hNotif)
                            try {
                                ctx.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName))
                            } catch (_: Exception) {
                                ctx.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    android.net.Uri.parse("package:${ctx.packageName}")))
                            }
                        }
                        CoreStep.RESTRICTED -> {
                            hint(hRestr)
                            ctx.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                android.net.Uri.parse("package:${ctx.packageName}")))
                        }
                        CoreStep.A11Y -> { hint(hA11y); pro.potoki.bekon.agent.consent.Consent.openAccessibilitySettings(ctx) }
                        CoreStep.READY -> {}
                    }
                },
                modifier = Modifier.fillMaxWidth().height(BTN_H),
                shape = BTN_SHAPE,
            ) { Text(stringResource(btn), maxLines = 1) }
        }
    }
}

private fun coreTitle(c: CoreStep) = when (c) {
    CoreStep.SESSION -> R.string.core_step_session; CoreStep.NOTIFICATIONS -> R.string.core_step_notifications
    CoreStep.RESTRICTED -> R.string.core_step_restricted; CoreStep.A11Y -> R.string.core_step_a11y
    CoreStep.READY -> R.string.core_ready_title
}

private fun coreSentence(c: CoreStep) = when (c) {
    CoreStep.SESSION -> R.string.step1_session; CoreStep.NOTIFICATIONS -> R.string.step1_notifications
    CoreStep.RESTRICTED -> R.string.step1_restricted; CoreStep.A11Y -> R.string.step1_a11y
    CoreStep.READY -> R.string.core_ready_title
}

private fun coreVerb(c: CoreStep, s: HomeState) = when (c) {
    CoreStep.SESSION -> if (s.isRunning && !s.captureGranted) R.string.wiz_screen_share else R.string.verb_start
    CoreStep.NOTIFICATIONS, CoreStep.A11Y -> R.string.verb_enable
    CoreStep.RESTRICTED -> R.string.verb_open
    CoreStep.READY -> R.string.verb_open
}

@Composable
private fun ShareButtons(url: String, onShowQr: () -> Unit, onStop: (() -> Unit)?) {
    val ctx = LocalContext.current
    val msg = remember(url) { Handoff.text(url) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        run {
            Text(stringResource(R.string.wiz_share_body), style = MaterialTheme.typography.bodyLarge)
            val copied = stringResource(R.string.qr_copied_agent)
            Button(onClick = {
                (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("bekon handoff", msg))
                Toast.makeText(ctx, copied, Toast.LENGTH_SHORT).show()
            }, modifier = Modifier.fillMaxWidth().height(BTN_H), shape = BTN_SHAPE) { Text(stringResource(R.string.wiz_copy), maxLines = 1) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, msg)
                    ctx.startActivity(Intent.createChooser(send, null))
                }, modifier = Modifier.weight(1f).height(BTN_H), shape = BTN_SHAPE, contentPadding = TIGHT) { BtnLabel(stringResource(R.string.wiz_share)) }
                OutlinedButton(onClick = onShowQr, modifier = Modifier.weight(1f).height(BTN_H), shape = BTN_SHAPE, contentPadding = TIGHT) { BtnLabel(stringResource(R.string.wiz_show_qr)) }
                if (onStop != null) StopButton(onStop, Modifier.weight(1f))
            }
        }
    }
}


private val TIGHT = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 8.dp)
private val BTN_H = 52.dp
private val BTN_SHAPE = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
private val CARD_SHAPE = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)

/** Single-line label for the narrow 3-up button row: slightly smaller type so Russian labels never truncate. */
@Composable
private fun BtnLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
}

/** Outlined Stop in the error colour; same height/shape as the other card buttons. */
@Composable
private fun StopButton(onStop: () -> Unit, modifier: Modifier) {
    val err = MaterialTheme.colorScheme.error
    OutlinedButton(
        onClick = onStop,
        modifier = modifier.height(BTN_H),
        shape = BTN_SHAPE,
        contentPadding = TIGHT,
        border = androidx.compose.foundation.BorderStroke(1.dp, err),
        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = err),
    ) { BtnLabel(stringResource(R.string.home_stop)) }
}
