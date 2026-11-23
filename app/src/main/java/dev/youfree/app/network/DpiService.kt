package dev.youfree.app.network

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import dev.youfree.app.MainActivity
import io.github.romanvht.byedpi.core.ByeDpiProxy

/**
 * Native ByeDPI lives in a separate app process so it can be safely restarted.
 * Upstream JNI only allows one successful launch per process.
 */
class DpiService : Service() {
    private var nativeEngine: ByeDpiProxy? = null
    private var nativeThread: Thread? = null
    private var started = false

    companion object {
        const val START = "dev.youfree.native.DPI_START"
        const val STOP = "dev.youfree.native.DPI_STOP"
        const val PORT = "port"
        const val STRATEGY = "strategy"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("youfree.dpi", "YouFree · ByeDPI",
                NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { stopSelf(); return START_NOT_STICKY }
        if (intent?.action != START || started) return START_NOT_STICKY
        started = true
        startForeground(300, notification())
        val port = intent.getIntExtra(PORT, 0)
        val strategy = intent.getStringExtra(STRATEGY).orEmpty()
        if (port !in 1025..65535) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Arguments are passed directly to the native C program (not a shell).
        val args = mutableListOf("ciadpi", "-i", "127.0.0.1", "-p", port.toString())
        val tokens = strategy.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.any { it in setOf("-i", "--ip", "-p", "--port", "-D", "--daemon", "-E", "--transparent") }) {
            stopSelf()
            return START_NOT_STICKY
        }
        args += tokens
        nativeThread = Thread({
            try {
                val engine = ByeDpiProxy()
                nativeEngine = engine
                engine.startProxy(args.toTypedArray())
            } catch (_: Throwable) { }
            finally { stopSelf() }
        }, "ByeDPI-native").apply { isDaemon = true; start() }
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, "youfree.dpi")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("YouFree · ByeDPI включён")
            .setContentText("Локальный обход DPI только внутри YouFree")
            .setContentIntent(open).setOngoing(true).build()
    }

    override fun onDestroy() {
        runCatching { nativeEngine?.stopProxy() }
        nativeEngine = null
        super.onDestroy()
        // On the isolated :native process, prevents non-restartable upstream
        // JNI static state from persisting across user requested restarts.
        Process.killProcess(Process.myPid())
    }
}
