package com.homewifi.manager

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Green used for "online / working" states; adapts to the active (light or dark) scheme. */
@Composable
fun onlineColor(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < .5f) Color(0xFF86D79B) else Color(0xFF2E7D4F)

@Composable
fun StatusDot(color: Color, size: Dp = 8.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).background(color, CircleShape))
}

/** The one card style used across the app: soft, flat surface with a hairline border. */
@Composable
fun SoftCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    color: Color = MaterialTheme.colorScheme.surface,
    border: Color = MaterialTheme.colorScheme.outlineVariant,
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(20.dp)
    val stroke = BorderStroke(1.dp, border)
    if (onClick != null) Surface(onClick = onClick, modifier = modifier, shape = shape, color = color, border = stroke, content = content)
    else Surface(modifier = modifier, shape = shape, color = color, border = stroke, content = content)
}

@Composable
fun IconBadge(icon: ImageVector, tint: Color = MaterialTheme.colorScheme.primary, size: Dp = 40.dp) {
    Box(Modifier.size(size).background(tint.copy(alpha = .13f), CircleShape), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(size * .52f), tint = tint)
    }
}

@Composable
fun SectionTitle(title: String, subtitle: String? = null) {
    Column(Modifier.padding(top = 8.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, subtitle: String, action: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        IconBadge(icon, size = 64.dp)
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        action?.invoke()
    }
}

@Composable
fun Skeleton() {
    val transition = rememberInfiniteTransition(label = "Loading")
    val alpha by transition.animateFloat(0.25f, 0.7f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "Pulse")
    Surface(Modifier.fillMaxWidth().height(120.dp), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha)) {}
}

@Composable
fun DetailLine(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value.ifBlank { "Not reported" }, style = MaterialTheme.typography.bodyMedium)
    }
}

/** A tappable row with an icon, used inside a card or wrapped by [SettingsRow]. */
@Composable
fun ActionRow(icon: ImageVector, title: String, subtitle: String? = null, onClick: () -> Unit, showChevron: Boolean = true) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        IconBadge(icon)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (showChevron) Icon(Icons.Default.ChevronRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SettingsRow(icon: ImageVector, title: String, subtitle: String? = null, onClick: () -> Unit) {
    SoftCard(onClick = onClick) { ActionRow(icon, title, subtitle, onClick) }
}

@Composable
fun ToggleRow(title: String, subtitle: String? = null, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked, onChange, modifier = Modifier.semantics { contentDescription = title }, enabled = enabled)
    }
}

/** Collapsible card used to keep Settings short; technical groups start collapsed. */
@Composable
fun ExpandableCard(
    title: String, subtitle: String? = null, icon: ImageVector? = null, initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    SoftCard {
        Column {
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                icon?.let { IconBadge(it) }
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    if (expanded) "Collapse $title" else "Expand $title", Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (expanded) Column(Modifier.padding(start = 18.dp, end = 18.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
        }
    }
}

/** Device avatar: icon in a soft circle with a small status dot. */
@Composable
fun DeviceAvatar(device: Device, size: Dp = 44.dp) {
    val colors = MaterialTheme.colorScheme
    val dot = when { device.blocked -> colors.error; device.online -> onlineColor(); else -> colors.outline }
    Box(Modifier.size(size)) {
        Box(Modifier.fillMaxSize().background(if (device.online && !device.blocked) colors.primaryContainer else colors.surfaceVariant, CircleShape), contentAlignment = Alignment.Center) {
            Icon(deviceIcon(device), null, Modifier.size(size * .5f), tint = if (device.online && !device.blocked) colors.onPrimaryContainer else colors.onSurfaceVariant)
        }
        StatusDot(dot, size * .28f, Modifier.align(Alignment.BottomEnd).border(2.dp, colors.surface, CircleShape))
    }
}
