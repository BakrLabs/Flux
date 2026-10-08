package dev.bakrlabs.flux

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

object Notifications {
    const val ID = 41
    private const val ACTIVE = "transfers"
    private const val RESULTS = "results"

    private fun manager(context: Context): NotificationManager {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(ACTIVE) == null) {
            manager.createNotificationChannel(NotificationChannel(ACTIVE, "Transfers in progress", NotificationManager.IMPORTANCE_LOW))
        }
        if (manager.getNotificationChannel(RESULTS) == null) {
            manager.createNotificationChannel(NotificationChannel(RESULTS, "Finished transfers", NotificationManager.IMPORTANCE_LOW))
        }
        return manager
    }

    private fun openApp(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    fun build(context: Context, text: String, percent: Int?): Notification {
        manager(context)
        val builder = NotificationCompat.Builder(context, ACTIVE)
            .setSmallIcon(R.drawable.ic_stat_flux)
            .setContentTitle("Flux")
            .setContentText(text)
            .setContentIntent(openApp(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (percent != null) builder.setProgress(100, percent.coerceIn(0, 100), false)
        return builder.build()
    }

    fun update(context: Context, text: String, percent: Int?) {
        manager(context).notify(ID, build(context, text, percent))
    }

    fun done(context: Context, text: String) {
        val notification = NotificationCompat.Builder(context, RESULTS)
            .setSmallIcon(R.drawable.ic_stat_flux)
            .setContentTitle("Flux")
            .setContentText(text)
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .build()
        manager(context).notify(System.currentTimeMillis().toInt(), notification)
    }
}
