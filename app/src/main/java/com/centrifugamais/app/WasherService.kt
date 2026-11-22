package com.centrifugamais.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * Foreground service that keeps the app alive (and the CPU awake) while it is
 * connected to the washer, so the extra-spin automation works with the screen off.
 */
class WasherService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels(this)
        val notification = buildStatus(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_STATUS, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_STATUS, notification)
        }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "centrifuga:connection").apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        runCatching { wakeLock?.release() }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_STATUS = "status"
        private const val CHANNEL_ALERTS = "alerts"
        private const val NOTIF_STATUS = 1
        private var alertId = 100

        private fun createChannels(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_STATUS, "Status da lavadora", NotificationManager.IMPORTANCE_LOW),
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ALERTS, "Avisos da centrifugação", NotificationManager.IMPORTANCE_HIGH),
            )
        }

        private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        private fun buildStatus(ctx: Context): Notification {
            val c = WasherController
            val s = c.status
            val title = when (c.conn) {
                WasherController.Conn.CONNECTED -> s?.let { "${it.statusText} · ${it.stageName}" } ?: "Conectado"
                WasherController.Conn.CONNECTING -> "Conectando à lavadora…"
                WasherController.Conn.DISCONNECTED -> "Desconectado (tentando de novo)"
            }
            val text = buildString {
                s?.timeRemainingMin?.let { if (s.running) append("Faltam $it min. ") }
                if (c.autoEnabled) append(c.autoText)
            }.ifEmpty { "Toque para abrir" }
            return Notification.Builder(ctx, CHANNEL_STATUS)
                .setSmallIcon(android.R.drawable.ic_popup_sync)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openApp(ctx))
                .build()
        }

        fun refresh(ctx: Context) {
            if (WasherController.device == null) return
            runCatching {
                ctx.getSystemService(NotificationManager::class.java).notify(NOTIF_STATUS, buildStatus(ctx))
            }
        }

        fun alert(ctx: Context, title: String, text: String) {
            runCatching {
                createChannels(ctx)
                val n = Notification.Builder(ctx, CHANNEL_ALERTS)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setAutoCancel(true)
                    .setContentIntent(openApp(ctx))
                    .build()
                ctx.getSystemService(NotificationManager::class.java).notify(alertId++, n)
            }
        }
    }
}
