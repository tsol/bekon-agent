package pro.potoki.bekon.agent.consent

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pro.potoki.bekon.agent.device.touch.TouchService

/**
 * In-app Prominent Disclosure (Google Play User Data / Accessibility API policy).
 * Separate from the privacy policy, shown before the user is sent to system settings,
 * with an affirmative Accept and a Decline that keeps the capability off.
 */
class DisclosureActivity : ComponentActivity() {
    companion object {
        const val EXTRA_KIND = "kind"
        const val EXTRA_OPEN = "open"

        val A11Y_TITLE = "Bekon uses the Accessibility service"
        val A11Y_POINTS = listOf(
            "What it does: with your permission Bekon's Accessibility service can READ what is shown on the screen (text, buttons, fields) and PERFORM ACTIONS for you: taps, swipes, typing text and Back/Home.",
            "Why: so a remote assistant (an AI agent or a person helping you) that YOU connect can see your screen and operate the phone to complete the task you asked for.",
            "Where the data goes: screen content and actions are exchanged only with the agent you explicitly connect by sharing this phone's tunnel link / QR code. Every new agent must be approved on this phone. Bekon does not sell this data or use it for ads.",
            "Your control: a bacon-coloured frame and a notification are shown while a remote session is active. Tap Stop in the app or the notification at any time. You can turn the service off in Settings › Accessibility.",
            "Bekon is not an accessibility tool for people with disabilities; it is a remote assistance and automation app.",
        )
        val IME_TITLE = "Optional: Bekon Keys keyboard"
        val IME_POINTS = listOf(
            "Bekon normally types through the Accessibility service and does NOT need a keyboard.",
            "Bekon Keys is only a fallback for apps that refuse normal text entry (older Android, terminals). When selected, it types text sent by the agent you connected into the focused field.",
            "It does not record what you type yourself and sends nothing off the phone. Switch back to your usual keyboard at any time.",
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val kind = runCatching { Consent.Kind.valueOf(intent.getStringExtra(EXTRA_KIND) ?: "") }
            .getOrDefault(Consent.Kind.A11Y)
        val open = intent.getBooleanExtra(EXTRA_OPEN, true)
        val title = if (kind == Consent.Kind.A11Y) A11Y_TITLE else IME_TITLE
        val points = if (kind == Consent.Kind.A11Y) A11Y_POINTS else IME_POINTS
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                  androidx.compose.foundation.layout.Box(Modifier.safeDrawingPadding()) {
                    Column(
                        Modifier.padding(24.dp).fillMaxSize().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Before you continue", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        points.forEach { Text("• $it", style = MaterialTheme.typography.bodyLarge) }
                        TextButton(onClick = { Consent.openPrivacyPolicy(this@DisclosureActivity) }) {
                            Text("Privacy policy")
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(onClick = { decline(kind) }, modifier = Modifier.weight(1f).height(52.dp)) {
                                Text("Decline")
                            }
                            Button(onClick = { accept(kind, open) }, modifier = Modifier.weight(1f).height(52.dp)) {
                                Text("Accept")
                            }
                        }
                    }
                }
            }
                }
        }
    }

    private fun accept(kind: Consent.Kind, open: Boolean) {
        Consent.set(this, kind, true)
        if (kind == Consent.Kind.A11Y) TouchService.instance?.onConsentChanged()
        if (open) {
            val action = if (kind == Consent.Kind.A11Y) Settings.ACTION_ACCESSIBILITY_SETTINGS else Settings.ACTION_INPUT_METHOD_SETTINGS
            Consent.start(this, Intent(action))
        }
        finish()
    }

    private fun decline(kind: Consent.Kind) {
        Consent.set(this, kind, false)
        if (kind == Consent.Kind.A11Y) TouchService.instance?.onConsentChanged()
        Toast.makeText(this, "Not enabled. You can review this again from the app at any time.", Toast.LENGTH_LONG).show()
        finish()
    }
}
