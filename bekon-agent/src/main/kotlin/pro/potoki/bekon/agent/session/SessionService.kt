package pro.potoki.bekon.agent.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.PowerManager.WakeLock
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
import pro.potoki.bekon.agent.HomeState
import pro.potoki.bekon.agent.MainActivity
import pro.potoki.bekon.agent.SessionPhase
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
    private var wakeLock: WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
        acquireWakeLock()
        Log.i(TAG, "onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("Bekon Agent"))
        when (intent?.action) {
            ACTION_START -> startSession()
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    private fun startSession() {
        if (_state.value.isRunning) return
        _state.value = _state.value.copy(phase = SessionPhase.STARTING, error = null)
        val ex = DeviceExecutor(applicationContext)
        executor = ex
        scope.launch {
            try {
                val mcp = McpHandler(ex)
                val srv = LocalServer(DEFAULT_PORT, ex, mcp)
                srv.start()
                server = srv
                refreshState(ex)
                startTunnel()
            } catch (e: Exception) {
                Log.e(TAG, "start failed", e)
                _state.value = _state.value.copy(phase = SessionPhase.IDLE, error = e.message)
            }
        }
    }

    private fun startTunnel() {
        val t = TunnelFactory.cloudflare(applicationContext, "http://127.0.0.1:$DEFAULT_PORT")
        tunnel = t
        val url = t.start()
        if (url != null) {
            _state.value = _state.value.copy(publicUrl = url)
            updateNotification("Live · $url")
        } else {
            _state.value = _state.value.copy(error = "tunnel failed to start")
        }
    }

    private fun refreshState(ex: DeviceExecutor) {
        _state.value = _state.value.copy(
            phase = SessionPhase.SERVING,
            active = true,
            captureGranted = ex.health()["capture"] ?: false,
            gesturesOn = TouchService.isBound(),
            keyboardOn = BekonImeService.isEnabled(contentResolver),
        )
    }

    fun handleCaptureResult(data: Intent, resultCode: Int) {
        val ex = executor
        if (ex != null) {
            ex.onCapturePermission(data, resultCode)
            _state.value = _state.value.copy(captureGranted = true)
            updateNotification("Capture granted")
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        try { tunnel?.stop() } catch (e: Exception) { Log.w(TAG, "tunnel stop ${e.message}") }
        try { server?.stop() } catch (e: Exception) { Log.w(TAG, "server stop ${e.message}") }
        scope.cancel()
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
