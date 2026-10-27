package com.dpibreak.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.dpibreak.R

/**
 * Утилиты для создания notification-канала и уведомления сервиса.
 */
object NotificationUtils {

    private const val CHANNEL_ID = "vpn_service"
    const val NOTIFICATION_ID = 1

    /**
     * Создаёт notification-канал (вызывать один раз при старте приложения/сервиса).
     */
    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.vpn_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Уведомление о работе DPIBreak"
                setShowBadge(false)
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * Создаёт уведомление для foreground-сервиса.
     */
    fun createNotification(
        context: Context,
        pendingIntent: android.app.PendingIntent,
        contentText: String? = null
    ): Notification {
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.vpn_notification_title))
            .setContentText(contentText ?: context.getString(R.string.vpn_notification_text))
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
