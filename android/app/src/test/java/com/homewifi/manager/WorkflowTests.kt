package com.homewifi.manager

import java.io.IOException
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.After
import org.junit.Before
import retrofit2.HttpException
import retrofit2.Response
import okhttp3.ResponseBody.Companion.toResponseBody

private class TestConfig : ConnectionSettings {
    override var url = "https://test.invalid/"
    override var token = "x".repeat(32)
    override var theme = "system"
    override var notifications = false
    override var cursor = -1L
    override var dynamicColors = false
    override fun save(address: String, token: String) { this.url = address; this.token = token }
    override fun clear() { token = "" }
}
private class MemoryDao : DeviceDao {
    val rows = MutableStateFlow<List<CachedDevice>>(emptyList())
    override fun observe() = rows
    override suspend fun clear() { rows.value = emptyList() }
    override suspend fun insert(devices: List<CachedDevice>) { rows.value = devices }
}
private class TestApi : WifiApi {
    var paused = false
    var syncCalls = 0
    override suspend fun releaseRouterAccess(body: RouterRelease): RouterAccessStatus { paused = true; return RouterAccessStatus(router_access_paused = true, router_logout_verified = true) }
    override suspend fun resumeRouterAccess(): RouterAccessStatus { paused = false; return RouterAccessStatus() }
    override suspend fun adguard() = AdGuardStatus()
    var devices = listOf(Device("02:00:00:00:00:02", hostname = "Example", online = true))
    var failure: Exception? = null
    var accessCalls = 0
    override suspend fun dashboard(): Dashboard { failure?.let { throw it }; return Dashboard(router_online = true, total = devices.size, connected = devices.size, stale = false) }
    override suspend fun devices() = devices
    override suspend fun settings() = Preferences(writes_enabled = !paused, router_access_paused = paused, router_logout_verified = paused)
    override suspend fun settings(settings: Preferences) = settings
    override suspend fun sync(): Dashboard { syncCalls++; return dashboard() }
    override suspend fun events(mac: String?, before: Long?, after: Long?, limit: Int) = emptyList<Event>()
    override suspend fun schedules(mac: String?) = emptyList<Schedule>()
    override suspend fun schedule(draft: ScheduleDraft): Schedule = error("Not used")
    override suspend fun cancel(id: Int) = mapOf("cancelled" to true)
    override suspend fun patch(mac: String, patch: DevicePatch): Device { devices = devices.map { if(it.mac == mac) it.copy(alias = patch.alias ?: it.alias) else it }; return devices.first { it.mac == mac } }
    override suspend fun access(mac: String, change: AccessChange): Device {
        failure?.let { throw it }
        accessCalls++
        devices = devices.map { if(it.mac == mac) it.copy(blocked = change.blocked) else it }
        return devices.first { it.mac == mac }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class WorkflowTests {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }

    @Test fun repositoryPersistsRealResponsesAndKeepsCacheOnFailure() = runTest(dispatcher) {
        val api = TestApi(); val dao = MemoryDao(); val repo = Repository(object : ApiSource { override fun create() = api }, dao)
        repo.load()
        assertEquals("Example", repo.decode(dao.rows.value).single().name)
        api.failure = IOException("offline")
        runCatching { repo.load() }
        assertEquals(1, dao.rows.value.size)
        repo.clearCache()
        assertTrue(dao.rows.value.isEmpty())
    }

    @Test fun viewModelLoadsAndVerifiesManagementBeforeShowingSuccess() = runTest(dispatcher) {
        val api = TestApi(); val dao = MemoryDao(); val repo = Repository(object : ApiSource { override fun create() = api }, dao)
        val vm = WifiViewModel(repo, TestConfig())
        runCurrent()
        assertFalse(vm.state.value.loading)
        assertEquals(1, vm.state.value.devices.size)
        vm.access(vm.state.value.devices.single()); runCurrent()
        assertEquals(1, api.accessCalls)
        assertTrue(vm.state.value.devices.single().blocked)
        assertEquals("Router restriction state verified", vm.state.value.message)
        api.failure = HttpException(Response.error<Any>(502, "".toResponseBody()))
        vm.access(vm.state.value.devices.single()); runCurrent()
        assertNull(vm.state.value.message)
        assertTrue(vm.state.value.error!!.contains("verify"))
        vm.viewModelScope.cancel()
    }

    @Test fun releasingRouterAccessSurvivesRefreshAndResumesExplicitly() = runTest(dispatcher) {
        val api = TestApi(); val repo = Repository(object : ApiSource { override fun create() = api }, MemoryDao())
        val vm = WifiViewModel(repo, TestConfig()); runCurrent()
        vm.routerAccess(true); runCurrent()
        assertTrue(vm.state.value.settings.router_access_paused)
        assertTrue(vm.state.value.settings.router_logout_verified)
        assertFalse(vm.state.value.settings.writes_enabled)
        assertEquals("Server logged out. Router access released.", vm.state.value.message)
        vm.refresh(true); runCurrent()
        assertEquals(0, api.syncCalls)
        assertTrue(vm.state.value.settings.router_access_paused)
        vm.routerAccess(false); runCurrent()
        assertFalse(vm.state.value.settings.router_access_paused)
        assertTrue(vm.state.value.settings.writes_enabled)
        assertEquals("Router access resumed", vm.state.value.message)
        vm.viewModelScope.cancel()
    }

    @Test fun authenticationFailureAndOfflineCacheAreVisible() = runTest(dispatcher) {
        val api = TestApi(); val dao = MemoryDao(); val repo = Repository(object : ApiSource { override fun create() = api }, dao)
        repo.load()
        api.failure = HttpException(Response.error<Any>(401, "".toResponseBody()))
        val vm = WifiViewModel(repo, TestConfig()); runCurrent()
        assertFalse(vm.state.value.authenticated)
        assertTrue(vm.state.value.cached)
        assertEquals(1, vm.state.value.devices.size)
        assertTrue(vm.state.value.error!!.contains("token"))
        vm.viewModelScope.cancel()
    }
}
