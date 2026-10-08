package dev.bakrlabs.flux

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat

class TransferService : Service() {
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra("text") ?: "Transfer running"
        startForeground(
            Notifications.ID,
            Notifications.build(this, text, null),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        if (wake == null) {
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "flux:transfer").apply {
                acquire(6 * 60 * 60 * 1000L)
            }
            val manager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifi = manager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "flux:transfer").apply {
                acquire()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wake?.takeIf { it.isHeld }?.release()
        wifi?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    companion object {
        fun start(context: Context, text: String) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, TransferService::class.java).putExtra("text", text),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TransferService::class.java))
        }
    }
}
