package com.nuvola.vpn.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.nuvola.vpn.MainActivity
import com.nuvola.vpn.R

/**
 * Notificacion persistente para el ForegroundService del tunel.
 * Canal IMPORTANCE_LOW: no suena, pero es visible y no se puede
 * descartar mientras el servicio corre.
 */
object NotificationHelper {

    const val CHANNEL_ID = "nuvola_vpn"
    const val NOTIF_ID = 1001

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Estado del tunel NuvolaVPN"
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
    }

    fun build(context: Context, text: String, ongoing: Boolean = true): Notification {
        ensureChannel(context)
        val openIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(context.getString(R.string.notif_title))
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(ongoing)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
    }

    fun update(context: Context, text: String) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIF_ID, build(context, text))
    }

    fun cancel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.cancel(NOTIF_ID)
    }
}
