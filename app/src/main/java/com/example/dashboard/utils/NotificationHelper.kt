package com.example.dashboard.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.dashboard.R

object NotificationHelper {
    private const val CHANNEL_ID = "maintenance_channel"

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Entretien Véhicule"
            val descriptionText = "Alertes pour les consommables"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun sendWarningNotification(context: Context, itemName: String, remainingKm: Int) {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning) // Icône système simple
            .setContentTitle("Attention : $itemName")
            .setContentText("Il reste moins de $remainingKm km avant l'entretien !")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // ID unique basé sur le temps pour ne pas écraser les notifs précédentes
        notificationManager.notify(System.currentTimeMillis().toInt(), builder.build())
    }
}