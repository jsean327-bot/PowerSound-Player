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

/**
 * The home-screen widget: cover, song, artist and previous / play-pause / next.
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

object WidgetUpdater {
    const val ACTION_PREFIX = "com.powersound.player.widget."
    const val ACTION_PLAY_PAUSE = ACTION_PREFIX + "PLAY_PAUSE"
    const val ACTION_NEXT = ACTION_PREFIX + "NEXT"
    const val ACTION_PREV = ACTION_PREFIX + "PREV"

    private const val PREFS = "powersound_widget"

    private fun ids(context: Context): IntArray = runCatching {
        AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, PowerSoundWidget::class.java))
    }.getOrDefault(IntArray(0))

    fun hasWidgets(context: Context): Boolean = ids(context).isNotEmpty()

    /** Draws the song and the play state into every PowerSound widget. [art] null shows a music note. */
    fun show(context: Context, title: String, artist: String, art: Bitmap?, playing: Boolean) {
        val ids = ids(context)
        if (ids.isEmpty()) return
        val v = RemoteViews(context.packageName, R.layout.widget_player)
        v.setTextViewText(R.id.w_title, title.ifBlank { "PowerSound" })
        v.setTextViewText(R.id.w_artist, artist)
        if (art != null) {
            v.setImageViewBitmap(R.id.w_art, rounded(art, context))
            v.setViewPadding(R.id.w_art, 0, 0, 0, 0)
        } else {
            val pad = (14 * context.resources.displayMetrics.density).toInt()
            v.setImageViewResource(R.id.w_art, R.drawable.ic_w_note)
            v.setViewPadding(R.id.w_art, pad, pad, pad, pad)
        }
        v.setImageViewResource(R.id.w_play, if (playing) R.drawable.ic_w_pause else R.drawable.ic_w_play)
        v.setContentDescription(R.id.w_play, if (playing) "Pause" else "Play")
        v.setOnClickPendingIntent(R.id.w_play, command(context, ACTION_PLAY_PAUSE, 11))
        v.setOnClickPendingIntent(R.id.w_next, command(context, ACTION_NEXT, 12))
        v.setOnClickPendingIntent(R.id.w_prev, command(context, ACTION_PREV, 13))
        v.setOnClickPendingIntent(R.id.w_open, openApp(context))
        runCatching { AppWidgetManager.getInstance(context).updateAppWidget(ids, v) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("title", title)
            .putString("artist", artist)
            .apply()
    }

    /** The last song the widget showed (used when the app isn't running). */
    fun showSaved(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        show(context, p.getString("title", null) ?: "PowerSound", p.getString("artist", null) ?: "Tap play to listen", null, false)
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
