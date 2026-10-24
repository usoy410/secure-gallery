package com.sudo.securegallery.secure

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
import com.sudo.securegallery.MainActivity
import com.sudo.securegallery.R

/**
 * "N photos ready to secure" (§8.2, #22). Tapping it opens the app, where one system dialog
 * deletes the originals. It holds a count only, never a file name or anything from a photo.
 * Without the notification permission nothing is posted; the scan screen shows the same count.
 */
object ReadyNotification {
    private const val CHANNEL = "ready-to-secure"
    private const val ID = 1

    fun post(context: Context, ready: Int) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (ready <= 0) {
            manager.cancel(ID)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.secure_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val text = context.resources.getQuantityString(R.plurals.secure_ready, ready, ready)
        manager.notify(
            ID,
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle(text)
                .setContentText(context.getString(R.string.secure_notification_body))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }
}
