package io.github.cc666debug.radiowizard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat

/**
 * Runs only while the app is casting. Android then treats the app like a music player and keeps it alive when it's
 * swiped away; without this the app is killed at once, and Android's output switcher shuts the speaker down with it.
 * Its notification is RW's player (song, art, ⏮ ⏯ ⏭, also on the lock screen); the buttons work like RW's own
 * and like the headphone buttons (see MediaKeys).
 */
class CastKeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra(EXTRA_KEY)?.let { k ->
            keyHandler?.invoke(k)
            if (!running) stopSelf()
            return START_NOT_STICKY
        }
        intent?.getStringExtra(EXTRA_DEVICE)?.let { device = it }
        try {
            ServiceCompat.startForeground(this, NOTE_ID, build(this),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0)
            running = true
        } catch (e: Exception) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "rw_player"
        private const val OLD_CHANNEL = "cast_keepalive"
        private const val NOTE_ID = 6660
        private const val EXTRA_DEVICE = "device"
        private const val EXTRA_KEY = "key"

        /** Set by MainActivity: the notification's buttons go to the page. */
        @Volatile var keyHandler: ((String) -> Unit)? = null
        @Volatile private var running = false
        @Volatile private var device = "your speaker"

        private fun keyIntent(c: Context, k: String, code: Int) = PendingIntent.getService(c, code,
            Intent(c, CastKeepAliveService::class.java).putExtra(EXTRA_KEY, k),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        private fun action(c: Context, icon: Int, label: String, k: String, code: Int) =
            Notification.Action.Builder(Icon.createWithResource(c, icon), label, keyIntent(c, k, code)).build()

        private fun build(c: Context): Notification {
            val nm = c.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                nm.deleteNotificationChannel(OLD_CHANNEL)
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Player", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "What's playing on the speaker, with play / next / previous."
                    setShowBadge(false)
                })
            }
            val open = PendingIntent.getActivity(c, 0,
                Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            @Suppress("DEPRECATION")
            val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(c, CHANNEL) else Notification.Builder(c)
            b.setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(MediaKeys.noteTitle.ifEmpty { "Casting to $device" })
                .setContentText(MediaKeys.noteText.ifEmpty { "RadioWizard" })
                .setSubText(device)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .addAction(action(c, android.R.drawable.ic_media_previous, "Previous", "prev", 11))
                .addAction(if (MediaKeys.notePlaying) action(c, android.R.drawable.ic_media_pause, "Pause", "pause", 12)
                    else action(c, android.R.drawable.ic_media_play, "Play", "play", 13))
                .addAction(action(c, android.R.drawable.ic_media_next, "Next", "next", 14))
            MediaKeys.noteArt?.let { b.setLargeIcon(it) }
            val style = Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2)
            MediaKeys.token?.let { style.setMediaSession(it) }
            b.setStyle(style)
            return b.build()
        }

        /** New song / play state: redraws the notification while casting. */
        fun refresh(c: Context) {
            if (!running) return
            try { c.getSystemService(NotificationManager::class.java).notify(NOTE_ID, build(c)) } catch (e: Exception) {}
        }

        fun start(context: Context, device: String?) {
            try {
                val i = Intent(context, CastKeepAliveService::class.java).putExtra(EXTRA_DEVICE, device ?: "your speaker")
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
            } catch (e: Exception) {}
        }

        fun stop(context: Context) {
            running = false
            try { context.stopService(Intent(context, CastKeepAliveService::class.java)) } catch (e: Exception) {}
        }
    }
}
