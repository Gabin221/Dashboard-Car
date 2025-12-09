package com.example.dashboard.utils

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat

object NotificationHelper {
    private const val CHANNEL_ID = "maintenance_channel"

    fun sendWarningNotification(context: Context, itemName: String, remainingKm: Int) {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("Attention : $itemName")
            .setContentText("Il reste moins de $remainingKm km avant l'entretien !")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(System.currentTimeMillis().toInt(), builder.build())
    }
}