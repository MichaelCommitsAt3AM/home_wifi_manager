package com.homewifi.manager

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun UpdateBanner(state: UpdateState, preview: Boolean = false, onAction: () -> Unit, onDismiss: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.SystemUpdate, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(if(!preview && state.stage == "ready") "Update ready to install" else "Update available", style = MaterialTheme.typography.titleSmall)
                    Text(when {
                        preview -> "Preview · no download will start"
                        state.stage == "downloading" -> "Downloading ${state.progress}%"
                        state.stage == "error" -> state.message ?: "Download failed. Try again."
                        state.stage == "ready" -> state.message ?: "Home WiFi ${state.release?.versionName}"
                        else -> "Home WiFi ${state.release?.versionName}"
                    }, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = onAction, enabled = preview || state.stage != "downloading") {
                    Text(if(!preview && state.stage == "ready") "Install" else if(!preview && state.stage == "error") "Retry" else "Update")
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Dismiss update banner", Modifier.size(18.dp)) }
            }
            if(!preview && state.stage == "downloading") LinearProgressIndicator(progress = { state.progress/100f }, modifier = Modifier.fillMaxWidth())
        }
    }
}
