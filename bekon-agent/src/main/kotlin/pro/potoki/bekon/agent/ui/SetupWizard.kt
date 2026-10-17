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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

@Composable
fun SetupWizard(state: HomeState, agents: List<String>, tick: Int, onIntent: (HomeIntent) -> Unit, onShowQr: () -> Unit) {
    val ctx = LocalContext.current
    val guide = remember { DeviceSetupGuide(ctx, projectionActive = { state.captureGranted }) }
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
    // Step finished (auto-advance on resume): drop the Settings hint notification.
    androidx.compose.runtime.LaunchedEffect(current) { pro.potoki.bekon.agent.status.AgentAlert.clearHint(ctx) }
    val section = stringResource(when {
        android.os.Build.MANUFACTURER.equals("samsung", true) -> R.string.core_a11y_section_samsung
        android.os.Build.MANUFACTURER.lowercase().let { it.contains("xiaomi") || it.contains("redmi") || it.contains("poco") } -> R.string.core_a11y_section_xiaomi
        else -> R.string.core_a11y_section_generic
    })
    val hintTitle = stringResource(R.string.core_hint_title)
    fun hint(text: String) = pro.potoki.bekon.agent.status.AgentAlert.hint(ctx, hintTitle, text)

    if (current != CoreStep.READY) {
        val idx = core.indexOf(current) + 1
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.core_progress, idx, core.size), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary)
                // Checkmark strip of the core steps.
                core.forEach { c ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (ok(c)) "✓" else if (c == current) "➜" else "○",
                            color = if (ok(c)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(coreTitle(c)),
                            style = if (c == current) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium)
                    }
                }
                androidx.compose.material3.HorizontalDivider()
                Text(stringResource(coreIcon(current)), style = MaterialTheme.typography.displaySmall)
                when (current) {
                    CoreStep.SESSION -> {
                        Text(stringResource(R.string.core_session_body), style = MaterialTheme.typography.bodyLarge)
                        when {
                            state.phase == SessionPhase.IDLE || state.phase == SessionPhase.STOPPING || state.phase == SessionPhase.FAILED ->
                                Button(onClick = { onIntent(HomeIntent.Start) }, enabled = state.canStart || state.phase == SessionPhase.FAILED,
                                    modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(stringResource(R.string.home_start)) }
                            state.isRunning && !state.captureGranted ->
                                Button(onClick = { ctx.startActivity(Intent(ctx, CapturePermissionActivity::class.java)) },
                                    modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(stringResource(R.string.wiz_screen_share)) }
                            else -> Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.padding(end = 12.dp)); Text(stringResource(R.string.wiz_starting))
                            }
                        }
                    }
                    CoreStep.NOTIFICATIONS -> {
                        Text(stringResource(R.string.core_notifications_body), style = MaterialTheme.typography.bodyLarge)
                        val h = stringResource(R.string.core_hint_notifications)
                        Button(onClick = {
                            hint(h)
                            try {
                                ctx.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName))
                            } catch (_: Exception) {
                                ctx.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    android.net.Uri.parse("package:${ctx.packageName}")))
                            }
                        }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(stringResource(R.string.core_open_settings)) }
                    }
                    CoreStep.RESTRICTED -> {
                        Text(stringResource(R.string.core_restricted_body), style = MaterialTheme.typography.bodyLarge)
                        val h = stringResource(R.string.core_hint_restricted)
                        Button(onClick = {
                            hint(h)
                            ctx.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                android.net.Uri.parse("package:${ctx.packageName}")))
                        }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(stringResource(R.string.core_open_app_info)) }
                        val ha = stringResource(R.string.core_hint_a11y, section)
                        OutlinedButton(onClick = { hint(ha); pro.potoki.bekon.agent.consent.Consent.openAccessibilitySettings(ctx) },
                            modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.core_try_a11y)) }
                    }
                    CoreStep.A11Y -> {
                        Text(stringResource(R.string.core_a11y_body, section), style = MaterialTheme.typography.bodyLarge)
                        val ha = stringResource(R.string.core_hint_a11y, section)
                        Button(onClick = { hint(ha); pro.potoki.bekon.agent.consent.Consent.openAccessibilitySettings(ctx) },
                            modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(stringResource(R.string.core_open_settings)) }
                    }
                    CoreStep.READY -> {}
                }
                // Escape hatch: someone already chatting with an agent can hand over the link early.
                if (state.isRunning && !state.publicUrl.isNullOrBlank() && agents.isEmpty()) {
                    TextButton(onClick = onShowQr) { Text(stringResource(R.string.core_share_early)) }
                }
            }
        }
    } else if (agents.isEmpty()) {
        ShareCard(state.publicUrl!!, onShowQr, ready = true)
    }
    Checklist(tick, state)
}

private fun coreTitle(c: CoreStep) = when (c) {
    CoreStep.SESSION -> R.string.core_step_session; CoreStep.NOTIFICATIONS -> R.string.core_step_notifications
    CoreStep.RESTRICTED -> R.string.core_step_restricted; CoreStep.A11Y -> R.string.core_step_a11y
    CoreStep.READY -> R.string.core_ready_title
}

/** Big "illustration" glyph per step (vector art can replace these later). */
private fun coreIcon(c: CoreStep) = when (c) {
    CoreStep.SESSION -> R.string.core_icon_session; CoreStep.NOTIFICATIONS -> R.string.core_icon_notifications
    CoreStep.RESTRICTED -> R.string.core_icon_restricted; CoreStep.A11Y -> R.string.core_icon_a11y
    CoreStep.READY -> R.string.core_icon_session
}

@Composable
private fun WizardCard(title: String?, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            title?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
            content()
        }
    }
}

@Composable
private fun ShareCard(url: String, onShowQr: () -> Unit, ready: Boolean = false) {
    val ctx = LocalContext.current
    val msg = remember(url) { Handoff.text(url) }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (ready) Text("✓ " + stringResource(R.string.core_ready_title), style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.wiz_share_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.wiz_share_body), style = MaterialTheme.typography.bodyLarge)
            val copied = stringResource(R.string.qr_copied_agent)
            Button(onClick = {
                (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("bekon handoff", msg))
                Toast.makeText(ctx, copied, Toast.LENGTH_SHORT).show()
            }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(stringResource(R.string.wiz_copy)) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, msg)
                    ctx.startActivity(Intent.createChooser(send, null))
                }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.wiz_share)) }
                OutlinedButton(onClick = onShowQr, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.wiz_show_qr)) }
            }
        }
    }
}

@Composable
private fun Checklist(tick: Int, state: HomeState) {
    val ctx = LocalContext.current
    val guide = remember { DeviceSetupGuide(ctx, projectionActive = { state.captureGranted }) }
    val prefs = remember { ctx.getSharedPreferences("bekon_wizard", Context.MODE_PRIVATE) }
    var manual by remember { mutableStateOf(prefs.all.keys.toSet()) }
    // Screen capture is part of Step 1; the rest comes from the OEM guide.
    val steps = remember(tick, manual, state.gesturesOn) {
        // Core steps (session, notifications, restricted, accessibility) live in the wizard above.
        try { guide.uiSteps().filter { it.req !in setOf("projection", "accessibility", "restricted_settings", "notifications") } } catch (_: Exception) { emptyList() }
    }
    if (steps.isEmpty()) return
    fun done(s: DeviceSetupGuide.UiStep) = s.status ?: (s.id in manual)
    val n = steps.count(::done)
    val allDone = n == steps.size
    // Open while something is missing, collapsed once all done; the header toggles it.
    var userToggle by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val open = userToggle ?: !allDone
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (allDone) "✓ " + stringResource(R.string.wiz_all_done)
                    else stringResource(R.string.optional_title, n, steps.size),
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { userToggle = !open }) {
                    Text(stringResource(if (open) R.string.wiz_hide else R.string.wiz_show))
                }
            }
            if (!open) return@Column
            if (!allDone) Text(stringResource(R.string.wiz_checklist_body), style = MaterialTheme.typography.bodySmall)
            steps.forEach { s ->
                val ok = done(s)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (ok) "✓" else "○", style = MaterialTheme.typography.titleMedium,
                        color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.title, style = MaterialTheme.typography.bodyLarge)
                        if (!ok) Text(stringResource(explain(s.req)), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (!ok) {
                        if (s.canOpen) TextButton(onClick = {
                            try { guide.open(s.id) } catch (e: Exception) {
                                Toast.makeText(ctx, s.path, Toast.LENGTH_LONG).show()
                            }
                        }) { Text(stringResource(R.string.wiz_open)) }
                        // Not detectable (OEM toggles): the human confirms it.
                        if (s.status == null) TextButton(onClick = {
                            prefs.edit().putBoolean(s.id, true).apply(); manual = manual + s.id
                        }) { Text(stringResource(R.string.wiz_mark_done)) }
                    }
                }
            }
        }
    }
}

private fun explain(req: String): Int = when (req) {
    "accessibility" -> R.string.wiz_explain_accessibility
    "restricted_settings" -> R.string.wiz_explain_restricted_settings
    "notifications" -> R.string.wiz_explain_notifications
    "battery" -> R.string.wiz_explain_battery
    else -> R.string.wiz_explain_other
}
