package com.sepand.ai.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** User-started, always-visible quick controls for accessibility gestures in other apps. */
class SepandOverlayService : Service() {
    private var windowManager: WindowManager? = null
    private var panel: LinearLayout? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        startAsForeground()
        showPanel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopSelf()
        return START_STICKY
    }

    private fun startAsForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Sepand phone controls", NotificationManager.IMPORTANCE_LOW))
        }
        val stopIntent = PendingIntent.getService(
            this, 10, Intent(this, SepandOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        val notification = builder
            .setSmallIcon(com.sepand.ai.R.drawable.ic_sepand)
            .setContentTitle("Sepand AI controls are active")
            .setContentText("Floating scroll, back and home controls are available")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, com.sepand.ai.R.drawable.ic_sepand), "Stop controls", stopIntent).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else startForeground(NOTIFICATION_ID, notification)
    }

    private fun showPanel() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(9, 9, 9, 9)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 28f
                setColor(0xEE241019.toInt())
                setStroke(2, 0xAAFF829D.toInt())
            }
        }
        val header = TextView(this).apply {
            text = "SEPAND  ·  PHONE CONTROL"
            textSize = 10f
            setTextColor(0xFFFFB7C6.toInt())
            setPadding(10, 8, 10, 8)
        }
        root.addView(header)
        addControl(root, "↑  Scroll up") { runControl("scroll_up") }
        addControl(root, "↓  Scroll down") { runControl("scroll_down") }
        addControl(root, "↶  Back") { runControl("back") }
        addControl(root, "⌂  Home") { runControl("home") }
        addControl(root, "Hide bubble") { stopSelf() }

        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; x = 18; y = 180 }
        panel = root
        runCatching { windowManager?.addView(root, params) }.onFailure {
            Toast.makeText(this, "Could not show Sepand controls: ${it.message}", Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }

    private fun addControl(parent: LinearLayout, label: String, action: () -> Unit) {
        val button = Button(this).apply {
            text = label
            textSize = 11f
            setTextColor(0xFFFFF0F3.toInt())
            setBackgroundColor(0xFF642238.toInt())
            setOnClickListener { action() }
        }
        parent.addView(button, LinearLayout.LayoutParams(220, 44).apply { bottomMargin = 5 })
    }

    private fun runControl(action: String) {
        val service = SepandAccessibilityService.instance
        val result = service?.execute(action) ?: "Enable Sepand Accessibility in Android settings first"
        Toast.makeText(this, result, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        runCatching { panel?.let { windowManager?.removeView(it) } }
        panel = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.sepand.ai.STOP_OVERLAY"
        private const val CHANNEL = "sepand_phone_controls"
        private const val NOTIFICATION_ID = 701
    }
}
