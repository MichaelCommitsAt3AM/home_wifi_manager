package com.homewifi.manager

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
private fun ConnectionForm(vm: WifiViewModel, state: UiState) {
    var address by remember { mutableStateOf(vm.config.url) }
    var token by remember { mutableStateOf("") }
    OutlinedTextField(address, { address = it }, Modifier.fillMaxWidth(), label = { Text("Server address (HTTPS)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
    OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth(), label = { Text("API token") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { vm.connect(address.trim(), token.ifBlank { vm.config.token }); token = "" }, enabled = !state.busy) { Text("Save and test") }
        if (state.authenticated) TextButton(onClick = vm::logout) { Text("Disconnect") }
    }
}

@Composable
fun SettingsScreen(
    vm: WifiViewModel, state: UiState, onActivity: () -> Unit, onHealth: () -> Unit = {}, onWifi: () -> Unit = {},
    updatePreview: Boolean = false, onPreviewUpdate: (Boolean) -> Unit = {},
    updateState: UpdateState = UpdateState(), onCheckUpdates: () -> Unit = {}
) {
    var releaseConfirmation by remember { mutableStateOf(false) }
    if (releaseConfirmation) AlertDialog(onDismissRequest = { releaseConfirmation = false },
        title = { Text("Release router access?") },
        text = { Text("Logs the home server out so a technician can use the router dashboard. Router monitoring and controls stay paused until you resume, including after a server restart. Missed scheduled actions will be skipped. Timed unblocks wait until router access resumes. AdGuard monitoring continues.") },
        confirmButton = { TextButton(onClick = { releaseConfirmation = false; vm.routerAccess(true) }) { Text("Release access") } },
        dismissButton = { TextButton(onClick = { releaseConfirmation = false }) { Text("Cancel") } })
    var poll by remember(state.settings.poll_seconds) { mutableStateOf(state.settings.poll_seconds.toString()) }
    var notifications by remember { mutableStateOf(vm.config.notifications) }
    var categories by remember(state.settings.notification_categories) { mutableStateOf(state.settings.notification_categories.toSet()) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> notifications = granted; vm.config.notifications = granted }
    val categoriesChanged = categories != state.settings.notification_categories.toSet()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SectionTitle("Settings") }
        if (!state.authenticated) item {
            SoftCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Connect to your home server", style = MaterialTheme.typography.titleMedium)
                    Text("Enter the address and token from your server setup.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ConnectionForm(vm, state)
                }
            }
        }
        if (BuildConfig.DEBUG) item {
            SoftCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(18.dp)) {
                    ToggleRow("Preview update banner", "Debug build only", updatePreview, onPreviewUpdate)
                }
            }
        }
        item {
            ExpandableCard("Appearance", "Light, dark and colors", Icons.Default.Palette) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("system", "light", "dark").forEach { theme -> FilterChip(state.theme == theme, { vm.theme(theme) }, label = { Text(theme.replaceFirstChar { it.uppercase() }) }) }
                }
                if (Build.VERSION.SDK_INT >= 31) ToggleRow("Use wallpaper colors", "Replace the warm theme with your phone's colors", state.dynamicColors, vm::dynamicColors)
            }
        }
        item {
            ExpandableCard("Notifications", "Choose what to hear about", Icons.Default.Notifications) {
                ToggleRow("Notify me", "Android may delay background checks", notifications, onChange = { enabled ->
                    notifications = enabled; vm.config.notifications = enabled
                    if (enabled && Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                })
                if (state.authenticated) {
                    Text("Tell me when", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    listOf("unknown_device", "router_offline", "router_restored", "schedule_executed", "schedule_failed", "management_failed").forEach { kind ->
                        ToggleRow(notificationLabel(kind), null, kind in categories, { selected -> categories = if (selected) categories + kind else categories - kind })
                    }
                    if (categoriesChanged) Button(onClick = { vm.preferences(state.settings.poll_seconds, categories.toList()) }, enabled = !state.busy) { Text("Save") }
                }
            }
        }
        if (state.authenticated) {
            if (state.dashboard.adguard.configured) item {
                ExpandableCard("Ad blocking", "Pause, resume and allow websites", Icons.Default.VerifiedUser) {
                    AdGuardCard(state.dashboard.adguard, expanded = true)
                    AdGuardControls(vm, state)
                }
            }
            item { SettingsRow(Icons.Default.Public, "Internet", "Status, speed test and outages", onHealth) }
            item { SettingsRow(Icons.Default.Wifi, "Wi-Fi radio", "Mode, channel and channel analyzer", onWifi) }
            item { SettingsRow(Icons.Default.History, "Activity", "Recent events on your network", onActivity) }
        }
        item {
            ExpandableCard("About and updates", "Version ${BuildConfig.VERSION_NAME}", Icons.Default.Info) {
                OutlinedButton(onClick = onCheckUpdates, enabled = !updateState.checking) { Text(if (updateState.checking) "Checking for updates…" else "Check for updates") }
                updateState.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
        item {
            ExpandableCard("Advanced", "Server connection, router access and diagnostics", Icons.Default.Build,
                initiallyExpanded = state.authenticated && state.settings.router_access_paused) {
                if (state.authenticated) {
                    Text("Server connection", style = MaterialTheme.typography.titleSmall)
                    ConnectionForm(vm, state)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text("Router access", style = MaterialTheme.typography.titleSmall)
                    Text(if (state.settings.router_access_paused) "Monitoring paused" else "Monitoring active", style = MaterialTheme.typography.bodyMedium)
                    Text(if (state.settings.router_access_paused && state.settings.router_logout_verified) "The server is logged out. You can use the router dashboard until you resume here."
                        else if (state.settings.router_access_paused) "Automatic login is paused, but logout could not be confirmed. An old router session may need to expire."
                        else "Release the server's dashboard session when a technician needs access.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!state.settings.router_access_paused || !state.settings.router_logout_verified) OutlinedButton(onClick = { releaseConfirmation = true }, enabled = !state.busy) { Text(if (state.settings.router_access_paused) "Retry logout" else "Release router access") }
                    if (state.settings.router_access_paused) Button(onClick = { vm.routerAccess(false) }, enabled = !state.busy) { Text("Resume router access") }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text("Monitoring", style = MaterialTheme.typography.titleSmall)
                    Text("How often the server refreshes router information", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(poll, { poll = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(), label = { Text("Polling interval (seconds)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = poll.toIntOrNull() !in 30..3600)
                    Button(onClick = { vm.preferences(poll.toInt(), state.settings.notification_categories) }, enabled = poll.toIntOrNull() in 30..3600 && !state.busy) { Text("Save") }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text("Diagnostics", style = MaterialTheme.typography.titleSmall)
                    DetailLine("Last sync", timestamp(state.dashboard.last_sync))
                    DetailLine("Known devices", state.dashboard.total.toString())
                    DetailLine("Unknown online", state.dashboard.unknown.toString())
                    DetailLine("Server connection", if (state.cached) "Unavailable or unverified" else "Connected over HTTPS")
                    DetailLine("Router", "${state.dashboard.router.model} ${state.dashboard.router.firmware}")
                    DetailLine("Resources", "CPU ${state.dashboard.router.cpu.ifBlank { "unavailable" }} · Memory ${state.dashboard.router.memory.ifBlank { "unavailable" }}")
                    DetailLine("Device controls", if (state.settings.writes_enabled) "Enabled" else "Disabled pending validation")
                    DetailLine("Push relay", if (state.settings.ntfy_configured) "Configured" else "Not configured")
                    if (state.dashboard.adguard.configured) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Text("Ad blocking details", style = MaterialTheme.typography.titleSmall)
                        AdGuardTechnicalDetails(state.dashboard.adguard)
                    }
                } else Text("Connect to your server above to see these options.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
