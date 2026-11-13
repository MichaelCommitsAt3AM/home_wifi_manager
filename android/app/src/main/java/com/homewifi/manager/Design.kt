package com.homewifi.manager

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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

// Warm, home-like palette: cream and terracotta in light mode, warm charcoal in dark mode.
val NetworkLightColors = lightColorScheme(
    primary = Color(0xFFBF4E1E), onPrimary = Color.White, primaryContainer = Color(0xFFFBE2D2), onPrimaryContainer = Color(0xFF55200A),
    secondary = Color(0xFF6E5B49), onSecondary = Color.White, secondaryContainer = Color(0xFFF2E6D6), onSecondaryContainer = Color(0xFF3B2D20),
    tertiary = Color(0xFF8A6412), onTertiary = Color.White, tertiaryContainer = Color(0xFFF8EBC9), onTertiaryContainer = Color(0xFF4A3406),
    background = Color(0xFFFBF6EE), onBackground = Color(0xFF2B2118), surface = Color.White, onSurface = Color(0xFF2B2118),
    surfaceVariant = Color(0xFFF1E8DA), onSurfaceVariant = Color(0xFF6B5D4F),
    outline = Color(0xFFC9BAA6), outlineVariant = Color(0xFFEADFCF),
    error = Color(0xFFB3261E), onError = Color.White, errorContainer = Color(0xFFFADCD6), onErrorContainer = Color(0xFF410E0B),
    inverseSurface = Color(0xFF3A2F26), inverseOnSurface = Color(0xFFF8EFE4), inversePrimary = Color(0xFFF2A07B),
    surfaceContainer = Color.White, surfaceContainerLow = Color(0xFFF6EFE4), surfaceContainerHigh = Color(0xFFF1E8DA)
)
val NetworkDarkColors = darkColorScheme(
    primary = Color(0xFFF2A07B), onPrimary = Color(0xFF4A1B07), primaryContainer = Color(0xFF5C2A12), onPrimaryContainer = Color(0xFFFFDBCB),
    secondary = Color(0xFFD9C5AF), onSecondary = Color(0xFF3B2D20), secondaryContainer = Color(0xFF4A3C2F), onSecondaryContainer = Color(0xFFF2E6D6),
    tertiary = Color(0xFFE6C47A), onTertiary = Color(0xFF3F2E05), tertiaryContainer = Color(0xFF4F3B10), onTertiaryContainer = Color(0xFFF8EBC9),
    background = Color(0xFF17120E), onBackground = Color(0xFFF3EBE1), surface = Color(0xFF221B15), onSurface = Color(0xFFF3EBE1),
    surfaceVariant = Color(0xFF30271F), onSurfaceVariant = Color(0xFFC2B2A0),
    outline = Color(0xFF75655A), outlineVariant = Color(0xFF3A3027),
    error = Color(0xFFFFB4AB), onError = Color(0xFF601410), errorContainer = Color(0xFF5E1F1A), onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = Color(0xFFF3EBE1), inverseOnSurface = Color(0xFF2B2118), inversePrimary = Color(0xFFBF4E1E),
    surfaceContainer = Color(0xFF221B15), surfaceContainerLow = Color(0xFF1B1511), surfaceContainerHigh = Color(0xFF30271F)
)
// Slightly larger than before: nothing below 12sp so it stays readable for the whole household.
val NetworkTypography = Typography(
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-1).sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-.6).sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-.4).sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 23.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 21.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp)
)
val NetworkShapes = Shapes(extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(28.dp))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Overview(state: UiState, onDevices: () -> Unit, onSettings: () -> Unit, onRefresh: () -> Unit, onHealth: () -> Unit = {}, onPaused: () -> Unit = {}) {
    val colors = MaterialTheme.colorScheme
    val paused = state.settings.router_access_paused
    val routerOnline = state.dashboard.router_online
    val tone = when { paused -> colors.onSurfaceVariant; routerOnline == true -> onlineColor(); routerOnline == false -> colors.error; else -> colors.onSurfaceVariant }
    val headline = when { paused -> "Monitoring paused"; routerOnline == true -> "Everything looks good"; routerOnline == false -> "Can't reach your router"; else -> "Checking your network…" }
    PullToRefreshBox(isRefreshing = state.loading, onRefresh = onRefresh) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (!state.authenticated) item {
                EmptyState(Icons.Default.Home, "Welcome home", "Connect to your home server to see who's on your network.") { Button(onClick = onSettings) { Text("Set up connection") } }
            }
            if (state.loading && state.devices.isEmpty()) item { Skeleton() }
            if (state.authenticated || state.devices.isNotEmpty()) {
                item {
                    SoftCard(Modifier.fillMaxWidth(), onClick = onDevices) {
                        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                StatusDot(tone, 10.dp)
                                Text(headline, style = MaterialTheme.typography.titleSmall, color = tone)
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(state.dashboard.connected.toString(), style = MaterialTheme.typography.headlineLarge, fontSize = 60.sp, lineHeight = 64.sp, color = colors.primary)
                                Text(if (paused) "devices at last check" else if (state.dashboard.connected == 1) "device online" else "devices online",
                                    style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                            }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("See all devices", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = colors.primary)
                                Icon(Icons.Default.ChevronRight, null, Modifier.size(20.dp), tint = colors.primary)
                            }
                        }
                    }
                }
                if (state.dashboard.unknown > 0 && !paused) item {
                    SoftCard(Modifier.fillMaxWidth(), onClick = onDevices, color = colors.tertiaryContainer, border = colors.tertiaryContainer) {
                        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            IconBadge(Icons.Default.DevicesOther, colors.onTertiaryContainer)
                            Column(Modifier.weight(1f)) {
                                val n = state.dashboard.unknown
                                Text(if (n == 1) "1 new device joined" else "$n new devices joined", style = MaterialTheme.typography.titleSmall, color = colors.onTertiaryContainer)
                                Text("Make sure you recognize ${if (n == 1) "it" else "them"}.", style = MaterialTheme.typography.bodySmall, color = colors.onTertiaryContainer)
                            }
                            Icon(Icons.Default.ChevronRight, null, Modifier.size(20.dp), tint = colors.onTertiaryContainer)
                        }
                    }
                }
                if (state.dashboard.blocked > 0) item {
                    val n = state.dashboard.blocked
                    SettingsRow(Icons.Default.PauseCircle, if (n == 1) "1 device paused" else "$n devices paused", "Their internet access is turned off", onPaused)
                }
                item {
                    val healthy = state.connection.state == "healthy" && !state.connection.stale
                    SettingsRow(Icons.Default.Public, "Internet", if (state.connection.stale) "Checking…" else healthLabel(state.connection.state), onHealth)
                }
                if (state.dashboard.adguard.configured) {
                    item { AdGuardCard(state.dashboard.adguard, onDetails = onSettings) }
                }
                item { Text("Last updated ${timestamp(state.dashboard.last_sync)}", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant) }
            }
        }
    }
}

private fun adguardSummary(status: AdGuardStatus, healthy: Boolean): String = when {
    healthy -> "Protection is on"
    status.authentication_required -> "Sign-in needed"
    !status.configured -> "Not set up"
    status.online == true && !status.protection_enabled -> "Paused"
    else -> "Needs attention"
}

@Composable
fun AdGuardCard(status: AdGuardStatus, expanded: Boolean = false, onDetails: () -> Unit = {}) {
    val healthy = status.online == true && status.running && status.protection_enabled && status.filtering_enabled
    val tint = if (healthy) onlineColor() else MaterialTheme.colorScheme.onSurfaceVariant
    if (!expanded) {
        SettingsRow(Icons.Default.VerifiedUser, "Ad blocking", adguardSummary(status, healthy), onDetails)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconBadge(Icons.Default.VerifiedUser, tint)
            Column(Modifier.weight(1f)) {
                Text("Ad blocking", style = MaterialTheme.typography.titleSmall)
                Text(adguardSummary(status, healthy), style = MaterialTheme.typography.bodySmall, color = tint)
            }
            Icon(if (healthy) Icons.Default.CheckCircle else Icons.Default.Info, null, Modifier.size(20.dp), tint = tint)
        }
        if (status.online == true) {
            Row(Modifier.fillMaxWidth()) {
                ProtectionMetric("Blocked", compactNumber(status.blocked_queries), Modifier.weight(1f))
                ProtectionMetric("Requests", compactNumber(status.queries), Modifier.weight(1f))
                ProtectionMetric("Share blocked", "${status.blocked_percent}%", Modifier.weight(1f))
            }
            LinearProgressIndicator(progress = { (status.blocked_percent / 100).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(4.dp), color = tint, trackColor = tint.copy(alpha = .12f))
        }
    }
}

/** Technical AdGuard facts; shown only under Settings > Advanced. */
@Composable
fun AdGuardTechnicalDetails(status: AdGuardStatus) {
    DetailLine("DNS check", if (status.blocking_observed == true && status.normal_resolution) "Passed: ads blocked, normal DNS resolves"
        else if (!status.configured) "AdGuard integration is being configured on the server"
        else if (status.online == true && !status.protection_enabled) "Protection is paused; DNS still resolves normally"
        else "Server DNS probe has not confirmed filtering yet")
    DetailLine("Last checked", timestamp(status.checked_at))
    DetailLine("Filter lists", "${status.enabled_filters} enabled · ${compactNumber(status.rules_count)} rules")
    DetailLine("Average DNS time", "${status.avg_processing_ms} ms")
    DetailLine("Statistics", "${status.observed_clients} observed clients")
    Text("These checks verify AdGuard and the server's DNS path. Private DNS, VPNs, and browser encrypted DNS can bypass it on individual devices.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable private fun ProtectionMetric(label: String, value: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) { Text(value, style = MaterialTheme.typography.titleMedium); Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
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
    "device_blocked" -> "Internet paused"
    "device_unblocked" -> "Internet resumed"
    "router_offline" -> "Router is unreachable"
    "router_restored" -> "Router connection restored"
    "management_failed" -> "Device action failed"
    else -> kind.replace('_', ' ').replaceFirstChar { it.uppercase() }
}
fun notificationLabel(kind: String): String = when(kind) {
    "unknown_device" -> "A new device joins"
    "router_offline" -> "Your router goes offline"
    "router_restored" -> "Your router comes back"
    "schedule_executed" -> "A schedule runs"
    "schedule_failed" -> "A schedule doesn't run"
    "management_failed" -> "A device action fails"
    else -> eventLabel(kind)
}
