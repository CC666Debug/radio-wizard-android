package io.github.cc666debug.radiowizard

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Runs only while the app is casting. Android then treats the app like a music player and keeps it alive when it's
 * swiped away; without this the app is killed at once, and Android's output switcher shuts the speaker down with it.
 * Google's casting notification still has the controls; this one is a quiet extra that Android requires
 * (its category can be switched off in the app's notification settings, and casting still keeps going).
 */
class CastKeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    // Keeps the phone's processor awake while casting, so the next song still gets sent with the screen off.
    private var wake: PowerManager.WakeLock? = null

    override fun onDestroy() {
        try { wake?.takeIf { it.isHeld }?.release() } catch (e: Exception) {}
        wake = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Casting in the background", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Keeps the speaker playing after you close the app. You can turn this off; casting keeps working."
                setShowBadge(false)
            })
        }
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val device = intent?.getStringExtra(EXTRA_DEVICE) ?: "your speaker"
        val note = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Casting to $device")
            .setContentText("Keeps playing after you close RadioWizard")
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .build()
        try {
            ServiceCompat.startForeground(this, NOTE_ID, note,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0)
        } catch (e: Exception) {
            stopSelf()
        }
        if (wake == null) {
            wake = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RadioWizard:casting").apply { setReferenceCounted(false); acquire() }
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "cast_keepalive"
        private const val NOTE_ID = 6660
        private const val EXTRA_DEVICE = "device"

        fun start(context: Context, device: String?) {
            try {
                val i = Intent(context, CastKeepAliveService::class.java).putExtra(EXTRA_DEVICE, device)
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
            } catch (e: Exception) {}
        }

        fun stop(context: Context) {
            try { context.stopService(Intent(context, CastKeepAliveService::class.java)) } catch (e: Exception) {}
        }
    }
}
