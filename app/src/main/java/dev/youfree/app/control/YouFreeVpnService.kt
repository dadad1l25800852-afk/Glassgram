package dev.youfree.app.control

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import dev.youfree.app.MainActivity
import dev.youfree.app.happ.XrayEmbedded
import org.json.JSONObject

/**
 * Per-app VPN for Google's unmodified YouTube. Uses the current Xray TUN inbound,
 * not the HTTP proxy (which would not affect the external YouTube APK).
 * Networking is experimental until tested on a physical device.
 */
class YouFreeVpnService : VpnService() {
    private var tun: ParcelFileDescriptor? = null
    private var core: XrayEmbedded? = null

    companion object {
        const val ACTION_CONNECT = "dev.youfree.app.CONNECT"
        const val ACTION_STOP = "dev.youfree.app.STOP"
        const val EXTRA_OUTBOUND = "outbound"
        @Volatile var running = false
        @Volatile var lastError = ""
    }

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel("youfree_vpn", "YouFree · VPN",
            NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_CONNECT) return START_NOT_STICKY
        startForeground(110, notification("Запуск VPN для YouTube"))
        val outboundJson = intent.getStringExtra(EXTRA_OUTBOUND)
        if (outboundJson.isNullOrBlank()) {
            lastError = "Нет выбранного VPN-сервера"
            stopSelf()
            return START_NOT_STICKY
        }
        Thread({
            try {
                lastError = ""
                val connectivity = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val network = connectivity.activeNetwork ?: error("Нет активной сети")
                val interfaceName = connectivity.getLinkProperties(network)?.interfaceName
                    ?: error("Не найден физический сетевой интерфейс")
                val builder = Builder().setSession("YouFree · YouTube")
                    .setMtu(1500)
                    .addAddress("172.19.0.2", 30)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer("1.1.1.1")
                builder.addAllowedApplication("com.google.android.youtube")
                setUnderlyingNetworks(arrayOf(network))
                tun = builder.establish() ?: error("Android не создал VPN-интерфейс")
                // Go Xray uses a duplicate of this FD; we hold the original.
                Os.setenv("XRAY_TUN_FD", tun!!.fd.toString(), true)
                val outbound = JSONObject(outboundJson).put("tag", "selected")
                core = XrayEmbedded()
                core!!.startTun(XrayEmbedded.Node("Выбранный сервер", outbound), interfaceName)
                running = true
            } catch (e: Exception) {
                lastError = "VPN не запущен: " + (e.message ?: e.javaClass.simpleName)
                running = false
                stopSelf()
            }
        }, "youfree-vpn-start").start()
        return START_NOT_STICKY
    }

    private fun notification(label: String): Notification {
        val pending = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, "youfree_vpn")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("YouFree")
            .setContentText(label)
            .setContentIntent(pending)
            .setOngoing(true).build()
    }

    override fun onRevoke() {
        super.onRevoke()
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        runCatching { core?.stop() }
        core = null
        runCatching { tun?.close() }
        tun = null
        runCatching { Os.unsetenv("XRAY_TUN_FD") }
        super.onDestroy()
    }
}
