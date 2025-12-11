package com.example.dashboard

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

class DashboardWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    private fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_dashboard)

        // Bouton Conduite -> Ouvre MainActivity avec un extra "NAV_TARGET" = "dashboard"
        val intentDrive = Intent(context, MainActivity::class.java).apply {
            putExtra("NAV_TARGET", "dashboard")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntentDrive = PendingIntent.getActivity(context, 0, intentDrive, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        views.setOnClickPendingIntent(R.id.widget_btn_drive, pendingIntentDrive)

        // Bouton Entretien -> Ouvre MainActivity avec un extra "NAV_TARGET" = "maintenance"
        val intentMaint = Intent(context, MainActivity::class.java).apply {
            putExtra("NAV_TARGET", "maintenance")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntentMaint = PendingIntent.getActivity(context, 1, intentMaint, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        views.setOnClickPendingIntent(R.id.widget_btn_maint, pendingIntentMaint)

        appWidgetManager.updateAppWidget(appWidgetId, views)
    }
}