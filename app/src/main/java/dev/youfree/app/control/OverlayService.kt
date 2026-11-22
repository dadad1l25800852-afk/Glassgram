package dev.youfree.app.control

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import dev.youfree.app.MainActivity
import kotlin.math.abs

/** A movable, dark YouTube-like affordance over the original signed application. */
class OverlayService : Service() {
    private var windowManager: WindowManager? = null
    private var bubble: TextView? = null
    private var params: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("youfree_control", "YouFree · кнопка",
                NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val pending = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        startForeground(111, Notification.Builder(this, "youfree_control")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("YouFree · управление")
            .setContentText("Нажми на плавающую кнопку внутри YouTube")
            .setContentIntent(pending).setOngoing(true).build())

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (bubble != null) return START_STICKY

        val density = resources.displayMetrics.density
        val width = (54 * density).toInt()
        val shape = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 25 * density
            setColor(Color.rgb(30, 30, 30))
            setStroke((1 * density).toInt().coerceAtLeast(1), Color.DKGRAY)
        }
        val view = TextView(this).apply {
            text = "▶"
            textSize = 22f
            setTextColor(Color.rgb(255, 0, 51))
            gravity = Gravity.CENTER
            background = shape
            elevation = 7 * density
            contentDescription = "Настройки YouFree"
        }
        val layout = WindowManager.LayoutParams(
            width, width,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (12 * density).toInt()
            y = (180 * density).toInt()
        }

        var firstX = 0f
        var firstY = 0f
        var initialX = 0
        var initialY = 0
        var dragged = false
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    firstX = event.rawX; firstY = event.rawY
                    initialX = layout.x; initialY = layout.y
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - firstX
                    val dy = event.rawY - firstY
                    if (abs(dx) > 8 * density || abs(dy) > 8 * density) dragged = true
                    if (dragged) {
                        layout.x = (initialX - dx).toInt().coerceAtLeast(0)
                        layout.y = (initialY + dy).toInt().coerceAtLeast(0)
                        runCatching { windowManager?.updateViewLayout(view, layout) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) {
                        val launch = Intent(this, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        startActivity(launch)
                    }
                    true
                }
                else -> false
            }
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        try {
            windowManager?.addView(view, layout)
            bubble = view
            params = layout
        } catch (e: Exception) {
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        bubble?.let { runCatching { windowManager?.removeView(it) } }
        bubble = null
        params = null
        super.onDestroy()
    }
}
