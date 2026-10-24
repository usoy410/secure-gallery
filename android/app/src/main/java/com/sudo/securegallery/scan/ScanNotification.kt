package com.sudo.securegallery.scan

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import com.sudo.securegallery.R

/**
 * The ongoing "Checking your photos for IDs" notification of a running scan. It makes the scan
 * a foreground job: without it Android stops background work after 10 minutes, and a first scan
 * of a large gallery takes longer. It shows no file name or anything from a photo.
 */
object ScanNotification {
    private const val CHANNEL = "gallery-scan"
    const val ID = 2

    fun foregroundInfo(context: Context): ForegroundInfo {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.scan_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle(context.getString(R.string.scan_notification_title))
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setSilent(true)
            .build()
        return ForegroundInfo(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
}
