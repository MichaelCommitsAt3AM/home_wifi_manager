package com.homewifi.manager

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

private fun standardLabel(radio: RadioSettings, value: String) =
    radio.standards.firstOrNull { it.value == value }?.label ?: value

private fun channelLabel(channel: Int) = if (channel == 0) "Auto" else channel.toString()

@SuppressLint("MissingPermission")
private fun readScan(context: Context): List<NearbyNetwork> {
    val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    return try {
        wifi.scanResults.map {
            @Suppress("DEPRECATION") val ssid = it.SSID.orEmpty()
            NearbyNetwork(it.BSSID.orEmpty(), ssid, it.frequency, it.level)
        }
    } catch (_: SecurityException) { emptyList() }
}

@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
private fun connectedBssid(context: Context): String? = try {
    val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    wifi.connectionInfo?.bssid?.takeIf { it != "02:00:00:00:00:00" }
} catch (_: SecurityException) { null }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WifiRadioScreen(vm: WifiViewModel, state: UiState) {
    val context = LocalContext.current
    LaunchedEffect(state.authenticated) { if (state.authenticated) vm.loadRadio() }
    val radio = state.radio
    var standard by remember(radio?.standard) { mutableStateOf(radio?.standard.orEmpty()) }
    var channel by remember(radio?.channel, radio?.auto_channel) { mutableStateOf(if (radio?.auto_channel == true) 0 else radio?.channel ?: 0) }
    var confirm by remember { mutableStateOf(false) }

    var scans by remember { mutableStateOf<List<NearbyNetwork>?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var scanNote by remember { mutableStateOf<String?>(null) }
    var excluded by remember { mutableStateOf<String?>(null) }
    fun hasLocation() = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun startScan() {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (!wifi.isWifiEnabled) { scanNote = "Turn on Wi-Fi on this phone to scan."; return }
        excluded = connectedBssid(context)
        scanning = true; scanNote = null
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                runCatching { context.applicationContext.unregisterReceiver(this) }
                scanning = false
                val fresh = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
                scans = readScan(context)
                if (!fresh) scanNote = "Android limited this scan, so results may be a little old."
            }
        }
        ContextCompat.registerReceiver(context.applicationContext, receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
        @Suppress("DEPRECATION") val started = try { wifi.startScan() } catch (_: SecurityException) { false }
        if (!started) {
            runCatching { context.applicationContext.unregisterReceiver(receiver) }
            scanning = false
            scans = readScan(context)
            scanNote = "Android is limiting scans (about four every two minutes). Showing the latest results."
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startScan() else scanNote = "Location permission is needed: Android only shares nearby Wi-Fi names and signal with apps that have it. The app does not store or send your location."
    }

    if (confirm && radio != null) AlertDialog(onDismissRequest = { confirm = false },
        title = { Text("Restart Wi-Fi to apply?") },
        text = { Text("Wi-Fi will restart and every connected device, including this phone if it is on Wi-Fi, disconnects for about a minute. Devices that cannot use the new mode may not reconnect." +
            "\n\nMode: ${standardLabel(radio, standard)}\nChannel: ${channelLabel(channel)}") },
        confirmButton = { TextButton(onClick = {
            confirm = false
            vm.setRadio(standard.takeIf { it != radio.standard }, channel.takeIf { it != (if (radio.auto_channel) 0 else radio.channel) })
        }) { Text("Apply") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } })

    val report = remember(scans, excluded, radio?.channels) {
        scans?.let { analyzeChannels(it, (radio?.channels ?: (0..13).toList()).filter { c -> c in 1..13 }, excluded) }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SectionTitle("Wi-Fi radio", "Mode and channel of your router's 2.4 GHz network") }
        if (!state.authenticated) item { Text("Connect to your server in Settings first.", style = MaterialTheme.typography.bodyMedium) }
        else if (radio == null) item { Text(if (state.busy) "Reading the router…" else "Radio settings are not loaded yet. Pull Refresh or check that router access is active.", style = MaterialTheme.typography.bodyMedium) }
        else {
            item {
                SoftCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        DetailLine("Now", "${standardLabel(radio, radio.standard)} · channel ${if (radio.auto_channel) "auto (currently ${radio.channel})" else radio.channel}")
                        DetailLine("Channel width", when (radio.bandwidth) { "20" -> "20 MHz"; "40" -> "40 MHz"; "auto" -> "Auto 20/40 MHz"; else -> radio.bandwidth })
                        if (!radio.writes_enabled) Text("Changing these settings is switched off on the server until the first change has been validated on your router. You can still look and scan.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                ExpandableCard("Mode", standardLabel(radio, standard), initiallyExpanded = true) {
                    radio.standards.forEach { option ->
                        Row(Modifier.fillMaxWidth().selectable(standard == option.value, role = Role.RadioButton, onClick = { standard = option.value }),
                            verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(standard == option.value, null)
                            Text(option.label, Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp))
                        }
                    }
                    if (standard == "11n") Text("802.11n only: older 802.11b/g-only devices cannot connect.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (standard == "11b" || standard == "11g" || standard == "11bg") Text("This turns off 802.11n and its higher speeds.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            item {
                ExpandableCard("Channel", channelLabel(channel), initiallyExpanded = true) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        radio.channels.forEach { c ->
                            FilterChip(channel == c, { channel = c }, label = { Text(channelLabel(c)) },
                                trailingIcon = if (report?.best == c) ({ Text("★") }) else null)
                        }
                    }
                    Text("Channels 1, 6 and 11 do not overlap each other. ★ marks the quietest channel from your last scan.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                val unchanged = standard == radio.standard && channel == (if (radio.auto_channel) 0 else radio.channel)
                Button(onClick = { confirm = true }, enabled = !state.busy && !unchanged && radio.writes_enabled, modifier = Modifier.fillMaxWidth()) { Text("Apply changes") }
            }
            item {
                SoftCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Channel analyzer", style = MaterialTheme.typography.titleSmall)
                        Text("Scans nearby Wi-Fi networks from this phone and shows which 2.4 GHz channels are least crowded. Run it while you are near the router.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(onClick = { if (hasLocation()) startScan() else permission.launch(Manifest.permission.ACCESS_FINE_LOCATION) }, enabled = !scanning) {
                            Text(if (scanning) "Scanning…" else if (scans == null) "Scan nearby networks" else "Scan again")
                        }
                        scanNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        report?.let { r ->
                            val seen = scans.orEmpty().count { it.channel != null }
                            Text("$seen nearby 2.4 GHz networks" + if (excluded != null) " (your connected network is not counted)" else "", style = MaterialTheme.typography.bodySmall)
                            r.loads.forEach { load ->
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(load.channel.toString().padStart(2), Modifier.width(24.dp), style = MaterialTheme.typography.labelMedium)
                                    Box(Modifier.weight(1f).height(10.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(5.dp))) {
                                        val fraction = if (r.maxScore <= 0.0) 0f else (load.score / r.maxScore).toFloat().coerceIn(0f, 1f)
                                        if (fraction > 0f) Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(
                                            if (load.channel == r.best) onlineColor() else MaterialTheme.colorScheme.primary, RoundedCornerShape(5.dp)))
                                    }
                                    Text("${load.networks}", Modifier.width(24.dp), style = MaterialTheme.typography.labelMedium)
                                }
                            }
                            Text("Bar = how much nearby traffic overlaps the channel; number = networks directly on it.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            r.best?.let { best ->
                                Text("Quietest: channel $best", style = MaterialTheme.typography.titleSmall)
                                if (best != (if (radio.auto_channel) 0 else radio.channel)) OutlinedButton(onClick = { channel = best }) { Text("Select channel $best") }
                                else Text("That is already your channel.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}
