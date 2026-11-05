package com.powersound.player.video

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.powersound.player.R
import java.lang.ref.WeakReference

/**
 * Background play for videos (⋮ → Background Play): when you leave the video, the sound keeps going with a
 * notification (play / pause / close). Coming back to the video ends it.
 */
class VideoBackgroundService : Service() {

    companion object {
        private const val CHANNEL = "video_background"
        private const val ID = 2207
        private const val ACTION_TOGGLE = "com.powersound.player.video.BG_TOGGLE"
        private const val ACTION_CLOSE = "com.powersound.player.video.BG_CLOSE"

        @Volatile
        private var running: WeakReference<VideoBackgroundService>? = null

        /** The video screen whose player this notification controls. */
        @Volatile
        var owner: WeakReference<VideoActivity>? = null

        fun start(activity: VideoActivity) {
            owner = WeakReference(activity)
            runCatching {
                val i = Intent(activity, VideoBackgroundService::class.java)
                if (Build.VERSION.SDK_INT >= 26) activity.startForegroundService(i) else activity.startService(i)
            }
        }

        fun stop(context: Context) {
            if (running?.get() == null) return
            runCatching { context.stopService(Intent(context, VideoBackgroundService::class.java)) }
        }

        /** Play / pause changed: redraw the notification. */
        fun refresh() {
            running?.get()?.show()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = WeakReference(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val activity = owner?.get()
        when (intent?.action) {
            ACTION_TOGGLE -> activity?.togglePlay()
            ACTION_CLOSE -> {
                activity?.let { it.playerOrNull()?.pause() }
                show()
                stopSelf()
                return START_NOT_STICKY
            }
        }
        show()
        if (activity == null) stopSelf()
        return START_NOT_STICKY
    }

    fun show() {
        val activity = owner?.get()
        val playing = activity?.playerOrNull()?.playWhenReady == true
        val title = activity?.playerOrNull()?.mediaMetadata?.title?.toString() ?: "Video"
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Video in the background", NotificationManager.IMPORTANCE_LOW))
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(
            this, 40,
            Intent(this, VideoActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            flags
        )
        val toggle = PendingIntent.getService(this, 41, Intent(this, VideoBackgroundService::class.java).setAction(ACTION_TOGGLE), flags)
        val close = PendingIntent.getService(this, 42, Intent(this, VideoBackgroundService::class.java).setAction(ACTION_CLOSE), flags)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        val n = b.setSmallIcon(R.drawable.ic_w_note)
            .setContentTitle(title)
            .setContentText(if (playing) "Playing in the background" else "Paused")
            .setContentIntent(open)
            .setOngoing(playing)
            .setShowWhen(false)
            .addAction(Notification.Action.Builder(null as android.graphics.drawable.Icon?, if (playing) "Pause" else "Play", toggle).build())
            .addAction(Notification.Action.Builder(null as android.graphics.drawable.Icon?, "Close", close).build())
            .build()
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            else startForeground(ID, n)
        }
    }

    override fun onDestroy() {
        running = null
        runCatching {
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
            else @Suppress("DEPRECATION") stopForeground(true)
        }
        super.onDestroy()
    }
}
