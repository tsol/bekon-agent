package pro.potoki.bekon.agent

/** Phase 1 tunnel choices. Only Cloudflare exists; WLYA would be a new value. */
enum class TunnelKind { CLOUDFLARE }

/** Lifecycle of the phone session. */
enum class SessionPhase { IDLE, STARTING, SERVING, STOPPING }

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
    val error: String? = null,
) {
    val canStart: Boolean get() = phase == SessionPhase.IDLE
    val isRunning: Boolean get() = phase == SessionPhase.SERVING
    val canShowQr: Boolean get() = !publicUrl.isNullOrBlank()
}

/** Intents the UI sends to the session. */
sealed interface HomeIntent {
    data object Start : HomeIntent
    data object Stop : HomeIntent
    data class OpenSettingsScreen(val row: SettingsRow) : HomeIntent
    data object ShowQr : HomeIntent
    data class SetTunnelKind(val kind: TunnelKind) : HomeIntent
}

enum class SettingsRow { SCREEN, GESTURES, KEYBOARD }
