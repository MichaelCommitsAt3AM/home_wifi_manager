package com.homewifi.manager

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.*
import dagger.hilt.android.AndroidEntryPoint
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { val vm: WifiViewModel = hiltViewModel(); val state by vm.state.collectAsStateWithLifecycle()
            val dark = state.theme == "dark" || (state.theme == "system" && isSystemInDarkTheme())
            val context = LocalContext.current
            val colors = if (state.dynamicColors && Build.VERSION.SDK_INT >= 31) {
                if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            } else if (dark) NetworkDarkColors else NetworkLightColors
            MaterialTheme(colorScheme = colors, typography = NetworkTypography, shapes = NetworkShapes) { Surface(Modifier.fillMaxSize(), color = colors.background) { WifiApp(vm, state) } }
        }
    }
}

fun timestamp(value: String?): String = if (value.isNullOrBlank()) "Not available" else runCatching {
    DateTimeFormatter.ofPattern("dd MMM, HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(value))
}.getOrDefault(value.take(19))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WifiApp(vm: WifiViewModel, state: UiState) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: "dashboard"
    val secondary = route.startsWith("device/") || route == "activity"
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); vm.dismiss() } }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { TopAppBar(title = { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!secondary) Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = .12f)) { Icon(Icons.Default.Router, null, Modifier.padding(8.dp).size(21.dp), tint = MaterialTheme.colorScheme.primary) }
            Text(if (route.startsWith("device/")) "Device details" else if(route == "activity") "Activity" else "Home WiFi", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            navigationIcon = { if (secondary) IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = { if (state.authenticated) IconButton(onClick = { vm.refresh(true) }, enabled = !state.loading && !state.busy) { Icon(Icons.Default.Refresh, "Synchronize with router") } }) },
        bottomBar = { if (!secondary) NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
            listOf(Triple("dashboard", "Overview", Icons.Default.Dashboard), Triple("devices", "Devices", Icons.Default.Devices),
                Triple("blocked", "Blocked", Icons.Default.Shield), Triple("settings", "Settings", Icons.Default.Settings)).forEach { (path, label, icon) ->
                NavigationBarItem(selected = route == path, onClick = { nav.navigate(path) { popUpTo("dashboard") { saveState = true }; launchSingleTop = true; restoreState = true } }, icon = { Icon(icon, label, Modifier.size(22.dp)) }, label = { Text(label, maxLines = 1, fontSize = 10.sp) }, colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = .12f), selectedIconColor = MaterialTheme.colorScheme.primary, selectedTextColor = MaterialTheme.colorScheme.primary, unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant))
            }
        } }, snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.loading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = "Loading network data" })
            AnimatedVisibility(state.error != null) { Surface(color = MaterialTheme.colorScheme.errorContainer) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(state.error.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { vm.refresh() }) { Text("Retry") }
                    IconButton(onClick = vm::dismiss) { Icon(Icons.Default.Close, "Dismiss error") }
                }
            } }
            if ((state.cached || state.dashboard.stale) && state.devices.isNotEmpty() && route != "settings") {
                Surface(color = MaterialTheme.colorScheme.tertiaryContainer) { Text(if(state.settings.router_access_paused) "Router access released · Resume in Settings" else "Saved observations · last sync ${timestamp(state.dashboard.last_sync)}", Modifier.fillMaxWidth().padding(12.dp), style = MaterialTheme.typography.labelMedium) }
            }
            NavHost(nav, startDestination = "dashboard", modifier = Modifier.weight(1f)) {
                composable("dashboard") { Overview(state, onDevices = { nav.navigate("devices") }, onSettings = { nav.navigate("settings") }, onRefresh = { vm.refresh(true) }) }
                composable("devices") { DeviceList(state, false, { nav.navigate("device/$it") }, { vm.refresh(true) }) }
                composable("blocked") { DeviceList(state, true, { nav.navigate("device/$it") }, { vm.refresh(true) }) }
                composable("activity") { LaunchedEffect(Unit) { if (state.authenticated) vm.activity() }; ActivityScreen(state, { vm.activity() }, { vm.activity(true) }) }
                composable("settings") { SettingsScreen(vm, state, onActivity = { nav.navigate("activity") }) }
                composable("device/{mac}") { backStack ->
                    val mac = backStack.arguments?.getString("mac").orEmpty()
                    LaunchedEffect(mac) { vm.history(mac) }
                    DeviceDetails(vm, state, mac)
                }
            }
        }
    }
}

@Composable
fun SectionTitle(title: String, subtitle: String? = null) {
    Column(Modifier.padding(vertical = 8.dp)) { Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
}
@Composable
fun EmptyState(icon: ImageVector, title: String, subtitle: String, action: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium)
        action?.invoke()
    }
}
@Composable
fun Skeleton() {
    val transition = rememberInfiniteTransition(label = "Loading")
    val alpha by transition.animateFloat(0.2f, 0.65f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "Pulse")
    Surface(Modifier.fillMaxWidth().height(80.dp), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha)) {}
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceList(state: UiState, blockedOnly: Boolean, onSelect: (String) -> Unit, refresh: () -> Unit) {
    var search by remember { mutableStateOf("") }; var filter by remember { mutableStateOf(if (blockedOnly) "Blocked" else "All") }; var sort by remember { mutableStateOf("Status") }
    val selected = selectDevices(state.devices, search, filter, sort)
    PullToRefreshBox(isRefreshing = state.loading, onRefresh = refresh) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { SectionTitle(if (blockedOnly) "Access restrictions" else "Your devices", if (blockedOnly) "Restrictions read from the router’s MAC blacklists" else "Router observations · randomized MACs may appear as new devices") }
            item { OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(), placeholder = { Text("Search name, IP or MAC") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true, shape = RoundedCornerShape(20.dp)) }
            if (!blockedOnly) item { Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("All", "Online", "Offline", "Trusted", "Unknown").forEach { label -> FilterChip(selected = filter == label, onClick = { filter = label }, label = { Text(label, fontSize = 12.sp) }, shape = CircleShape) } } }
            item { Row(verticalAlignment = Alignment.CenterVertically) { Text("${selected.size} devices", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                TextButton(onClick = { sort = when(sort) { "Status" -> "Name"; "Name" -> "Recent"; else -> "Status" } }) { Icon(Icons.Default.Sort, null); Text(sort) } } }
            if (selected.isEmpty()) item { EmptyState(if (blockedOnly) Icons.Default.VerifiedUser else Icons.Default.Devices, if (blockedOnly) "No restrictions found" else "No matching devices", "Refresh after connecting the backend, or adjust your search.") }
            items(selected, key = { it.mac }) { DeviceRow(it, { onSelect(it.mac) }) }
        }
    }
}
@Composable
fun DeviceRow(device: Device, click: () -> Unit) {
    Surface(onClick = click, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(deviceIcon(device), null, Modifier.size(23.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(device.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text(device.ip ?: "No IP reported", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if(device.protected) Icon(Icons.Default.Lock, "Protected device", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if(device.blocked) "Blocked" else if(device.online) "Online" else "Offline", style = MaterialTheme.typography.labelMedium, color = if(device.online && !device.blocked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
@Composable
fun DeviceDetails(vm: WifiViewModel, state: UiState, mac: String) {
    val device = state.devices.firstOrNull { it.mac == mac }
    var confirm by remember { mutableStateOf(false) }; var rename by remember { mutableStateOf(false) }; var alias by remember(device?.alias) { mutableStateOf(device?.alias.orEmpty()) }; var schedule by remember { mutableStateOf(false) }
    if (device == null) { EmptyState(Icons.Default.Devices, "Device unavailable", "Refresh the device list and try again."); return }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text(if (device.blocked) "Remove router restrictions?" else "Block this device?") },
        text = { Text("${device.name}\n${device.mac}\n\n${if (device.blocked) "Remove matching MAC blacklist entries." else "The router will restrict this MAC on its current connection. MAC filtering does not prevent spoofing."}\nActive access schedules will be paused after this manual action.") },
        confirmButton = { Button(onClick = { confirm = false; vm.access(device) }) { Text("Confirm") } }, dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } })
    if (rename) AlertDialog(onDismissRequest = { rename = false }, title = { Text("Friendly name") }, text = { OutlinedTextField(alias, { alias = it.take(80) }, label = { Text("Device name") }) }, confirmButton = { TextButton(onClick = { vm.patch(mac, DevicePatch(alias = alias)); rename = false }) { Text("Save") } }, dismissButton = { TextButton(onClick = { rename = false }) { Text("Cancel") } })
    if (schedule) ScheduleDialog(mac, onDismiss = { schedule = false }, onSave = { vm.schedule(it); schedule = false })
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { SectionTitle(device.name, "${device.connection_type} · ${if(device.online) "Online" else "Offline"}") }
        item { OutlinedCard { Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DetailLine("IP address", device.ip ?: "Not reported"); DetailLine("MAC address", device.mac)
            DetailLine("Hostname", device.hostname.ifBlank { "Not reported" }); DetailLine("Device type", device.device_type.ifBlank { "Not reported" })
            DetailLine("Port", device.port); DetailLine("First observed", timestamp(device.first_seen)); DetailLine("Last online", timestamp(device.last_seen))
            DetailLine("Router restriction", if (device.blocked) device.restriction_scope else "No blacklist entry")
            Text("A Wi-Fi restriction applies to its listed SSID; access through another interface or changed MAC may differ.", style = MaterialTheme.typography.bodySmall)
        } } }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton(onClick = { rename = true }) { Text("Rename") }; OutlinedButton(onClick = { vm.patch(mac, DevicePatch(trusted = !device.trusted)) }, enabled = !state.busy) { Text(if(device.trusted) "Mark unknown" else "Trust device") } } }
        item { Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("Protect from blocking"); Text("Local safety setting", style = MaterialTheme.typography.bodySmall) }; Switch(device.protected, { vm.patch(mac, DevicePatch(protected = it)) }, enabled = !state.busy) } }
        item { Button(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth(), enabled = state.settings.writes_enabled && !state.cached && !state.dashboard.stale && !state.busy && (!device.protected || device.blocked)) { Icon(Icons.Default.Shield, null); Spacer(Modifier.width(8.dp)); Text(if(device.blocked) "Unblock device" else "Block device") } }
        if (!state.settings.writes_enabled) item { Text(if(state.settings.router_access_paused) "Resume router access in Settings to manage devices." else "Router changes are disabled until the safe-device validation is complete.", style = MaterialTheme.typography.bodySmall) }
        item { SectionTitle("Access schedules", "Executed by the server, even when this app is closed") }
        item { OutlinedButton(onClick = { schedule = true }, enabled = state.settings.writes_enabled && !device.protected && !state.busy) { Icon(Icons.Default.Schedule, null); Text("Add schedule") } }
        items(state.schedules, key = { it.id }) { item -> OutlinedCard { Column(Modifier.fillMaxWidth().padding(14.dp)) { Text("${item.action.replaceFirstChar { it.uppercase() }} · ${timestamp(item.next_run)}"); Text("${item.timezone} · ${item.status}", style = MaterialTheme.typography.bodySmall); if(item.enabled) TextButton(onClick = { vm.cancel(item) }) { Text("Cancel schedule") } } } }
        item { SectionTitle("Connection & access history") }
        if (state.history.isEmpty()) item { Text("No recorded activity yet.") }
        items(state.history, key = { it.id }) { EventRow(it) }
    }
}
@Composable
fun DetailLine(label: String, value: String) { Column { Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(value.ifBlank { "Not reported" }, style = MaterialTheme.typography.bodyMedium) } }
@Composable
fun ScheduleDialog(mac: String, onDismiss: () -> Unit, onSave: (ScheduleDraft) -> Unit) {
    var block by remember { mutableStateOf(true) }; var recurring by remember { mutableStateOf(false) }; var time by remember { mutableStateOf("22:00") }; var minutes by remember { mutableStateOf("30") }; var zone by remember { mutableStateOf(ZoneId.systemDefault().id) }; var days by remember { mutableStateOf((0..6).toSet()) }; var error by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Access schedule") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row { FilterChip(block, { block = true }, label = { Text("Block") }); Spacer(Modifier.width(8.dp)); FilterChip(!block, { block = false }, label = { Text("Unblock") }) }
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Repeat weekly", Modifier.weight(1f)); Switch(recurring, { recurring = it }) }
        if (recurring) {
            OutlinedTextField(time, { time = it }, label = { Text("Time (HH:mm)") }, singleLine = true)
            OutlinedTextField(zone, { zone = it }, label = { Text("Timezone") }, singleLine = true)
            Row { listOf("M", "T", "W", "T", "F", "S", "S").forEachIndexed { i, day -> TextButton(onClick = { days = if(i in days) days - i else days + i }, modifier = Modifier.weight(1f).widthIn(min = 0.dp), contentPadding = PaddingValues(0.dp)) { Text(day, color = if(i in days) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline) } } }
        } else OutlinedTextField(minutes, { minutes = it }, label = { Text("Run in minutes") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        Text("Manual block/unblock pauses active schedules. Missed actions older than five minutes are skipped after an outage.", style = MaterialTheme.typography.bodySmall)
        if(error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    } }, confirmButton = { TextButton(onClick = {
        runCatching {
            if(recurring) { require(time.matches(Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]")) && days.isNotEmpty()); ZoneId.of(zone)
                ScheduleDraft(mac, if(block) "block" else "unblock", zone, local_time = time, weekdays = days.sorted())
            } else { val delay = minutes.toLong(); require(delay in 1..525600); ScheduleDraft(mac, if(block) "block" else "unblock", zone, run_at = Instant.now().plusSeconds(delay * 60).toString()) }
        }.onSuccess(onSave).onFailure { error = "Enter a valid time, timezone, and at least one day." }
    }) { Text("Save") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
@Composable
fun EventRow(event: Event) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(11.dp), verticalAlignment = Alignment.CenterVertically) {
        val tint = if("failed" in event.kind || "offline" in event.kind) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
        Surface(shape = CircleShape, color = tint.copy(alpha = .08f)) { Icon(when { "failed" in event.kind || "offline" in event.kind -> Icons.Default.Warning; "block" in event.kind -> Icons.Default.Shield; else -> Icons.Default.Sensors }, null, Modifier.padding(9.dp).size(16.dp), tint = tint) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) { Text(eventLabel(event.kind), style = MaterialTheme.typography.bodyMedium); event.mac?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        Text(timestamp(event.at), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable
fun ActivityScreen(state: UiState, refresh: () -> Unit, more: () -> Unit) {
    var filter by remember { mutableStateOf("All") }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { SectionTitle("Network timeline", "Up to ${state.settings.retention_days} days of history") }
        item { Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("All", "Connections", "Access", "Router").forEach { label -> FilterChip(filter == label, { filter = label }, label = { Text(label, fontSize = 11.sp) }) } } }
        item { TextButton(onClick = refresh) { Text("Refresh activity") } }
        val events = state.events.filter { when(filter) { "Connections" -> "connect" in it.kind || "discover" in it.kind; "Access" -> "block" in it.kind || "schedule" in it.kind; "Router" -> "router" in it.kind; else -> true } }
        if(events.isEmpty()) item { EmptyState(Icons.Default.History, "No events to show", "Activity appears as the server observes your network.") }
        items(events, key = { it.id }) { EventRow(it) }
        if(state.events.isNotEmpty()) item { TextButton(onClick = more, enabled = !state.busy) { Text("Load earlier events") } }
    }
}
@Composable
fun SettingsSection(title: String, initiallyExpanded: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
        Column {
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Icon(if(expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, if(expanded) "Collapse $title" else "Expand $title", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if(expanded) Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
        }
    }
}

@Composable
fun SettingsScreen(vm: WifiViewModel, state: UiState, onActivity: () -> Unit) {    var releaseConfirmation by remember { mutableStateOf(false) }    if(releaseConfirmation) AlertDialog(onDismissRequest = { releaseConfirmation = false },        title = { Text("Release router access?") },        text = { Text("Logs the home server out so a technician can use the router dashboard. Router monitoring and controls stay paused until you resume, including after a server restart. Missed scheduled actions will be skipped. AdGuard monitoring continues.") },        confirmButton = { TextButton(onClick = { releaseConfirmation = false; vm.routerAccess(true) }) { Text("Release access") } },        dismissButton = { TextButton(onClick = { releaseConfirmation = false }) { Text("Cancel") } })
    var address by remember { mutableStateOf(vm.config.url) }
    var token by remember { mutableStateOf("") }
    var poll by remember(state.settings.poll_seconds) { mutableStateOf(state.settings.poll_seconds.toString()) }
    var notifications by remember { mutableStateOf(vm.config.notifications) }
    var categories by remember(state.settings.notification_categories) { mutableStateOf(state.settings.notification_categories.toSet()) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> notifications = granted; vm.config.notifications = granted }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { SectionTitle("Preferences") }        if(state.authenticated) item { SettingsSection("Router access", initiallyExpanded = state.settings.router_access_paused) {            Text(if(state.settings.router_access_paused) "Server monitoring paused" else "Server monitoring active", style = MaterialTheme.typography.titleSmall)            Text(if(state.settings.router_access_paused && state.settings.router_logout_verified) "The server is logged out. You can use the router dashboard until you resume here." else if(state.settings.router_access_paused) "Automatic login is paused, but logout could not be confirmed. An old router session may need to expire." else "Release the server's dashboard session when a technician needs access.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)            if(!state.settings.router_access_paused || !state.settings.router_logout_verified) OutlinedButton(onClick = { releaseConfirmation = true }, enabled = !state.busy) { Text(if(state.settings.router_access_paused) "Retry logout" else "Release router access") }            if(state.settings.router_access_paused) Button(onClick = { vm.routerAccess(false) }, enabled = !state.busy) { Text("Resume router access") }        } }
        item { SettingsSection("Connection", initiallyExpanded = !state.authenticated) {
            OutlinedTextField(address, { address = it }, Modifier.fillMaxWidth(), label = { Text("Backend HTTPS address") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth(), label = { Text("API token") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.connect(address.trim(), token.ifBlank { vm.config.token }); token = "" }, enabled = !state.busy) { Text("Save & test") }
                if(state.authenticated) TextButton(onClick = vm::logout) { Text("Disconnect") }
            }
        } }
        item { SettingsSection("Appearance") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("system", "light", "dark").forEach { theme -> FilterChip(state.theme == theme, { vm.theme(theme) }, label = { Text(theme.replaceFirstChar { it.uppercase() }) }) } }
            if(Build.VERSION.SDK_INT >= 31) Row(verticalAlignment = Alignment.CenterVertically) { Text("Use wallpaper colors", Modifier.weight(1f)); Switch(state.dynamicColors, vm::dynamicColors) }
        } }
        if(state.authenticated) {
            item { SettingsSection("DNS protection") { AdGuardCard(state.dashboard.adguard, expanded = true) } }
        }
        item { SettingsSection("Monitoring & notifications") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Background notifications", Modifier.weight(1f))
                Switch(notifications, { enabled -> notifications = enabled; vm.config.notifications = enabled; if(enabled && Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS) })
            }
            Text("Background checks may be delayed by Android.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if(state.authenticated) {
                OutlinedTextField(poll, { poll = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(), label = { Text("Polling interval (seconds)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = poll.toIntOrNull() !in 30..3600)
                listOf("unknown_device", "router_offline", "router_restored", "schedule_executed", "schedule_failed", "management_failed").forEach { kind ->
                    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(kind in categories, { selected -> categories = if(selected) categories + kind else categories - kind }); Text(eventLabel(kind), style = MaterialTheme.typography.bodyMedium) }
                }
                Button(onClick = { vm.preferences(poll.toInt(), categories.toList()) }, enabled = poll.toIntOrNull() in 30..3600 && !state.busy) { Text("Save preferences") }
            }
        } }
        if(state.authenticated) {
            item { Surface(onClick = onActivity, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
                Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Network activity", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    Icon(Icons.Default.ChevronRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } }
            item { SettingsSection("Diagnostics") {
                DetailLine("Last sync", timestamp(state.dashboard.last_sync))
                DetailLine("Known devices", state.dashboard.total.toString())
                DetailLine("Unknown online", state.dashboard.unknown.toString())
                DetailLine("Backend", if(state.cached) "Connection unavailable or unverified" else "Connected over HTTPS")
                DetailLine("Router", "${state.dashboard.router.model} ${state.dashboard.router.firmware}")
                DetailLine("Resources", "CPU ${state.dashboard.router.cpu.ifBlank { "unavailable" }} · Memory ${state.dashboard.router.memory.ifBlank { "unavailable" }}")
                DetailLine("Router changes", if(state.settings.writes_enabled) "Enabled after validation" else "Disabled pending validation")
                DetailLine("Push relay", if(state.settings.ntfy_configured) "Configured" else "Not configured")
                DetailLine("Version", BuildConfig.VERSION_NAME)
            } }
        }
    }
}
