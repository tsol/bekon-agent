package pro.potoki.bekon.agent.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.PowerManager.WakeLock
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import pro.potoki.bekon.agent.access.AccessPromptActivity
import pro.potoki.bekon.agent.access.ClientGate
import pro.potoki.bekon.agent.access.PrefsTrustedClients
import pro.potoki.bekon.agent.HomeState
import pro.potoki.bekon.agent.MainActivity
import pro.potoki.bekon.agent.SessionPhase
import pro.potoki.bekon.agent.SetupStep
import pro.potoki.bekon.agent.core.DeviceExecutor
import pro.potoki.bekon.agent.device.ime.BekonImeService
import pro.potoki.bekon.agent.device.touch.TouchService
import pro.potoki.bekon.agent.mcp.McpHandler
import pro.potoki.bekon.agent.server.LocalServer
import pro.potoki.bekon.agent.tunnel.Tunnel

/**
 * Foreground service that owns the whole session: capture, accessibility, keyboard,
 * the local HTTP/MCP server, and cloudflared. If the Activity dies the session keeps
 * running; Stop is explicit. Emits HomeState via a single StateFlow.
 *
 * Resilience (HANDOFF-RESILIENCE-OVERNIGHT Part A):
 *  - startForeground() before any heavy work (Android O+ FGS rule).
 *  - onStartCommand returns START_STICKY so the system restarts us after a kill.
 *  - Every exception on start -> phase=FAILED + human-readable lastError + Retry, never a crash.
 *  - A SessionSupervisor watches the tunnel + local server and restarts them with backoff
 *    while the capture/gestures stay alive (capture is separate from the tunnel).
 *  - A StepEngine drives the frictionless onboarding checklist (Part B).
 *  - Battery optimization + OEM deep-links requested quietly.
 */
class SessionService : Service() {
    companion object {
        private const val TAG = "SessionService"
        private const val CHANNEL_ID = "bekon_agent_v1"
        private const val NOTIFICATION_ID = 1
        private const val DEFAULT_PORT = 8711

        const val ACTION_START = "pro.potoki.bekon.agent.action.START"
        const val ACTION_STOP = "pro.potoki.bekon.agent.action.STOP"

        @Volatile var instance: SessionService? = null
            private set

        private val _state = MutableStateFlow(HomeState())
        val state: StateFlow<HomeState> = _state.asStateFlow()

        fun start(context: Context) {
            val i = Intent(context, SessionService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }
        fun stop(context: Context) {
            val i = Intent(context, SessionService::class.java).setAction(ACTION_STOP)
            context.startService(i)
        }

        fun setPaused(paused: Boolean) {
            if (!_state.value.isRunning) return
            _state.value = _state.value.copy(paused = paused)
            instance?.updateNotification(statusText())
        }

        /** Blocks touch, keys, and navigation. Screen and clipboard read stay open. */
        fun setReadOnly(on: Boolean) {
            if (!_state.value.isRunning) return
            _state.value = _state.value.copy(readOnly = on)
            instance?.updateNotification(statusText())
        }

        fun statusText(): String {
            val s = _state.value
            val url = s.publicUrl
            return when {
                s.phase == SessionPhase.FAILED -> "Failed · ${s.error ?: "unknown"}"
                s.paused && url != null -> "Paused · $url"
                s.paused -> "Paused"
                s.readOnly && url != null -> "Read only · $url"
                s.readOnly -> "Read only"
                s.phase == SessionPhase.STARTING -> "Starting…"
                url != null && !s.captureGranted -> "Screen not shared · tap to share · $url"
                url != null -> "Live · $url"
                else -> "Bekon Agent"
            }
        }

        /** MediaProjection result returns here (same package). */
        fun onCapturePermissionResult(data: Intent, resultCode: Int) {
            instance?.handleCaptureResult(data, resultCode)
                ?: Log.w(TAG, "capture result but no service")
        }
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var executor: DeviceExecutor? = null
    private var server: LocalServer? = null
    private var tunnel: Tunnel? = null
    private var supervisor: SessionSupervisor? = null
    private var stepEngine: StepEngine? = null
    private var wakeLock: WakeLock? = null
    private var gate: ClientGate? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        gate = ClientGate(
            PrefsTrustedClients(applicationContext),
            paused = { _state.value.paused },
        ) { clientId, ip -> AccessPromptActivity.ask(applicationContext, clientId, ip) }
        createChannel()
        acquireWakeLock()
        stepEngine = StepEngine(stepDetectors())
        requestBatteryOptimizationQuietly()
        pro.potoki.bekon.agent.device.capture.ScreenCapture.onSystemStop = { onCaptureLost() }
        Log.i(TAG, "onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // FGS must be promoted before any heavy work on Android O+.
        // Never use the mediaProjection type here: on Android 14 it requires a fresh projection
        // grant, which a sticky restart / auto-start does not have. Projection type is added in
        // handleCaptureResult() once the user actually grants capture.
        if (!promoteForeground(withProjection = false)) {
            Log.w(TAG, "cannot enter foreground; stopping gracefully")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_START -> startSession()
            ACTION_STOP -> stopSession()
            else -> {
                // Re-delivery after a system kill (START_STICKY): resume if we were serving.
                if (_state.value.isRunning || _state.value.phase == SessionPhase.STARTING) {
                    startSession()
                }
            }
        }
        return START_STICKY
    }

    private fun startSession() {
        if (_state.value.phase == SessionPhase.SERVING) return
        _state.value = _state.value.copy(phase = SessionPhase.STARTING, error = null)
        updateNotification(statusText())
        val ex = DeviceExecutor(applicationContext)
        executor = ex
        scope.launch {
            try {
                val mcp = McpHandler(
                    ex, gate,
                    readOnly = { _state.value.readOnly },
                    shortcuts = pro.potoki.bekon.agent.shortcuts.ShortcutStore(applicationContext),
                    setup = pro.potoki.bekon.agent.setup.DeviceSetupGuide(applicationContext) { _state.value.captureGranted },
                )
                val srv = LocalServer(DEFAULT_PORT, ex, mcp, { tunnel?.publicUrl != null }, gate, readOnly = { _state.value.readOnly })
                srv.start()
                server = srv
                startSupervisor(srv)
                refreshState(ex)
            } catch (e: Exception) {
                Log.e(TAG, "start failed", e)
                fail("start failed: " + (e.message ?: e.javaClass.simpleName))
            }
        }
    }

    private fun startSupervisor(srv: LocalServer) {
        val t = TunnelFactory.cloudflare(applicationContext, "http://127.0.0.1:$DEFAULT_PORT")
        tunnel = t
        val sup = SessionSupervisor(
            scope = scope,
            control = object : TunnelControl {
                override fun ensureUp(): String? {
                    if (!srv.isRunning()) {
                        Log.w(TAG, "local server down — restarting")
                        srv.start()
                    }
                    val t = tunnel
                    if (t != null && !t.isAlive()) {
                        Log.w(TAG, "tunnel down — restarting")
                        t.stop()
                        t.start()
                    }
                    return tunnel?.publicUrl
                }
                override fun isHealthy(): Boolean {
                    return srv.isRunning() && (tunnel?.isAlive() ?: false)
                }
            },
            onUrl = { url ->
                _state.value = _state.value.copy(publicUrl = url, phase = SessionPhase.SERVING)
                updateNotification(statusText())
            },
            onSessionDown = { reason ->
                // Tunnel died — keep capture alive, report and let the supervisor restart.
                Log.w(TAG, "session down: $reason")
                _state.value = _state.value.copy(publicUrl = null, error = "tunnel down: $reason")
                updateNotification(statusText())
            },
        )
        supervisor = sup
        sup.start()
    }

    private fun stopSession() {
        Log.i(TAG, "stopSession")
        supervisor?.stop()
        supervisor = null
        try { tunnel?.stop() } catch (_: Exception) {}
        tunnel = null
        try { server?.stop() } catch (_: Exception) {}
        server = null
        _state.value = HomeState()
        stopSelf()
    }

    private fun refreshState(ex: DeviceExecutor) {
        _state.value = _state.value.copy(
            active = true,
            captureGranted = ex.health()["capture"] ?: false,
            gesturesOn = TouchService.isBound(),
            keyboardOn = BekonImeService.isEnabled(contentResolver),
            keyboardSelected = BekonImeService.isSelected(contentResolver),
        )
        reconcileSteps()
        updateNotification(statusText())
    }

    /** Returns false if the system refused the foreground promotion. */
    private fun promoteForeground(withProjection: Boolean): Boolean {
        val notification = buildNotification(statusText())
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                var type = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                if (withProjection) type = type or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                startForeground(NOTIFICATION_ID, notification, type)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // specialUse type doesn't exist pre-14; manifest types are filtered by the OS.
                val type = if (withProjection) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    else android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE
                startForeground(NOTIFICATION_ID, notification, type)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "startForeground(projection=$withProjection) refused", e)
            false
        } catch (e: IllegalStateException) {
            // e.g. ForegroundServiceStartNotAllowedException
            Log.e(TAG, "startForeground(projection=$withProjection) not allowed", e)
            false
        }
    }

    fun handleCaptureResult(data: Intent, resultCode: Int) {
        val ex = executor
        if (ex != null) {
            if (!promoteForeground(withProjection = true)) {
                _state.value = _state.value.copy(error = "capture: foreground projection refused")
                reconcileSteps()
                return
            }
            try {
                ex.onCapturePermission(data, resultCode)
                _state.value = _state.value.copy(captureGranted = true)
                updateNotification("Capture granted")
            } catch (e: Exception) {
                Log.e(TAG, "capture grant failed", e)
                _state.value = _state.value.copy(error = "capture: " + (e.message ?: ""))
            }
        }
        reconcileSteps()
    }

    /**
     * Android 14+ ends a MediaProjection when the phone locks (STOP_REASON_KEYGUARD) or the
     * user taps "Stop sharing". Each grant is single-use, so the human must share again.
     * Reflect that honestly instead of keeping "Live" with a dead screen.
     */
    private fun onCaptureLost() {
        if (!_state.value.captureGranted) return
        Log.w(TAG, "capture lost: projection stopped by the system")
        _state.value = _state.value.copy(captureGranted = false, stepIndex = 0) // re-derive: SCREEN is missing again
        if (_state.value.isRunning && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Drop the mediaProjection FGS type; keep the session (tunnel, gestures) alive.
            promoteForeground(withProjection = false)
        }
        reconcileSteps()
        updateNotification(statusText())
    }

    // ---- Part B/C: step engine + coach ----

    private fun stepDetectors(): Map<SetupStep, () -> Boolean> = mapOf(
        SetupStep.NOTIFICATION to {
            Build.VERSION.SDK_INT < 33 ||
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        },
        SetupStep.BATTERY to { isEmptyBatteryOptimizations() },
        SetupStep.SCREEN to { _state.value.captureGranted },
        SetupStep.GESTURES to { TouchService.isBound() },
        SetupStep.KEYBOARD to {
            BekonImeService.isEnabled(contentResolver) && BekonImeService.isSelected(contentResolver)
        },
        SetupStep.STARTING to { _state.value.isRunning },
        SetupStep.COACH to { true },
        SetupStep.DONE to { true },
    )

    private fun isEmptyBatteryOptimizations(): Boolean {
        if (Build.VERSION.SDK_INT < 23) return true
        return try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(packageName)
        } catch (e: Exception) {
            Log.w(TAG, "battery check: ${e.message}")
            true
        }
    }

    fun advanceStep() {
        val engine = stepEngine ?: return
        val st = engine.advance(_state.value.stepIndex)
        _state.value = _state.value.copy(stepIndex = st)
        emitHint()
        updateNotification(statusText())
    }

    fun retryStep() {
        reconcileSteps()
        emitHint()
    }

    /** Re-derive stepIndex from real capability flags (called after onResume from settings). */
    fun reconcileSteps() {
        val engine = stepEngine ?: return
        val missing = engine.reconcile(_state.value.stepIndex)
        _state.value = _state.value.copy(stepIndex = engine.stepIndex)
        if (missing != null) emitHint()
        updateNotification(statusText())
    }

    private fun emitHint() {
        val step = _state.value.currentStep
        val capture = _state.value.captureGranted
        val tunnelUp = tunnel?.isAlive() ?: false
        val gesturesOn = TouchService.isBound()
        // Coach only when capture (or tunnel) is up and something is still off.
        val callable = capture || tunnelUp
        if (!callable || step == SetupStep.DONE || step == SetupStep.COACH) return
        if (gesturesOn) {
            // Path 1: overlay. We don't have a real bbox here without an a11y snapshot;
            // the deep-link CTA + label on the banner guides the user.
            pro.potoki.bekon.agent.device.touch.GestureOverlay.showCoach(null, step.body)
        }
        // Path 2/3 handled on the wire: the MCP/HTTP client reads /coach/hint.
        Log.i(TAG, "coach hint for ${step.name}: ${step.body}")
    }

    private fun fail(msg: String) {
        _state.value = _state.value.copy(phase = SessionPhase.FAILED, error = msg, publicUrl = null)
        updateNotification(statusText())
    }

    // ---- lifecycle ----

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        supervisor?.stop()
        supervisor = null
        try { tunnel?.stop() } catch (e: Exception) { Log.w(TAG, "tunnel stop ${e.message}") }
        try { server?.stop() } catch (e: Exception) { Log.w(TAG, "server stop ${e.message}") }
        pro.potoki.bekon.agent.device.capture.ScreenCapture.onSystemStop = null
        scope.cancel()
        _state.value = HomeState()
        releaseWakeLock()
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun requestBatteryOptimizationQuietly() {
        if (Build.VERSION.SDK_INT < 23) return
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                // Best-effort: if it throws or the device blocks it, ignore (never crash).
                startActivity(intent)
            }
        } catch (e: Exception) {
            Log.w(TAG, "battery opt: ${e.message}")
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Bekon Agent", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Bekon Agent session is running"
                    setShowBadge(false)
                }
            )
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Bekon Agent")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Bekon::WakeLock").apply {
            setReferenceCounted(false)
            acquire(10 * 60 * 1000L)
        }
    }
    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release(); wakeLock = null }
    }
}
