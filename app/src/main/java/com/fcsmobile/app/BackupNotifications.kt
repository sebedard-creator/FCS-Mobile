package com.fcsmobile.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class BackupNotifications(private val context: Context, private val notificationId: Int) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    private fun notification(progress: BackupProgress): android.app.Notification {
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Sauvegardes en cours", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Progression des sauvegardes vers le PC sur le réseau local"
            setShowBadge(false)
        })
        val open = PendingIntent.getActivity(context, notificationId,
            Intent(context, MainActivity::class.java).putExtra("page", "backup")
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(context, CHANNEL)
            .setColor(ContextCompat.getColor(context, R.color.app_accent))
            .setSmallIcon(R.drawable.ic_backup_notification)
            .setContentTitle("FCS Mobile · Sauvegarde en cours")
            .setContentText(progress.summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                if (progress.total > 0) "${progress.phase}\n${progress.summary}" else progress.phase))
            .setProgress(progress.total, progress.completed.coerceIn(0, progress.total.coerceAtLeast(0)), progress.total == 0)
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS).setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    fun update(progress: BackupProgress) {
        if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            manager.notify(notificationId, notification(progress))
        }
    }

    fun dismiss() { manager.cancel(notificationId) }

    companion object {
        const val CHANNEL = "fcs-mobile-backups"
    }
}
