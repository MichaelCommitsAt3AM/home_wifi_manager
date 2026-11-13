package com.homewifi.manager

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId

private fun connectionLabel(device: Device): String? = when (device.connection_type) {
    "wireless" -> "Wi-Fi"
    "wired", "ethernet" -> "Cable"
    else -> null
}

fun deviceStatus(device: Device): String = when {
    device.blocked -> "Internet paused"
    device.online -> listOfNotNull("Online", connectionLabel(device)).joinToString(" · ")
    device.last_seen != null -> "Last seen ${timestamp(device.last_seen)}"
    else -> "Offline"
}

@Composable
fun deviceStatusColor(device: Device): Color = when {
    device.blocked -> MaterialTheme.colorScheme.error
    device.online -> onlineColor()
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceList(state: UiState, initialFilter: String, onSelect: (String) -> Unit, refresh: () -> Unit) {
    var search by remember { mutableStateOf("") }
    var filter by remember(initialFilter) { mutableStateOf(initialFilter) }
    var sort by remember { mutableStateOf("Name") }
    val selected = selectDevices(state.devices, search, filter, sort)
    val online = state.devices.count { it.online && !it.blocked }
    val paused = state.devices.count { it.blocked }
    PullToRefreshBox(isRefreshing = state.loading, onRefresh = refresh) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { SectionTitle("Devices", if (state.devices.isEmpty()) "Everything connected to your network" else "$online online · $paused paused") }
            item {
                OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(), placeholder = { Text("Search devices") },
                    leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true, shape = RoundedCornerShape(20.dp))
            }
            item {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("All", "Online", "Offline", "Paused", "Trusted").forEach { label ->
                        FilterChip(selected = filter == label, onClick = { filter = label }, label = { Text(label) }, shape = CircleShape)
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (selected.size == 1) "1 device" else "${selected.size} devices", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { sort = if (sort == "Name") "Recent" else "Name" }) { Icon(Icons.Default.Sort, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(if (sort == "Name") "By name" else "Recently seen") }
                }
            }
            if (selected.isEmpty()) item {
                EmptyState(if (filter == "Paused") Icons.Default.VerifiedUser else Icons.Default.Devices,
                    if (filter == "Paused") "No paused devices" else "No devices found",
                    if (state.authenticated) "Pull down to refresh, or try a different search." else "Connect to your home server in Settings to see your devices.")
            }
            items(selected, key = { it.mac }) { DeviceRow(it) { onSelect(it.mac) } }
        }
    }
}

@Composable
fun DeviceRow(device: Device, click: () -> Unit) {
    SoftCard(Modifier.fillMaxWidth(), onClick = click) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            DeviceAvatar(device)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(device.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(deviceStatus(device), style = MaterialTheme.typography.bodySmall, color = deviceStatusColor(device))
            }
            if (device.protected) Icon(Icons.Default.Lock, "Always allowed online", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(Icons.Default.ChevronRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun DeviceDetails(vm: WifiViewModel, state: UiState, mac: String) {
    val device = state.devices.firstOrNull { it.mac == mac }
    var pauseMinutes by remember { mutableStateOf<Int?>(null) }
    var confirm by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var alias by remember(device?.alias) { mutableStateOf(device?.alias.orEmpty()) }
    var schedule by remember { mutableStateOf(false) }
    if (device == null) { EmptyState(Icons.Default.Devices, "Device unavailable", "Refresh the device list and try again."); return }

    val canManage = state.settings.writes_enabled && !state.cached && !state.dashboard.stale && !state.busy
    val timer = state.schedules.firstOrNull { it.enabled && it.status.startsWith("timed_") }
    val reason = when {
        !state.settings.writes_enabled -> if (state.settings.router_access_paused) "Resume monitoring in Settings to manage devices." else "Device controls are switched off right now."
        state.cached || state.dashboard.stale -> "Reconnect to your home server to make changes."
        device.protected && !device.blocked -> "This device is set to always stay online, so it can't be paused."
        else -> null
    }

    if (confirm) AlertDialog(onDismissRequest = { confirm = false },
        title = { Text(if (device.blocked) "Resume internet for ${device.name}?" else if (pauseMinutes != null) "Pause ${device.name} for ${pauseMinutes} minutes?" else "Pause internet for ${device.name}?") },
        text = { Text(if (device.blocked) "${device.name} will be able to go online again."
            else "${device.name} will lose its internet connection${if (pauseMinutes != null) " and come back automatically afterwards" else " until you resume it"}. Any schedules for this device are paused.\n\nSome devices can get around this by changing their Wi-Fi address.") },
        confirmButton = { Button(onClick = { confirm = false; vm.access(device, pauseMinutes) }) { Text(if (device.blocked) "Resume" else "Pause") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } })
    if (rename) AlertDialog(onDismissRequest = { rename = false }, title = { Text("Rename device") },
        text = { OutlinedTextField(alias, { alias = it.take(80) }, label = { Text("Name") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { vm.patch(mac, DevicePatch(alias = alias)); rename = false }) { Text("Save") } },
        dismissButton = { TextButton(onClick = { rename = false }) { Text("Cancel") } })
    if (schedule) ScheduleDialog(mac, onDismiss = { schedule = false }, onSave = { vm.schedule(it); schedule = false })

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DeviceAvatar(device, 76.dp)
                Spacer(Modifier.height(6.dp))
                Text(device.name, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                Text(deviceStatus(device), style = MaterialTheme.typography.bodyMedium, color = deviceStatusColor(device))
            }
        }
        item {
            Button(onClick = { pauseMinutes = null; confirm = true }, modifier = Modifier.fillMaxWidth().height(52.dp),
                enabled = canManage && (!device.protected || device.blocked)) {
                Icon(if (device.blocked) Icons.Default.PlayArrow else Icons.Default.Pause, null)
                Spacer(Modifier.width(8.dp))
                Text(if (device.blocked) "Resume internet" else "Pause internet")
            }
        }
        if (!device.blocked) item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Or pause for", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(15 to "15 min", 60 to "1 hour", 240 to "4 hours").forEach { (minutes, label) ->
                        OutlinedButton(onClick = { pauseMinutes = minutes; confirm = true }, modifier = Modifier.weight(1f),
                            enabled = canManage && !device.protected) { Text(label) }
                    }
                }
            }
        }
        timer?.let { item {
            Text(if (it.status == "timed_retry") "Internet will resume automatically once your router is reachable." else "Internet resumes automatically ${timestamp(it.next_run)}.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
        reason?.let { item { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        item {
            SoftCard(Modifier.fillMaxWidth()) {
                Column {
                    ActionRow(Icons.Default.Edit, "Name", device.name, { rename = true })
                    HorizontalDivider(Modifier.padding(horizontal = 18.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        ToggleRow("Trusted device", "Mark devices you recognize", device.trusted, { vm.patch(mac, DevicePatch(trusted = it)) }, enabled = !state.busy)
                        ToggleRow("Always allow online", "Prevents this device from being paused", device.protected, { vm.patch(mac, DevicePatch(protected = it)) }, enabled = !state.busy)
                    }
                }
            }
        }
        item { SectionTitle("Schedules", "Run on your home server, even when the app is closed") }
        items(state.schedules.filterNot { it.enabled && it.status.startsWith("timed_") }, key = { it.id }) { item ->
            SoftCard(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("${if (item.action == "block") "Pause" else "Resume"} · ${timestamp(item.next_run)}", style = MaterialTheme.typography.titleSmall)
                    Text(if (item.local_time.isNotBlank()) "Repeats weekly at ${item.local_time}" else "One time", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (item.enabled) TextButton(onClick = { vm.cancel(item) }, contentPadding = PaddingValues(0.dp)) { Text("Cancel schedule") }
                }
            }
        }
        item {
            OutlinedButton(onClick = { schedule = true }, enabled = state.settings.writes_enabled && !device.protected && !state.busy && timer == null) {
                Icon(Icons.Default.Schedule, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Add schedule")
            }
        }
        item { SectionTitle("Recent activity") }
        if (state.history.isEmpty()) item { Text("Nothing recorded yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(state.history, key = { it.id }) { EventRow(it, null) }
        item {
            ExpandableCard("Technical details", "Addresses and router information") {
                DetailLine("IP address", device.ip ?: "Not reported"); DetailLine("MAC address", device.mac)
                DetailLine("Hostname", device.hostname.ifBlank { "Not reported" }); DetailLine("Device type", device.device_type.ifBlank { "Not reported" })
                DetailLine("Connection", device.connection_type); DetailLine("Port", device.port)
                DetailLine("First seen", timestamp(device.first_seen)); DetailLine("Last online", timestamp(device.last_seen))
                DetailLine("Router restriction", if (device.blocked) device.restriction_scope else "None")
                Text("A Wi-Fi restriction applies to its listed network; access through another connection or a changed MAC address may differ.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun ScheduleDialog(mac: String, onDismiss: () -> Unit, onSave: (ScheduleDraft) -> Unit) {
    var pause by remember { mutableStateOf(true) }
    var recurring by remember { mutableStateOf(false) }
    var time by remember { mutableStateOf("22:00") }
    var minutes by remember { mutableStateOf("30") }
    val zone = remember { ZoneId.systemDefault().id }
    var days by remember { mutableStateOf((0..6).toSet()) }
    var error by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Add schedule") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(pause, { pause = true }, label = { Text("Pause internet") })
                FilterChip(!pause, { pause = false }, label = { Text("Resume internet") })
            }
            ToggleRow("Repeat every week", null, recurring, { recurring = it })
            if (recurring) {
                OutlinedTextField(time, { time = it }, Modifier.fillMaxWidth(), label = { Text("Time (24-hour, e.g. 22:00)") }, singleLine = true)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("M", "T", "W", "T", "F", "S", "S").forEachIndexed { i, day ->
                        val on = i in days
                        Box(Modifier.weight(1f).aspectRatio(1f).clip(CircleShape)
                            .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { days = if (on) days - i else days + i }, contentAlignment = Alignment.Center) {
                            Text(day, style = MaterialTheme.typography.labelLarge, color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } else OutlinedTextField(minutes, { minutes = it }, Modifier.fillMaxWidth(), label = { Text("Start in (minutes)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            Text("Pausing or resuming a device by hand pauses its schedules. Missed actions older than five minutes are skipped after an outage.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = {
        runCatching {
            val action = if (pause) "block" else "unblock"
            if (recurring) {
                require(time.matches(Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]")) && days.isNotEmpty())
                ScheduleDraft(mac, action, zone, local_time = time, weekdays = days.sorted())
            } else {
                val delay = minutes.toLong(); require(delay in 1..525600)
                ScheduleDraft(mac, action, zone, run_at = Instant.now().plusSeconds(delay * 60).toString())
            }
        }.onSuccess(onSave).onFailure { error = "Enter a valid time and pick at least one day." }
    }) { Text("Save") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
fun EventRow(event: Event, deviceName: String?) {
    val colors = MaterialTheme.colorScheme
    val problem = "failed" in event.kind || "offline" in event.kind
    val tint = if (problem) colors.tertiary else colors.primary
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBadge(when { problem -> Icons.Default.Warning; "block" in event.kind -> Icons.Default.Shield; else -> Icons.Default.Sensors }, tint, 36.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(eventLabel(event.kind), style = MaterialTheme.typography.bodyMedium)
            deviceName?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
        }
        Text(timestamp(event.at), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
    }
}

@Composable
fun ActivityScreen(state: UiState, refresh: () -> Unit, more: () -> Unit) {
    var filter by remember { mutableStateOf("All") }
    val names = remember(state.devices) { state.devices.associate { it.mac to it.name } }
    val events = state.events.filter {
        when (filter) {
            "Devices" -> "connect" in it.kind || "discover" in it.kind || "unknown" in it.kind
            "Access" -> "block" in it.kind || "schedule" in it.kind
            "Network" -> "router" in it.kind
            else -> true
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { SectionTitle("Activity", "The last ${state.settings.retention_days} days on your network") }
        item {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("All", "Devices", "Access", "Network").forEach { label -> FilterChip(filter == label, { filter = label }, label = { Text(label) }) }
            }
        }
        item { TextButton(onClick = refresh) { Text("Refresh") } }
        if (events.isEmpty()) item { EmptyState(Icons.Default.History, "Nothing to show yet", "Activity appears here as your network is observed.") }
        items(events, key = { it.id }) { EventRow(it, it.mac?.let { mac -> names[mac] ?: mac }) }
        if (state.events.isNotEmpty()) item { TextButton(onClick = more, enabled = !state.busy) { Text("Load earlier events") } }
    }
}
