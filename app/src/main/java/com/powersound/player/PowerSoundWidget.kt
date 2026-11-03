package com.powersound.player

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.widget.RemoteViews
import com.powersound.player.data.formatTime

/**
 * The home-screen widgets: cover, song, artist and previous / play-pause / next (4x1), and a large one (4x2)
 * that adds the song bar with times, shuffle and repeat.
 * The buttons go straight to the playback service ([PlaybackService.onStartCommand]); the service redraws the
 * widget whenever the song or the play state changes.
 */
class PowerSoundWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        // a widget was added, or the home screen asks for a fresh picture
        val service = PlaybackService.instance
        if (service != null) service.refreshWidget(force = true) else WidgetUpdater.showSaved(context)
    }
}

/** The large widget (4x2): the same plus the song bar with times, shuffle and repeat. */
class PowerSoundWidgetLarge : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val service = PlaybackService.instance
        if (service != null) service.refreshWidget(force = true) else WidgetUpdater.showSaved(context)
    }
}

/** What the widgets show. [repeat]: 0 off, 1 repeat the list, 2 repeat the song. */
data class WidgetState(
    val title: String,
    val artist: String,
    val playing: Boolean,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val repeat: Int = 0,
    val shuffle: Boolean = false
)

object WidgetUpdater {
    const val ACTION_PREFIX = "com.powersound.player.widget."
    const val ACTION_PLAY_PAUSE = ACTION_PREFIX + "PLAY_PAUSE"
    const val ACTION_NEXT = ACTION_PREFIX + "NEXT"
    const val ACTION_PREV = ACTION_PREFIX + "PREV"
    const val ACTION_REPEAT = ACTION_PREFIX + "REPEAT"
    const val ACTION_SHUFFLE = ACTION_PREFIX + "SHUFFLE"

    private const val PREFS = "powersound_widget"

    private fun ids(context: Context, cls: Class<*>): IntArray = runCatching {
        AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, cls))
    }.getOrDefault(IntArray(0))

    private fun smallIds(context: Context) = ids(context, PowerSoundWidget::class.java)
    private fun largeIds(context: Context) = ids(context, PowerSoundWidgetLarge::class.java)

    fun hasWidgets(context: Context): Boolean = smallIds(context).isNotEmpty() || largeIds(context).isNotEmpty()

    /** True when a large widget is on the home screen (only it needs the song bar moving). */
    fun hasLarge(context: Context): Boolean = largeIds(context).isNotEmpty()

    /** Draws the song and the play state into every PowerSound widget. [art] null shows a music note. */
    fun show(context: Context, s: WidgetState, art: Bitmap?) {
        val mgr = AppWidgetManager.getInstance(context)
        val cover = art?.let { rounded(it, context) }
        val small = smallIds(context)
        if (small.isNotEmpty()) {
            val v = RemoteViews(context.packageName, R.layout.widget_player)
            fill(context, v, s, cover, R.id.w_title, R.id.w_artist, R.id.w_art, R.id.w_play, R.id.w_next, R.id.w_prev, R.id.w_open, 14)
            runCatching { mgr.updateAppWidget(small, v) }
        }
        val large = largeIds(context)
        if (large.isNotEmpty()) {
            val v = RemoteViews(context.packageName, R.layout.widget_player_large)
            fill(context, v, s, cover, R.id.wl_title, R.id.wl_artist, R.id.wl_art, R.id.wl_play, R.id.wl_next, R.id.wl_prev, R.id.wl_open, 16)
            timeline(v, s)
            v.setTextViewText(R.id.wl_duration, if (s.durationMs > 0) formatTime(s.durationMs) else "")
            // shuffle / repeat: lit in the PowerSound color when on, dim when off
            val accent = context.getColor(R.color.accent)
            mode(v, R.id.wl_shuffle, s.shuffle, accent)
            v.setImageViewResource(R.id.wl_repeat, if (s.repeat == 2) R.drawable.ic_n_repeat_one else R.drawable.ic_n_repeat)
            mode(v, R.id.wl_repeat, s.repeat != 0, accent)
            v.setContentDescription(R.id.wl_shuffle, if (s.shuffle) "Shuffle on" else "Shuffle off")
            v.setContentDescription(
                R.id.wl_repeat,
                when (s.repeat) { 2 -> "Repeat song"; 1 -> "Repeat all"; else -> "Repeat off" }
            )
            v.setOnClickPendingIntent(R.id.wl_shuffle, command(context, ACTION_SHUFFLE, 15))
            v.setOnClickPendingIntent(R.id.wl_repeat, command(context, ACTION_REPEAT, 16))
            runCatching { mgr.updateAppWidget(large, v) }
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("title", s.title)
            .putString("artist", s.artist)
            .putLong("pos", s.positionMs)
            .putLong("dur", s.durationMs)
            .apply()
    }

    /**
     * Moves the song bar and keeps the time exact (the large widget only). Sent about once a second while
     * music plays and the screen is on; only these two views change, so it is cheap.
     */
    fun progress(context: Context, positionMs: Long, durationMs: Long, playing: Boolean) {
        val large = largeIds(context)
        if (large.isEmpty()) return
        val v = RemoteViews(context.packageName, R.layout.widget_player_large)
        timeline(v, WidgetState("", "", playing, positionMs, durationMs))
        runCatching { AppWidgetManager.getInstance(context).partiallyUpdateAppWidget(large, v) }
    }

    private fun timeline(v: RemoteViews, s: WidgetState) {
        val dur = s.durationMs.coerceAtLeast(0L)
        val pos = s.positionMs.coerceIn(0L, if (dur > 0) dur else Long.MAX_VALUE)
        v.setProgressBar(R.id.wl_progress, 1000, if (dur > 0) (pos * 1000 / dur).toInt() else 0, false)
        // the elapsed time counts by itself while playing
        v.setChronometer(R.id.wl_elapsed, android.os.SystemClock.elapsedRealtime() - pos, null, s.playing)
    }

    private fun mode(v: RemoteViews, id: Int, on: Boolean, accent: Int) {
        v.setInt(id, "setColorFilter", if (on) accent else 0xFFFFFFFF.toInt())
        v.setInt(id, "setImageAlpha", if (on) 255 else 110)
    }

    private fun fill(
        context: Context, v: RemoteViews, s: WidgetState, cover: Bitmap?,
        title: Int, artist: Int, art: Int, play: Int, next: Int, prev: Int, open: Int, notePadDp: Int
    ) {
        v.setTextViewText(title, s.title.ifBlank { "PowerSound" })
        v.setTextViewText(artist, s.artist)
        if (cover != null) {
            v.setImageViewBitmap(art, cover)
            v.setViewPadding(art, 0, 0, 0, 0)
        } else {
            val pad = (notePadDp * context.resources.displayMetrics.density).toInt()
            v.setImageViewResource(art, R.drawable.ic_w_note)
            v.setViewPadding(art, pad, pad, pad, pad)
        }
        v.setImageViewResource(play, if (s.playing) R.drawable.ic_w_pause else R.drawable.ic_w_play)
        v.setContentDescription(play, if (s.playing) "Pause" else "Play")
        v.setOnClickPendingIntent(play, command(context, ACTION_PLAY_PAUSE, 11))
        v.setOnClickPendingIntent(next, command(context, ACTION_NEXT, 12))
        v.setOnClickPendingIntent(prev, command(context, ACTION_PREV, 13))
        v.setOnClickPendingIntent(open, openApp(context))
    }

    /** The last song the widget showed (used when the app isn't running). */
    fun showSaved(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val modes = context.getSharedPreferences("powersound_player", Context.MODE_PRIVATE)
        show(
            context,
            WidgetState(
                p.getString("title", null) ?: "PowerSound",
                p.getString("artist", null) ?: "Tap play to listen",
                playing = false,
                positionMs = p.getLong("pos", 0L),
                durationMs = p.getLong("dur", 0L),
                repeat = repeatLevel(modes.getString("repeat", null)),
                shuffle = (modes.getString("shuffle", null) ?: "OFF") != "OFF"
            ),
            null
        )
    }

    /** The app's repeat mode name → 0 off, 1 repeat all, 2 repeat the song. */
    fun repeatLevel(name: String?): Int = when (name) {
        "SONG_REPEAT" -> 2
        "LIST_REPEAT", "ADVANCE_REPEAT" -> 1
        else -> 0
    }

    /** Button → the playback service. Android lets a widget tap start it even when the app is closed. */
    private fun command(context: Context, action: String, code: Int): PendingIntent {
        val i = Intent(context, PlaybackService::class.java).setAction(action)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        return if (Build.VERSION.SDK_INT >= 26) PendingIntent.getForegroundService(context, code, i, flags)
        else PendingIntent.getService(context, code, i, flags)
    }

    /** Cover or song name → the app's player screen. */
    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 14,
        Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_FROM_NOTIFICATION, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /** A square copy of the cover with rounded corners (home screens can't round images themselves on older Android). */
    private fun rounded(src: Bitmap, context: Context): Bitmap {
        val size = 256
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        // center-crop the cover into the square
        val scale = maxOf(size.toFloat() / src.width, size.toFloat() / src.height)
        val m = android.graphics.Matrix()
        m.setScale(scale, scale)
        m.postTranslate((size - src.width * scale) / 2f, (size - src.height * scale) / 2f)
        shader.setLocalMatrix(m)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.shader = shader }
        val r = 14f * context.resources.displayMetrics.density * size / (56f * context.resources.displayMetrics.density)
        canvas.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), r, r, paint)
        return out
    }
}
