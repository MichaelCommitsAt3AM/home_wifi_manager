package com.homewifi.manager

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject

data class UiState(
    val dashboard: Dashboard = Dashboard(), val devices: List<Device> = emptyList(),
    val events: List<Event> = emptyList(), val history: List<Event> = emptyList(),
    val schedules: List<Schedule> = emptyList(), val settings: Preferences = Preferences(),
    val loading: Boolean = false, val busy: Boolean = false, val error: String? = null,
    val message: String? = null, val authenticated: Boolean = false, val cached: Boolean = true,
    val theme: String = "system", val dynamicColors: Boolean = false,
    val connection: ConnectionHealth = ConnectionHealth(), val exceptions: List<String> = emptyList()
)

fun errorMessage(error: Throwable): String = when (error) {
    is HttpException -> when (error.code()) {
        401 -> "Authentication expired or token incorrect. Check your API token in Settings."
        403 -> "This device is protected. Infrastructure devices cannot be blocked."
        409 -> "This action is unavailable right now. Refresh and check its requirements."
        422 -> "Check the device information, time, and settings you entered."
        429 -> "Too many requests. Please wait a minute."
        502 -> "The server could not verify the response. Refresh before retrying."
        else -> "Server request failed (${error.code()}). Try again."
    }
    is javax.net.ssl.SSLException -> "The HTTPS certificate could not be verified. Check the backend hostname and server certificate."
    is IOException -> "Cannot reach the backend. Check Wi-Fi, Tailscale, and the backend address."
    is IllegalArgumentException -> error.message ?: "Check your connection settings."
    else -> "The operation could not be completed. Refresh and try again."
}

fun selectDevices(devices: List<Device>, search: String, filter: String, sort: String): List<Device> {
    val selected = devices.filter { device ->
        (search.isBlank() || listOf(device.name, device.ip.orEmpty(), device.mac).any { it.contains(search, true) }) &&
            when (filter) {
                "Online" -> device.online
                "Offline" -> !device.online
                "Trusted" -> device.trusted
                "Blocked" -> device.blocked
                else -> true
            }
    }
    val onlineFirst = compareByDescending<Device> { it.online }
    val order = if(sort == "Recent") onlineFirst.thenByDescending { it.last_seen }.thenBy { it.name.lowercase() }
        else onlineFirst.thenBy { it.name.lowercase() }
    return selected.sortedWith(order.thenBy { it.mac })
}

@HiltViewModel
class WifiViewModel @Inject constructor(private val repository: RouterRepository, val config: ConnectionSettings) : ViewModel() {
    private val mutable = MutableStateFlow(UiState(authenticated = config.token.isNotBlank(), theme = config.theme, dynamicColors = config.dynamicColors))
    val state = mutable.asStateFlow()
    init {
        viewModelScope.launch { repository.cached.collect { rows ->
            if (mutable.value.cached) mutable.update { it.copy(devices = repository.decode(rows)) }
        } }
        viewModelScope.launch {
            while (true) { if (config.token.isNotBlank()) refreshNow(); delay(60_000) }
        }
    }
    private suspend fun refreshNow(force: Boolean = false) {
        if (mutable.value.loading || mutable.value.busy || config.token.isBlank()) return
        mutable.update { it.copy(loading = true, error = null) }
        try {
            if (force && !mutable.value.settings.router_access_paused) repository.api().sync()
            val (dashboard, devices, settings) = repository.load()
            mutable.update { it.copy(dashboard = dashboard, devices = devices, settings = settings,
                cached = false, authenticated = true, loading = false,
                connection = dashboard.connection.copy(speed_test = it.connection.speed_test)) }
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            mutable.update { it.copy(loading = false, error = errorMessage(e), cached = true, connection = it.connection.copy(stale = true),
                authenticated = if (e is HttpException && e.code() == 401) false else it.authenticated) }
        }
    }
    fun refresh(force: Boolean = false) { viewModelScope.launch { refreshNow(force) } }
    fun dismiss() { mutable.update { it.copy(error = null, message = null) } }
    private fun action(success: String? = null, operation: suspend () -> Unit) {
        if (mutable.value.busy) return
        viewModelScope.launch {
            mutable.update { it.copy(busy = true, error = null, message = null) }
            try {
                operation()
                mutable.update { it.copy(busy = false, message = success ?: it.message) }
                refreshNow()
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { mutable.update { it.copy(busy = false, error = errorMessage(e)) } }
        }
    }
    fun connect(address: String, token: String) = action("Backend connection verified") {
        config.save(address, token)
        repository.clearCache()
        mutable.update { it.copy(devices = emptyList(), dashboard = Dashboard(), events = emptyList(), history = emptyList(), cached = true) }
        repository.api().dashboard()
    }
    fun logout() = action {
        config.clear(); repository.clearCache()
        mutable.value = UiState(theme = config.theme, dynamicColors = config.dynamicColors)
    }
    fun patch(mac: String, patch: DevicePatch) = action("Device updated") { repository.api().patch(mac, patch) }
    fun access(device: Device, minutes: Int? = null) = action(if(minutes == null) "Router restriction state verified" else "Timed block verified; automatic unblock saved") {
        repository.api().access(device.mac, AccessChange(!device.blocked, minutes = minutes))
        val schedules = repository.api().schedules(device.mac)
        mutable.update { it.copy(schedules = schedules) }
    }
    fun connection(check: Boolean = false) {
        if(mutable.value.busy) return
        viewModelScope.launch {
            try {
                val health = if(check) repository.api().checkConnection() else repository.api().connection()
                mutable.update { it.copy(connection = health) }
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { mutable.update { it.copy(error = errorMessage(e), connection = it.connection.copy(stale = true)) } }
        }
    }
    fun speedTest() = action("Server speed test started") {
        val result = repository.api().speedTest()
        mutable.update { it.copy(connection = it.connection.copy(speed_test = result)) }
    }
    fun protection(enabled: Boolean, minutes: Int = 5) = action(if(enabled) "AdGuard protection resumed" else "AdGuard paused for $minutes minutes") {
        val status = repository.api().protection(ProtectionChange(enabled, minutes))
        mutable.update { it.copy(dashboard = it.dashboard.copy(adguard = status)) }
    }
    fun refreshFilters() = action {
        val result = repository.api().refreshFilters()
        mutable.update { it.copy(message = "Filter refresh complete · ${result.updated} lists updated") }
    }
    fun loadExceptions() = action {
        val domains = repository.api().exceptions().domains
        mutable.update { it.copy(exceptions = domains) }
    }
    fun exception(domain: String, allowed: Boolean) = action(if(allowed) "Domain exception saved" else "Domain exception removed") {
        val domains = repository.api().exception(DomainException(domain, allowed)).domains
        mutable.update { it.copy(exceptions = domains) }
    }
    fun history(mac: String) = action { mutable.update { it.copy(history = repository.api().events(mac), schedules = repository.api().schedules(mac)) } }
    fun activity(more: Boolean = false) = action {
        val events = repository.api().events(before = if (more) mutable.value.events.lastOrNull()?.id else null)
        mutable.update { it.copy(events = if (more) (it.events + events).distinctBy { e -> e.id } else events) }
    }
    fun preferences(pollSeconds: Int, categories: List<String>) = action("Monitoring settings saved") {
        repository.api().settings(mutable.value.settings.copy(poll_seconds = pollSeconds, notification_categories = categories))
    }
    fun schedule(draft: ScheduleDraft) = action("Schedule saved on server") {
        repository.api().schedule(draft)
        mutable.update { it.copy(schedules = repository.api().schedules(draft.mac)) }
    }
    fun cancel(schedule: Schedule) = action("Schedule cancelled") {
        repository.api().cancel(schedule.id)
        mutable.update { it.copy(schedules = repository.api().schedules(schedule.mac)) }
    }
    fun theme(theme: String) { config.theme = theme; mutable.update { it.copy(theme = theme) } }
    fun routerAccess(release: Boolean) = action {
        val status = if(release) repository.api().releaseRouterAccess() else repository.api().resumeRouterAccess()
        mutable.update { it.copy(
            settings = it.settings.copy(router_access_paused = status.router_access_paused,
                router_logout_verified = status.router_logout_verified, router_access_error = status.router_access_error,
                writes_enabled = it.settings.writes_enabled && !status.router_access_paused),
            dashboard = it.dashboard.copy(router_access_paused = status.router_access_paused),
            message = if(!status.router_access_paused) "Router access resumed" else if(status.router_logout_verified) "Server logged out. Router access released." else "Monitoring paused, but router logout could not be confirmed."
        ) }
    }
    fun dynamicColors(enabled: Boolean) { config.dynamicColors = enabled; mutable.update { it.copy(dynamicColors = enabled) } }
}
