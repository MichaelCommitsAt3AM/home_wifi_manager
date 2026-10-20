package com.homewifi.manager

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.NumberFormat

val NetworkDarkColors = darkColorScheme(
    primary = Color(0xFF7DE3C0), onPrimary = Color(0xFF052F26), primaryContainer = Color(0xFF153E34), onPrimaryContainer = Color(0xFFB9F5DF),
    secondary = Color(0xFFA8BED5), secondaryContainer = Color(0xFF203143), onSecondaryContainer = Color(0xFFDCEAF6),
    tertiary = Color(0xFFE6C38A), tertiaryContainer = Color(0xFF453923), onTertiaryContainer = Color(0xFFFFDFAB),
    background = Color(0xFF0C121B), onBackground = Color(0xFFEDF2F8), surface = Color(0xFF141E2B),
    onSurface = Color(0xFFEDF2F8), surfaceVariant = Color(0xFF202D3C), onSurfaceVariant = Color(0xFF92A5BA),
    outline = Color(0xFF3B4D60), outlineVariant = Color(0xFF233242), surfaceContainer = Color(0xFF141E2B),
    surfaceContainerLow = Color(0xFF111A25), surfaceContainerHigh = Color(0xFF1C2938)
)
val NetworkLightColors = lightColorScheme(
    primary = Color(0xFF087C60), onPrimary = Color.White, primaryContainer = Color(0xFFD6F5E8), onPrimaryContainer = Color(0xFF074D3B),
    secondary = Color(0xFF45627B), secondaryContainer = Color(0xFFE1ECF5), onSecondaryContainer = Color(0xFF243D54),
    background = Color(0xFFF5F8FA), onBackground = Color(0xFF162C3B), surface = Color.White,
    onSurface = Color(0xFF162C3B), surfaceVariant = Color(0xFFEAF0F5), onSurfaceVariant = Color(0xFF64798A),
    outline = Color(0xFFB3C2CD), outlineVariant = Color(0xFFDFE7EC), surfaceContainer = Color.White,
    surfaceContainerLow = Color(0xFFF0F5F8), surfaceContainerHigh = Color(0xFFEAF0F5)
)
val NetworkTypography = Typography(
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 38.sp, letterSpacing = (-1).sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 27.sp, lineHeight = 33.sp, letterSpacing = (-.6).sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 21.sp, lineHeight = 27.sp, letterSpacing = (-.4).sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 18.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 10.sp, lineHeight = 14.sp)
)
val NetworkShapes = Shapes(extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(28.dp))

@Composable
fun StatusPill(label: String, active: Boolean, modifier: Modifier = Modifier) {
    val tint = if(active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(modifier.background(tint.copy(alpha = .09f), CircleShape).padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(5.dp).background(tint, CircleShape))
        Text(label, style = MaterialTheme.typography.labelMedium, color = tint)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Overview(state: UiState, onDevices: () -> Unit, onSettings: () -> Unit, onRefresh: () -> Unit, onHealth: () -> Unit = {}) {
    PullToRefreshBox(isRefreshing = state.loading, onRefresh = onRefresh) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            item { Text("Your home network", style = MaterialTheme.typography.headlineMedium) }
            if (!state.authenticated) item {
                EmptyState(Icons.Default.Router, "Welcome home", "Connect your private backend to see your network.") { Button(onClick = onSettings) { Text("Set up connection") } }
            }
            if (state.loading && state.devices.isEmpty()) item { Skeleton() }
            if (state.authenticated || state.devices.isNotEmpty()) {
                item {
                    Card(onClick = onDevices, shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
                            StatusPill(if(state.settings.router_access_paused) "Access released" else if(state.dashboard.router_online == true) "Router online" else if(state.dashboard.router_online == false) "Router unavailable" else "Awaiting sync", state.dashboard.router_online == true && !state.settings.router_access_paused)
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(state.dashboard.connected.toString(), style = MaterialTheme.typography.headlineLarge, fontSize = 52.sp, lineHeight = 56.sp, color = MaterialTheme.colorScheme.primary)
                                Text(if(state.settings.router_access_paused) "last known connected" else "devices connected", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("View devices", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                                Icon(Icons.Default.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                if (state.dashboard.adguard.configured) {
                    item { AdGuardCard(state.dashboard.adguard, onDetails = onSettings) }
                }
                item { TextButton(onClick = onHealth) {
                    Icon(Icons.Default.NetworkCheck, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                    Text(if(state.connection.stale) "Connection health · checking" else healthLabel(state.connection.state))
                } }
                item { Text("Updated ${timestamp(state.dashboard.last_sync)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
fun AdGuardCard(status: AdGuardStatus, expanded: Boolean = false, onDetails: () -> Unit = {}) {
    val healthy = status.online == true && status.running && status.protection_enabled && status.filtering_enabled
    val tint = if(healthy) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    if (!expanded) {
        Card(onClick = onDetails, shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Icon(Icons.Default.VerifiedUser, null, Modifier.size(24.dp), tint = tint)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("AdGuard Home", style = MaterialTheme.typography.titleSmall)
                    Text(if(healthy) "Protection active" else if(status.authentication_required) "Sign-in needed" else if(!status.configured) "Not connected" else if(status.online == true && !status.protection_enabled) "Protection paused" else "Needs attention", style = MaterialTheme.typography.bodySmall, color = tint)
                }
                Icon(Icons.Default.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }
    Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().border(1.dp, if(healthy) tint.copy(alpha = .22f) else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(22.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                Surface(shape = RoundedCornerShape(13.dp), color = tint.copy(alpha = .1f)) { Icon(Icons.Default.VerifiedUser, null, Modifier.padding(10.dp).size(21.dp), tint = tint) }
                Column(Modifier.weight(1f)) { Text("AdGuard Home", style = MaterialTheme.typography.titleSmall)
                    Text(if(healthy) "DNS protection active" else if(status.authentication_required) "Dashboard login required" else if(!status.configured) "Connect your DNS protection" else if(status.online == true && !status.protection_enabled) "Protection paused" else "Protection needs attention", style = MaterialTheme.typography.bodySmall, color = tint) }
                Icon(if(healthy) Icons.Default.CheckCircle else Icons.Default.Info, null, Modifier.size(18.dp), tint = tint)
            }
            if(status.online == true) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth()) {
                    ProtectionMetric("Blocked", compactNumber(status.blocked_queries), Modifier.weight(1f))
                    ProtectionMetric("Queries", compactNumber(status.queries), Modifier.weight(1f))
                    ProtectionMetric("Blocked rate", "${status.blocked_percent}%", Modifier.weight(1f))
                }
                LinearProgressIndicator(progress = { (status.blocked_percent / 100).toFloat().coerceIn(0f,1f) }, modifier = Modifier.fillMaxWidth().height(4.dp), color = tint, trackColor = tint.copy(alpha = .09f))
            }
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Icon(if(status.blocking_observed == true && status.normal_resolution) Icons.Default.CheckCircleOutline else Icons.Default.Info, null, Modifier.size(14.dp), tint = tint)
                Text(if(status.blocking_observed == true && status.normal_resolution) "Server DNS test passed · ads blocked, normal DNS resolves" else if(!status.configured) "AdGuard integration is being configured on the server." else if(status.online == true && !status.protection_enabled) "Protection is paused; DNS still resolves normally." else "Server DNS probe has not confirmed filtering yet.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if(expanded) {
                DetailLine("Last checked", timestamp(status.checked_at))
                DetailLine("Filter lists", "${status.enabled_filters} enabled · ${compactNumber(status.rules_count)} rules")
                DetailLine("Average DNS time", "${status.avg_processing_ms} ms")
                DetailLine("Statistics", "Configured AdGuard statistics window · ${status.observed_clients} observed clients")
                Text("These checks verify AdGuard and the server’s DNS path. Private DNS, VPNs, and browser encrypted DNS can bypass it on individual devices.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
@Composable private fun ProtectionMetric(label: String, value: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) { Text(value, style = MaterialTheme.typography.titleMedium); Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}
fun compactNumber(value: Long): String = when { value >= 1_000_000 -> "%.1fM".format(value / 1_000_000.0); value >= 10_000 -> "%.1fk".format(value / 1000.0); else -> NumberFormat.getIntegerInstance().format(value) }

fun deviceIcon(device: Device): ImageVector {
    val name = (device.name + " " + device.device_type).lowercase()
    return when {
        "tv" in name || "television" in name -> Icons.Default.Tv
        "phone" in name || "android" in name || "oppo" in name || "huawei" in name -> Icons.Default.Smartphone
        "server" in name -> Icons.Default.Dns
        "printer" in name || "laserjet" in name -> Icons.Default.Print
        device.connection_type == "wireless" -> Icons.Default.Wifi
        else -> Icons.Default.Computer
    }
}
fun eventLabel(kind: String): String = when(kind) {
    "device_discovered" -> "New device discovered"
    "device_connected" -> "Device connected"
    "device_disconnected" -> "Device went offline"
    "unknown_device" -> "Unrecognized device joined"
    "device_blocked" -> "Access restricted"
    "device_unblocked" -> "Access restored"
    "router_offline" -> "Router is unreachable"
    "router_restored" -> "Router connection restored"
    "management_failed" -> "Device action failed"
    else -> kind.replace('_', ' ').replaceFirstChar { it.uppercase() }
}
