package pro.potoki.bekon.agent

import android.app.Application
import android.os.Build
import android.os.StrictMode
import android.util.Log

class BekonApp : Application() {
    companion object {
        private const val TAG = "BekonApp"
        const val PREFS = "bekon_prefs"
    }

    override fun onCreate() {
        super.onCreate()
        val def = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            Log.e(TAG, "UNCAUGHT on ${t.name}", e)
            try {
                applicationContext.openFileOutput("crash.log", MODE_PRIVATE).use { out ->
                    out.write("${System.currentTimeMillis()} ${e}\n".toByteArray())
                    e.stackTrace.forEach { it -> out.write("  at $it\n".toByteArray()) }
                }
            } catch (_: Exception) {}
            def?.uncaughtException(t, e)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder().detectLeakedSqlLiteObjects().penaltyLog().build()
            )
        }
    }
}
