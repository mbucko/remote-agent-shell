package com.ras.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ras.MainActivity
import com.ras.R
import dagger.hilt.android.AndroidEntryPoint

/**
 * Foreground service to maintain the WebRTC connection.
 * This keeps the app alive and connection active even when in the background.
 */
@AndroidEntryPoint
class ConnectionService : Service() {

    private val binder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Must call startForeground immediately to avoid ForegroundServiceDidNotStartInTimeException
        promoteToForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Safe to call again - just updates the notification
        promoteToForeground()
        return START_STICKY
    }

    /**
     * Promote service to foreground with notification.
     *
     * CRITICAL: Must be called within 5 seconds of startForegroundService() or Android
     * will throw ForegroundServiceDidNotStartInTimeException and crash the app.
     * Safe to call multiple times - subsequent calls just update the notification.
     */
    private fun promoteToForeground() {
        val notification = buildNotification(getString(R.string.notification_connecting))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        // TODO: Clean up WebRTC connection
    }

    /**
     * Update the notification with the current connection status.
     */
    fun updateStatus(status: String) {
        val notification = buildNotification(status)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * Update the notification to show connected state.
     */
    fun setConnected(peerId: String) {
        updateStatus(getString(R.string.notification_connected, peerId))
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_connection),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_connection_desc)
            setShowBadge(false)
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(status: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    inner class LocalBinder : Binder() {
        fun getService(): ConnectionService = this@ConnectionService
    }

    companion object {
        const val CHANNEL_ID = "connection_status"
        const val NOTIFICATION_ID = 1
    }
}
