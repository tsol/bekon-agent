package pro.potoki.bekon.agent.session

import pro.potoki.bekon.agent.status.AgentStatus.State as StatusState
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
import pro.potoki.bekon.agent.R
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
        /** Notification swiped away (Android 14+ allows dismissing FGS notifications): re-post it. */
        const val ACTION_REPOST = "pro.potoki.bekon.agent.action.REPOST_NOTIFICATION"

        @Volatile var instance: SessionService? = null
            private set

        /** Last public URL in this process (kept across Stop/Start) to tell the owner when the quick-tunnel link changed. */
        @Volatile private var lastUrl: String? = null

        private val _state = MutableStateFlow(HomeState())
        val state: StateFlow<HomeState> = _state.asStateFlow()

        fun start(context: Context) {
            val i = Intent(context, SessionService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }
        /** Stop right now in-process (no startService from the background); falls back to the intent. */
        fun stopNow(context: Context) {
            val svc = instance
            if (svc != null) svc.main.post { svc.stopSession() } else stop(context)
        }

        fun stop(context: Context) {
            val i = Intent(context, SessionService::class.java).setAction(ACTION_STOP)
            context.startService(i)
        }

        /**
         * Owner action "Disconnect agent": void the agent's session token and drop the agent, its status and hints.
         * Screen sharing (MediaProjection), the local server and the tunnel (so the link/URL) stay up: a new agent,
         * or the same one, connects again with a new Allow.
         */
        fun revokeAgents() {
            try { pro.potoki.bekon.agent.status.UserEvents.stopCoaching?.invoke() } catch (_: Exception) {}
            try { pro.potoki.bekon.agent.coach.CoachOverlay.clear() } catch (_: Throwable) {}
            try { pro.potoki.bekon.agent.device.touch.GestureOverlay.clearCoach() } catch (_: Throwable) {}
            try { pro.potoki.bekon.agent.status.AgentStatus.clear() } catch (_: Throwable) {}
            instance?.gate?.reset()
            pro.potoki.bekon.agent.access.AgentConnections.clear()
            try { pro.potoki.bekon.agent.BekonApp.appContext?.let { pro.potoki.bekon.agent.status.AgentAlert.clear(it) } } catch (_: Throwable) {}
            Log.i(TAG, "agent disconnected by the owner (screen sharing and tunnel kept)")
        }

        fun setPaused(paused: Boolean) {
            if (!_state.value.isRunning) return
            _state.value = _state.value.copy(paused = paused)
            if (paused) try { pro.potoki.bekon.agent.status.UserEvents.stopCoaching?.invoke() } catch (_: Exception) {}
            instance?.updateNotification(statusText())
        }

        /** Blocks touch, keys, and navigation. Screen and clipboard read stay open. */
        fun setReadOnly(on: Boolean) {
            if (!_state.value.isRunning) return
            _state.value = _state.value.copy(readOnly = on)
            if (on) try { pro.potoki.bekon.agent.status.UserEvents.stopCoaching?.invoke() } catch (_: Exception) {}
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
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        gate = ClientGate(
            PrefsTrustedClients(applicationContext),
            paused = { _state.value.paused },
        ) { clientId, ip, replacing -> AccessPromptActivity.ask(applicationContext, clientId, ip, replacing) }
        createChannel()
        acquireWakeLock()
        stepEngine = StepEngine(stepDetectors())
        // No battery-optimization dialog on start: it is an optional step the human or agent opens on purpose.
        pro.potoki.bekon.agent.device.capture.ScreenCapture.onSystemStop = { onCaptureLost() }
        scope.launch {
            pro.potoki.bekon.agent.access.AgentConnections.state.collect {
                if (_state.value.isRunning) try { updateNotification(statusText()) } catch (_: Exception) {}
            }
        }
        scope.launch {
            pro.potoki.bekon.agent.status.AgentStatus.current.collect {
                if (_state.value.isRunning) try { updateNotification(statusText()) } catch (_: Exception) {}
            }
        }
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
            ACTION_REPOST -> {
                if (_state.value.isRunning || _state.value.phase == SessionPhase.STARTING) updateNotification(statusText())
                else stopSelf(startId)
            }
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
        pro.potoki.bekon.agent.status.UserEvents.newSession()
        _state.value = _state.value.copy(phase = SessionPhase.STARTING, error = null)
        updateNotification(statusText())
        val ex = DeviceExecutor(applicationContext)
        executor = ex
        scope.launch {
            try {
                val mcp = McpHandler(
                    ex, gate,
                    readOnly = { _state.value.readOnly },
                    liveCheck = {
                        val st = _state.value
                        when {
                            st.paused -> ClientGate.PAUSED
                            !st.isRunning -> "session_stopped: the owner stopped the Bekon session"
                            else -> null
                        }
                    },
                    shortcuts = pro.potoki.bekon.agent.shortcuts.ShortcutStore(applicationContext),
                    setup = pro.potoki.bekon.agent.setup.DeviceSetupGuide(applicationContext) { _state.value.captureGranted },
                )
                val srv = LocalServer(
                    DEFAULT_PORT, ex, mcp, { tunnel?.publicUrl != null }, gate,
                    readOnly = { _state.value.readOnly },
                    linkApps = object : pro.potoki.bekon.agent.server.LinkControl.Apps {
                        override fun list() = pro.potoki.bekon.agent.shortcuts.launchableApps(applicationContext)
                            .map { it.label to it.packageName }
                        override fun open(pkg: String) {
                            val i = packageManager.getLaunchIntentForPackage(pkg)
                                ?: throw pro.potoki.bekon.agent.core.CapabilityException("no launchable app $pkg")
                            startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                        override fun openUrl(url: String) {
                            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    },
                    linkCoach = object : pro.potoki.bekon.agent.server.LinkControl.Coach {
                        override fun draw(shapes: List<pro.potoki.bekon.agent.coach.Shape>, append: Boolean) =
                            ex.drawOnScreen(shapes, append, 0L)
                        override fun clear() { ex.clearScreen() }
                    },
                )
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
                    if (t != null && (!t.isAlive() || probeFailed)) {
                        Log.w(TAG, "tunnel down (alive=${t.isAlive()}, probeFailed=$probeFailed) — restarting, new URL")
                        probeFailed = false
                        t.stop()
                        t.start()
                    }
                    return tunnel?.publicUrl
                }
                override fun isHealthy(): Boolean {
                    return !probeFailed && srv.isRunning() && (tunnel?.isAlive() ?: false)
                }
            },
            onUrl = { url ->
                // Quick tunnels get a new random URL after a tunnel restart: links already given to an agent are dead.
                val prev = lastUrl
                lastUrl = url
                val changed = prev != null && prev != url
                if (changed) Log.w(TAG, "tunnel URL changed: the old link no longer works; the card shows the new one")
                pro.potoki.bekon.agent.handoff.Handoff.currentUrl = url
                _state.value = _state.value.copy(publicUrl = url, phase = SessionPhase.SERVING,
                    linkChanged = changed || _state.value.linkChanged)
                updateNotification(statusText())
            },
            onSessionDown = { reason ->
                // Tunnel died — keep capture alive, report and let the supervisor restart.
                Log.w(TAG, "session down: $reason")
                pro.potoki.bekon.agent.access.AgentConnections.lost(tunnel = true)
                _state.value = _state.value.copy(publicUrl = null, error = "tunnel down: $reason")
                updateNotification(statusText())
            },
        )
        supervisor = sup
        sup.start()
        startProbe()
    }

    @Volatile private var probeFailed = false
    private var probeThread: Thread? = null

    /**
     * cloudflared can stay alive while the edge has dropped the tunnel (agent sees HTTP 530). Probe our own
     * public /health every 30 s; two failures in a row = tunnel dead -> supervisor restarts it (new URL).
     */
    private fun startProbe() {
        probeThread?.interrupt()
        probeThread = Thread {
            var fails = 0
            while (true) {
                try { Thread.sleep(30_000) } catch (_: InterruptedException) { break }
                val u = tunnel?.publicUrl
                if (u == null) { fails = 0; continue }
                val ok = try {
                    val c = java.net.URL("$u/health").openConnection() as java.net.HttpURLConnection
                    c.connectTimeout = 15_000; c.readTimeout = 20_000
                    val code = c.responseCode; c.disconnect()
                    if (code !in 200..499) Log.w(TAG, "tunnel probe HTTP $code")
                    code in 200..499
                } catch (e: Exception) { Log.w(TAG, "tunnel probe: ${e.javaClass.simpleName}"); false }
                // A slow local action (link settle, web snapshot) must not look like a dead tunnel:
                // don't count failures while requests are in flight; restart only after 3 misses in a row
                // AND confirming the local server still answers (otherwise it's our problem, not the tunnel's).
                val busy = pro.potoki.bekon.agent.access.AgentConnections.inFlight.get() > 0
                fails = when { ok -> 0; busy -> fails; else -> fails + 1 }
                if (!ok) pro.potoki.bekon.agent.CrashLog.note("tunnel probe miss #$fails (busy=$busy)")
                if (fails >= 3) { probeFailed = true; fails = 0; pro.potoki.bekon.agent.CrashLog.note("tunnel probe: restarting tunnel") }
            }
        }.apply { isDaemon = true; name = "bekon-tunnel-probe"; start() }
    }

    private fun stopSession() {
        probeThread?.interrupt(); probeThread = null; probeFailed = false
        pro.potoki.bekon.agent.access.AgentConnections.resetAll()
        gate?.reset()
        Log.i(TAG, "stopSession")
        try { pro.potoki.bekon.agent.status.UserEvents.stopCoaching?.invoke() } catch (_: Exception) {}
        stopGuidanceExtras()
        try { executor?.releaseCapture() } catch (_: Exception) {}
        supervisor?.stop()
        supervisor = null
        try { tunnel?.stop() } catch (_: Exception) {}
        tunnel = null
        try { server?.stop() } catch (_: Exception) {}
        server = null
        pro.potoki.bekon.agent.status.AgentStatus.clear()
        _state.value = HomeState()
        stopSelf()
    }

    private fun refreshState(ex: DeviceExecutor) {
        _state.value = _state.value.copy(
            active = true,
            captureGranted = ex.health()["capture"] ?: false,
            gesturesOn = if (pro.potoki.bekon.agent.BuildConfig.GUIDANCE_MODE)
                pro.potoki.bekon.agent.overlay.OverlayHost.canDraw(this)
            else TouchService.isBound(),
            keyboardOn = if (pro.potoki.bekon.agent.BuildConfig.GUIDANCE_MODE) false
                else BekonImeService.isEnabled(contentResolver),
            keyboardSelected = if (pro.potoki.bekon.agent.BuildConfig.GUIDANCE_MODE) false
                else BekonImeService.isSelected(contentResolver),
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
                startGuidanceExtras(ex)
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
        val why = pro.potoki.bekon.agent.device.capture.ScreenCapture.lastLossReason ?: "unknown"
        Log.w(TAG, "capture lost: $why")
        _state.value = _state.value.copy(captureGranted = false, stepIndex = 0, error = "capture lost: $why") // re-derive: SCREEN is missing again
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
        // Play build: never a required step (no REQUEST_IGNORE_BATTERY_OPTIMIZATIONS); the optional
        // battery row in the setup guide only points to the system battery settings.
        SetupStep.BATTERY to { pro.potoki.bekon.agent.BuildConfig.GUIDANCE_MODE || isEmptyBatteryOptimizations() },
        SetupStep.SCREEN to { _state.value.captureGranted },
        SetupStep.GESTURES to {
            if (pro.potoki.bekon.agent.BuildConfig.GUIDANCE_MODE)
                pro.potoki.bekon.agent.overlay.OverlayHost.canDraw(this)
            else TouchService.isBound()
        },
        // Optional: typing works through accessibility SET_TEXT; never block onboarding on the IME.
        SetupStep.KEYBOARD to { true },
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
        val missing = engine.reconcile(0) // from the top: earlier steps can re-block, finished ones must clear
        _state.value = _state.value.copy(stepIndex = engine.stepIndex)
        if (missing != null) emitHint()
        updateNotification(statusText())
    }

    private fun emitHint() {
        val step = _state.value.currentStep
        val capture = _state.value.captureGranted
        val tunnelUp = tunnel?.isAlive() ?: false
        val gesturesOn = if (pro.potoki.bekon.agent.BuildConfig.GUIDANCE_MODE)
            pro.potoki.bekon.agent.overlay.OverlayHost.canDraw(this)
        else TouchService.isBound()
        // Coach only when capture (or tunnel) is up and something is still off.
        val callable = capture || tunnelUp
        if (!callable || step == SetupStep.DONE || step == SetupStep.COACH) return
        if (gesturesOn) {
            if (pro.potoki.bekon.agent.BuildConfig.GUIDANCE_MODE) {
                pro.potoki.bekon.agent.coach.CoachOverlay.bindAppContext(this)
                val dm = resources.displayMetrics
                val shapes = listOf(
                    pro.potoki.bekon.agent.coach.Shape(
                        pro.potoki.bekon.agent.coach.Shape.Kind.TEXT,
                        dm.widthPixels * 0.1f, dm.heightPixels * 0.15f,
                        dm.widthPixels * 0.1f, dm.heightPixels * 0.15f,
                        step.body.take(60),
                    ),
                )
                pro.potoki.bekon.agent.coach.CoachOverlay.show(null, shapes, false, 8_000)
            } else {
                // Path 1: overlay. We don't have a real bbox here without an a11y snapshot;
                // the deep-link CTA + label on the banner guides the user.
                pro.potoki.bekon.agent.device.touch.GestureOverlay.showCoach(null, step.body)
            }
        }
        // Path 2/3 handled on the wire: the MCP/HTTP client reads /coach/hint.
        Log.i(TAG, "coach hint for ${step.name}: ${step.body}")
    }


    private fun startGuidanceExtras(ex: DeviceExecutor) {
        if (!pro.potoki.bekon.agent.BuildConfig.GUIDANCE_MODE) return
        try {
            pro.potoki.bekon.agent.coach.CoachOverlay.bindAppContext(this)
            if (pro.potoki.bekon.agent.overlay.OverlayHost.canDraw(this)) {
                pro.potoki.bekon.agent.device.touch.StatusPill.attach(this)
                pro.potoki.bekon.agent.device.touch.StatusPill.refresh()
            }
            pro.potoki.bekon.agent.ocr.GuidanceSampler.start(
                scope = scope,
                latestBitmap = { ex.latestCaptureBitmap() },
                screenSize = { ex.screenSizeNow() },
            )
        } catch (e: Exception) {
            Log.w(TAG, "startGuidanceExtras: ${e.message}")
        }
    }

    private fun stopGuidanceExtras() {
        try { pro.potoki.bekon.agent.ocr.GuidanceSampler.stop() } catch (_: Exception) {}
        try { pro.potoki.bekon.agent.coach.CoachOverlay.clear() } catch (_: Exception) {}
        try { pro.potoki.bekon.agent.device.touch.StatusPill.detach() } catch (_: Exception) {}
        try { pro.potoki.bekon.agent.nav.NavJournal.clear() } catch (_: Exception) {}
        try { pro.potoki.bekon.agent.ocr.ScreenHistory.clear() } catch (_: Exception) {}
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
        stopGuidanceExtras()
        try { executor?.releaseCapture() } catch (_: Exception) {}
        scope.cancel()
        pro.potoki.bekon.agent.status.AgentStatus.clear()
        _state.value = HomeState()
        releaseWakeLock()
        if (instance === this) instance = null
        super.onDestroy()
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
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, SessionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val deletePi = PendingIntent.getService(
            this, 2,
            Intent(this, SessionService::class.java).setAction(ACTION_REPOST),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // agent_status: shown here when it is "done" or when the a11y overlay pill is unavailable.
        val st = pro.potoki.bekon.agent.status.AgentStatus.current.value
        // Latest agent text is always in the collapsed content so the human sees what the agent wants.
        val inNotif = st != null
        val conn = pro.potoki.bekon.agent.access.AgentConnections.state.value
        val title = when {
            st?.state == StatusState.DONE -> getString(R.string.notif_agent_finished)
            conn.active != null && !conn.idle -> getString(R.string.notif_session_title)
            // Until an agent actually connects this is readiness, not an active remote session.
            else -> getString(R.string.notif_ready_title)
        }
        val body = if (inNotif && st != null) {
            val lab = getString(when (st.state) {
                StatusState.WORKING -> R.string.status_working; StatusState.WAITING -> R.string.status_waiting
                StatusState.DONE -> R.string.status_done; StatusState.ERROR -> R.string.status_error
            })
            if (st.res != 0) getString(st.res) else if (st.state == StatusState.DONE) st.text else "$lab: ${st.text}"
        } else text
        val regrant = if (_state.value.isRunning && !_state.value.captureGranted) PendingIntent.getActivity(
            this, 3, Intent(this, CapturePermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        ) else null
        val caps = if (_state.value.isRunning) pro.potoki.bekon.agent.status.Capabilities.line(this, step = st?.step, total = st?.total) else null
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSubText(caps)
            .setContentTitle(title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(listOfNotNull(body, caps, text.takeIf { it != body }).joinToString("\n")))
            .apply { if (regrant != null) addAction(android.R.drawable.ic_menu_view, getString(R.string.capture_lost_verb), regrant) }
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.notif_stop_disconnect), stopPi)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentText(body)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pi)
            .setDeleteIntent(deletePi)
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
