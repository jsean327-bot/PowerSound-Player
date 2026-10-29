package com.powersound.player.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** First-time tips that explain the gestures. Each one is shown until you close it. */
object HintStore {
    private const val PREFS = "powersound_hints"
    private val seen = mutableStateMapOf<String, Boolean>()
    private var ctx: Context? = null

    fun init(context: Context) {
        if (ctx != null) return
        ctx = context.applicationContext
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.all.forEach { (k, v) -> if (v == true) seen[k] = true }
    }

    fun shouldShow(id: String): Boolean = seen[id] != true

    fun dismiss(id: String) {
        seen[id] = true
        ctx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putBoolean(id, true)?.apply()
    }

    fun resetAll() {
        seen.clear()
        ctx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.clear()?.apply()
    }
}

@Composable
fun HintBubble(title: String, text: String, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .widthIn(max = 300.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF26262E).copy(alpha = 0.96f))
            .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .padding(start = 14.dp, end = 4.dp, bottom = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.Close, contentDescription = "Close", tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
        Text(text, color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(end = 10.dp))
    }
}
