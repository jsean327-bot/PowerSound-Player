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
        private const val ACTION_BACK10 = "com.powersound.player.video.CAST_BACK10"
        private const val ACTION_FWD10 = "com.powersound.player.video.CAST_FWD10"
        private const val ACTION_STOP_CAST = "com.powersound.player.video.CAST_STOP"

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
        val casting = activity?.castingTo?.value != null
        when (intent?.action) {
            ACTION_TOGGLE -> if (casting) activity?.castToggle() else activity?.togglePlay()
            ACTION_BACK10 -> activity?.let { it.castSeek(it.castPosition.longValue - 10_000) }
            ACTION_FWD10 -> activity?.let { it.castSeek(it.castPosition.longValue + 10_000) }
            ACTION_STOP_CAST -> {
                activity?.stopCasting()
                stopSelf()
                return START_NOT_STICKY
            }
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
        val device = activity?.castingTo?.value
        if (activity != null && device != null) {
            showCasting(activity, device)
            return
        }
        val playing = activity?.playerOrNull()?.playWhenReady == true
        val title = activity?.playerOrNull()?.mediaMetadata?.title?.toString() ?: "Video"
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Video in the background", NotificationManager.IMPORTANCE_LOW))
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(
            this, 40,
            Intent(this, VideoActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
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

    /** Casting: the TV's controls (−10 s, play / pause, +10 s, stop) while you are in another app. */
    private fun showCasting(activity: VideoActivity, device: String) {
        val playing = activity.castPlaying.value
        val title = activity.playerOrNull()?.mediaMetadata?.title?.toString() ?: "Video"
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Video in the background", NotificationManager.IMPORTANCE_LOW))
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(
            this, 40,
            Intent(this, VideoActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            flags
        )
        fun action(code: Int, a: String) = PendingIntent.getService(this, code, Intent(this, VideoBackgroundService::class.java).setAction(a), flags)
        fun icon(res: Int) = android.graphics.drawable.Icon.createWithResource(this, res)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        val n = b.setSmallIcon(R.drawable.ic_w_note)
            .setContentTitle(title)
            .setContentText("Playing on $device")
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(Notification.Action.Builder(icon(R.drawable.ic_n_rewind), "−10 s", action(43, ACTION_BACK10)).build())
            .addAction(
                Notification.Action.Builder(
                    icon(if (playing) R.drawable.ic_w_pause else R.drawable.ic_w_play), if (playing) "Pause" else "Play", action(41, ACTION_TOGGLE)
                ).build()
            )
            .addAction(Notification.Action.Builder(icon(R.drawable.ic_n_forward), "+10 s", action(44, ACTION_FWD10)).build())
            .addAction(Notification.Action.Builder(icon(R.drawable.ic_n_close), "Stop casting", action(45, ACTION_STOP_CAST)).build())
            .setStyle(Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2))
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
