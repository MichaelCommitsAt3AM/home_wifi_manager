package com.homewifi.manager

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant

fun healthLabel(state: String): String = when(state) {
    "healthy" -> "Connection healthy"
    "router_unreachable" -> "Router unreachable"
    "internet_down" -> "Internet unavailable"
    "dns_failure" -> "DNS not resolving"
    else -> "Checking connection"
}

fun outageDuration(outage: Outage): String = runCatching {
    val seconds = Duration.between(Instant.parse(outage.started_at), outage.ended_at?.let(Instant::parse) ?: Instant.now()).seconds.coerceAtLeast(0)
    when {
        seconds < 60 -> "Less than a minute"
        seconds < 3600 -> "${seconds/60} min"
        else -> "${seconds/3600} h ${(seconds%3600)/60} min"
    }
}.getOrDefault("Duration unavailable")

@Composable
fun ConnectionHealthScreen(vm: WifiViewModel, state: UiState) {
    val health = state.connection
    val speed = health.speed_test
    var confirmSpeed by remember { mutableStateOf(false) }
    LaunchedEffect(speed.state) {
        while(true) { vm.connection(); delay(if(speed.state == "running") 3000 else 30_000) }
    }
    if(confirmSpeed) AlertDialog(onDismissRequest = { confirmSpeed = false }, title = { Text("Test the server connection?") },
        text = { Text("Measures the home server’s Internet connection, not this phone’s Wi-Fi. Transfers about 30 MB plus connection overhead through Cloudflare. Other network activity can affect the result.") },
        confirmButton = { TextButton(onClick = { confirmSpeed = false; vm.speedTest() }) { Text("Start test") } },
        dismissButton = { TextButton(onClick = { confirmSpeed = false }) { Text("Cancel") } })
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { SectionTitle("Connection health", "Observed from your home server") }
        item { OutlinedCard { Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if(health.stale) "Waiting for a fresh check" else healthLabel(health.state), style = MaterialTheme.typography.titleMedium)
            DetailLine("Router dashboard", reachability(health.router_reachable))
            DetailLine("Internet", reachability(health.internet_reachable))
            DetailLine("DNS resolution", reachability(health.dns_working))
            DetailLine("Last checked", timestamp(health.checked_at))
            Text("Checks run about every 30 seconds. Two matching observations confirm a change. Router reachability checks continue while dashboard access is released, without logging in.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { vm.connection(true) }, enabled = !state.busy) { Text("Check connection") }
        } } }
        item { SectionTitle("Server speed test") }
        item { OutlinedCard { Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when(speed.state) {
                "running" -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Testing the home server connection…") }
                "completed" -> {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        DetailLine("Download", "${speed.download_mbps} Mbps")
                        DetailLine("Upload", "${speed.upload_mbps} Mbps")
                    }
                    DetailLine("HTTP latency", "${speed.latency_ms} ms")
                    DetailLine("Tested", timestamp(speed.finished_at))
                }
                "failed", "interrupted" -> Text(speed.error ?: "The test did not complete. Try again.")
                else -> Text("Run a test when you want to check your connection speed.")
            }
            Text("A bounded single-connection estimate to Cloudflare, not continuous usage tracking. One test per minute.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { confirmSpeed = true }, enabled = !state.busy && speed.state != "running") { Text("Run speed test") }
        } } }
        item { SectionTitle("Outage history", "Up to 100 episodes within ${state.settings.retention_days} days · timing is approximate") }
        if(health.outages.isEmpty()) item { Text("No confirmed outages recorded yet.", style = MaterialTheme.typography.bodyMedium) }
        items(health.outages) { outage -> OutlinedCard { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(healthLabel(outage.kind), style = MaterialTheme.typography.titleSmall)
            Text("${timestamp(outage.started_at)} → ${outage.ended_at?.let { timestamp(it) } ?: "Ongoing"}", style = MaterialTheme.typography.bodySmall)
            Text(outageDuration(outage), style = MaterialTheme.typography.bodySmall)
            if(outage.end_reason == "monitoring_gap") Text("Observation ended when monitoring stopped; recovery time is unknown.", style = MaterialTheme.typography.bodySmall)
        } } }
    }
}

private fun reachability(value: Boolean?): String = when(value) { true -> "Reachable"; false -> "Unreachable"; null -> "Not checked" }
private data class PendingControl(val title: String, val explanation: String, val run: () -> Unit)

@Composable
fun AdGuardControls(vm: WifiViewModel, state: UiState) {
    var confirm by remember { mutableStateOf<PendingControl?>(null) }
    var domain by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { vm.loadExceptions() }
    confirm?.let { pending -> AlertDialog(onDismissRequest = { confirm = null }, title = { Text(pending.title) },
        text = { Text(pending.explanation) },
        confirmButton = { TextButton(onClick = { confirm = null; pending.run() }) { Text("Confirm") } },
        dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } }) }
    val status = state.dashboard.adguard
    val enabled = status.online == true && !state.busy
    Text("Protection controls", style = MaterialTheme.typography.titleSmall)
    if(status.protection_enabled) {
        Text("Pause protection for everyone using this DNS server:", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(5, 15, 60).forEach { minutes -> OutlinedButton(enabled = enabled, onClick = {
                confirm = PendingControl("Pause protection for $minutes minutes?", "AdGuard will automatically resume protection using its own timer. DNS service stays running.") { vm.protection(false, minutes) }
            }) { Text(if(minutes == 60) "1 h" else "$minutes min") } }
        }
    } else Button(onClick = { vm.protection(true) }, enabled = enabled) { Text("Resume protection") }
    OutlinedButton(enabled = enabled, onClick = {
        confirm = PendingControl("Refresh filter lists?", "Ask AdGuard to refresh its existing blocking lists.") { vm.refreshFilters() }
    }) { Text("Refresh filter lists") }
    HorizontalDivider()
    Text("Allowed domains", style = MaterialTheme.typography.titleSmall)
    Text("Exceptions apply to everyone using AdGuard and include subdomains. Only exceptions created here are listed and removed here.", style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(domain, { domain = it.take(253) }, Modifier.fillMaxWidth(), singleLine = true,
        label = { Text("Domain, e.g. example.com") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
    OutlinedButton(enabled = enabled && domain.isNotBlank(), onClick = {
        val value = domain.trim()
        confirm = PendingControl("Allow $value?", "Adds a DNS filtering exception for this domain and its subdomains.") { vm.exception(value, true); domain = "" }
    }) { Text("Allow domain") }
    state.exceptions.forEach { name -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        TextButton(enabled = enabled, onClick = {
            confirm = PendingControl("Remove $name exception?", "Normal AdGuard filtering will apply again.") { vm.exception(name, false) }
        }) { Text("Remove") }
    } }
}
