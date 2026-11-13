package com.homewifi.manager

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import dagger.hilt.android.AndroidEntryPoint
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: WifiViewModel = hiltViewModel()
            val state by vm.state.collectAsStateWithLifecycle()
            val dark = state.theme == "dark" || (state.theme == "system" && isSystemInDarkTheme())
            val context = LocalContext.current
            val colors = if (state.dynamicColors && Build.VERSION.SDK_INT >= 31) {
                if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            } else if (dark) NetworkDarkColors else NetworkLightColors
            MaterialTheme(colorScheme = colors, typography = NetworkTypography, shapes = NetworkShapes) {
                Surface(Modifier.fillMaxSize(), color = colors.background) { WifiApp(vm, state) }
            }
        }
    }
}

fun timestamp(value: String?): String = if (value.isNullOrBlank()) "Not available" else runCatching {
    DateTimeFormatter.ofPattern("dd MMM, HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(value))
}.getOrDefault(value.take(19))

private data class Tab(val path: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val tabs = listOf(
    Tab("dashboard", "Home", Icons.Default.Home),
    Tab("devices", "Devices", Icons.Default.Devices),
    Tab("settings", "Settings", Icons.Default.Settings)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WifiApp(vm: WifiViewModel, state: UiState, updates: UpdateViewModel = hiltViewModel()) {
    val update by updates.state.collectAsStateWithLifecycle()
    val updatePreview by updates.preview.collectAsStateWithLifecycle()
    val updateContext = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { updates.checkAutomatically() }
    val nav = rememberNavController()
    val openTab: (String) -> Unit = { path ->
        nav.navigate(path) {
            popUpTo("dashboard") { saveState = true }
            launchSingleTop = true
            // Only Settings keeps its saved state; Home must not restore a tab popped above it,
            // and Devices should reopen with the filter that was asked for.
            restoreState = path == "settings"
        }
    }
    val entry by nav.currentBackStackEntryAsState()
    val route = (entry?.destination?.route ?: "dashboard").substringBefore('?')
    val secondary = route.startsWith("device/") || route == "activity" || route == "health" || route == "wifi"
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); vm.dismiss() } }
    val title = when {
        route.startsWith("device/") -> "Device"
        route == "activity" -> "Activity"
        route == "health" -> "Internet"
        route == "wifi" -> "Wi-Fi radio"
        else -> "Home WiFi"
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column(Modifier.windowInsetsPadding(TopAppBarDefaults.windowInsets)) {
                AnimatedVisibility(updatePreview || (update.release != null && !update.dismissed)) {
                    UpdateBanner(update, updatePreview, onAction = {
                        if (updatePreview) android.widget.Toast.makeText(updateContext, "Banner preview only — no update will be downloaded", android.widget.Toast.LENGTH_SHORT).show()
                        else if (update.stage == "ready") updates.install() else updates.download()
                    }, onDismiss = updates::dismiss)
                }
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (!secondary) IconBadge(Icons.Default.Wifi, size = 34.dp)
                            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                    navigationIcon = { if (secondary) IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                    actions = { if (state.authenticated) IconButton(onClick = { vm.refresh(true) }, enabled = !state.loading && !state.busy) { Icon(Icons.Default.Refresh, "Refresh") } },
                    windowInsets = WindowInsets(0, 0, 0, 0)
                )
            }
        },
        bottomBar = {
            if (!secondary) NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = route == tab.path, onClick = { openTab(tab.path) },
                        icon = { Icon(tab.icon, tab.label, Modifier.size(24.dp)) }, label = { Text(tab.label, maxLines = 1) },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer, selectedTextColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant, unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant)
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.loading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = "Loading network data" })
            AnimatedVisibility(state.error != null) {
                Surface(color = MaterialTheme.colorScheme.errorContainer) {
                    Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(state.error.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                        TextButton(onClick = { vm.refresh() }) { Text("Retry") }
                        IconButton(onClick = vm::dismiss) { Icon(Icons.Default.Close, "Dismiss error") }
                    }
                }
            }
            if ((state.cached || state.dashboard.stale) && state.devices.isNotEmpty() && route != "settings") {
                Surface(color = MaterialTheme.colorScheme.tertiaryContainer) {
                    Text(if (state.settings.router_access_paused) "Monitoring paused · Resume it in Settings > Advanced" else "Showing saved info · last updated ${timestamp(state.dashboard.last_sync)}",
                        Modifier.fillMaxWidth().padding(12.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
                }
            }
            NavHost(nav, startDestination = "dashboard", modifier = Modifier.weight(1f)) {
                composable("dashboard") {
                    Overview(state, onDevices = { openTab("devices") }, onSettings = { openTab("settings") }, onRefresh = { vm.refresh(true) },
                        onHealth = { nav.navigate("health") }, onPaused = { openTab("devices?filter=Paused") })
                }
                composable("devices?filter={filter}", arguments = listOf(navArgument("filter") { type = NavType.StringType; defaultValue = "All" })) { backStack ->
                    DeviceList(state, backStack.arguments?.getString("filter") ?: "All", { nav.navigate("device/$it") }, { vm.refresh(true) })
                }
                composable("activity") {
                    LaunchedEffect(Unit) { if (state.authenticated) vm.activity() }
                    ActivityScreen(state, { vm.activity() }, { vm.activity(true) })
                }
                composable("settings") {
                    SettingsScreen(vm, state, onActivity = { nav.navigate("activity") }, onHealth = { nav.navigate("health") }, onWifi = { nav.navigate("wifi") },
                        updatePreview = updatePreview, onPreviewUpdate = updates::preview, updateState = update, onCheckUpdates = updates::check)
                }
                composable("health") { ConnectionHealthScreen(vm, state) }
                composable("wifi") { WifiRadioScreen(vm, state) }
                composable("device/{mac}") { backStack ->
                    val mac = backStack.arguments?.getString("mac").orEmpty()
                    LaunchedEffect(mac) { vm.history(mac) }
                    DeviceDetails(vm, state, mac)
                }
            }
        }
    }
}
