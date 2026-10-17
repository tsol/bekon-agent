package pro.potoki.bekon.agent

import android.app.Application
import android.os.Build
import android.os.StrictMode
import android.util.Log

class BekonApp : Application() {
    companion object {
        private const val TAG = "BekonApp"
        const val PREFS = "bekon_prefs"
        @Volatile var appContext: android.content.Context? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        val def = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            Log.e(TAG, "UNCAUGHT on ${t.name}", e)
            CrashLog.note("UNCAUGHT on thread ${t.name}: $e", e)
            // A background-thread exception must not take the whole service + tunnel down.
            if (t !== android.os.Looper.getMainLooper().thread) {
                Log.e(TAG, "background thread ${t.name} died; process kept alive")
                return@setDefaultUncaughtExceptionHandler
            }
            def?.uncaughtException(t, e)
        }
        Thread { CrashLog.recordExitReasons(applicationContext) }.start()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder().detectLeakedSqlLiteObjects().penaltyLog().build()
            )
        }
    }
}
