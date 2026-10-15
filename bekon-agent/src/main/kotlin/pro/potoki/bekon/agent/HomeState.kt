package pro.potoki.bekon.agent

/** Phase 1 tunnel choices. Only Cloudflare exists; WLYA would be a new value. */
enum class TunnelKind { CLOUDFLARE }

/** Lifecycle of the phone session. */
enum class SessionPhase { IDLE, STARTING, SERVING, FAILED, STOPPING }

/**
 * Ordered onboarding steps (Part B checklist / Part C coach mode).
 * The service drives these; each has a deep-link + a short human text.
 * INVARIANTS from ARCHITECTURE.md: Activity = UI + intents only; the service owns
 * the session; errors are STATE, never a crash.
 */
enum class SetupStep(
    val label: String,
    val deepLink: String,
    val body: String,
) {
    NOTIFICATION(
        "Notification permission",
        "android.settings.APP_NOTIFICATION_SETTINGS",
        "Bekon needs notification permission to keep the session running.",
    ),
    BATTERY(
        "Battery unrestricted",
        "android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
        "Let Bekon run in the background. On Samsung/Xiaomi also open App info → Battery.",
    ),
    SCREEN(
        "Screen capture",
        "android.settings.ACTION_MANAGE_OVERLAY_PERMISSION",
        "Grant full screen capture. Pick \"Entire screen\". Needed for screenshots.",
    ),
    GESTURES(
        "Gestures (Accessibility)",
        "android.settings.ACCESSIBILITY_SETTINGS",
        "Enable Bekon, then \"Restricted settings\" → App info → ⋮ → Allow restricted settings.",
    ),
    KEYBOARD(
        "Bekon keyboard (optional)",
        "android.settings.INPUT_METHOD_SETTINGS",
        "Optional fallback keyboard for apps that reject normal text entry.",
    ),
    STARTING(
        "Start session",
        "",
        "Start the local server and tunnel.",
    ),
    COACH(
        "Coach",
        "",
        "Some capabilities are still off — follow the hints on screen.",
    ),
    DONE(
        "All set",
        "",
        "Everything is ready.",
    ),
}

/**
 * Single source of UI truth. The Activity renders this; the SessionService owns and
 * updates it via a StateFlow. UI only ever sends intents.
 */
data class HomeState(
    val phase: SessionPhase = SessionPhase.IDLE,
    val captureGranted: Boolean = false,
    val gesturesOn: Boolean = false,
    val keyboardOn: Boolean = false,
    val keyboardSelected: Boolean = false,
    val tunnelKind: TunnelKind = TunnelKind.CLOUDFLARE,
    val publicUrl: String? = null,
    val active: Boolean = false,
    val paused: Boolean = false,
    val readOnly: Boolean = false,
    val error: String? = null,
    val stepIndex: Int = 0,
) {
    val canStart: Boolean get() = phase == SessionPhase.IDLE
    val isRunning: Boolean get() = phase == SessionPhase.SERVING
    val canShowQr: Boolean get() = !publicUrl.isNullOrBlank()
    val currentStep: SetupStep get() = SetupStep.entries.getOrElse(stepIndex) { SetupStep.DONE }
    val stepProgress: String get() = "${stepIndex.coerceIn(0, SetupStep.entries.size)}/${SetupStep.entries.size}"
}

/** Intents the UI sends to the session. */
sealed interface HomeIntent {
    data object Start : HomeIntent
    data object Stop : HomeIntent
    data object Pause : HomeIntent
    data object Resume : HomeIntent
    data class SetReadOnly(val on: Boolean) : HomeIntent
    data class OpenSettingsScreen(val row: SettingsRow) : HomeIntent
    data object ShowQr : HomeIntent
    data class SetTunnelKind(val kind: TunnelKind) : HomeIntent
    /** Part B: advance the onboarding checklist one step (the one big button). */
    data object AdvanceStep : HomeIntent
    /** Part C: retry a coach hint / missing capability. */
    data object RetryStep : HomeIntent
}

enum class SettingsRow { SCREEN, GESTURES, KEYBOARD }
