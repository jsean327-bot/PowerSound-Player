package com.powersound.player.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.powersound.player.UpdateState

private val DialogBg = Color(0xFF17171F)

@Composable
fun UpdateDialog(
    state: UpdateState,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onDismiss: () -> Unit
) {
    when (state) {
        UpdateState.Idle -> Unit

        UpdateState.Checking -> AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = DialogBg,
            title = { Text("Checking for updates…", color = Color.White) },
            text = { CircularProgressIndicator() },
            confirmButton = {}
        )

        UpdateState.UpToDate -> AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = DialogBg,
            title = { Text("You're up to date", color = Color.White) },
            text = { Text("PowerSound Player is on the latest version.", color = Color.White.copy(alpha = 0.8f)) },
            confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } }
        )

        is UpdateState.Available -> AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = DialogBg,
            title = { Text("New update available", color = Color.White) },
            text = {
                Column {
                    Text(state.info.versionName, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    if (state.info.sizeBytes > 0) {
                        Text("%.1f MB".format(state.info.sizeBytes / 1_048_576.0), color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                    }
                    if (state.info.notes.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            state.info.notes,
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 13.sp,
                            modifier = Modifier
                                .heightIn(max = 220.dp)
                                .verticalScroll(rememberScrollState())
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("Do you want to update now?", color = Color.White)
                }
            },
            confirmButton = { TextButton(onClick = onDownload) { Text("Update") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Later") } }
        )

        is UpdateState.Downloading -> AlertDialog(
            onDismissRequest = {},
            containerColor = DialogBg,
            title = { Text("Downloading update…", color = Color.White) },
            text = {
                Column {
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("${(state.progress * 100).toInt()}%", color = Color.White.copy(alpha = 0.7f))
                }
            },
            confirmButton = {}
        )

        is UpdateState.Ready -> AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = DialogBg,
            title = { Text("Ready to install", color = Color.White) },
            text = {
                Text(
                    if (state.needsPermission)
                        "Allow \"Install unknown apps\" for PowerSound on the page that opened, then come back and tap Install."
                    else
                        "Tap Install to update PowerSound Player. Your library, EQ and presets are kept.",
                    color = Color.White.copy(alpha = 0.85f)
                )
            },
            confirmButton = { TextButton(onClick = onInstall) { Text("Install") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Later") } }
        )

        is UpdateState.Failed -> AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = DialogBg,
            title = { Text("Update failed", color = Color.White) },
            text = { Text(state.message, color = Color.White.copy(alpha = 0.8f)) },
            confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } }
        )
    }
}
