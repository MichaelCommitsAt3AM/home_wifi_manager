package com.homewifi.manager

import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import okhttp3.ResponseBody.Companion.toResponseBody

class DeviceTests {
    private val online = Device("02:00:00:00:00:02", "192.168.100.2", "Laptop", online = true, trusted = true)
    private val blocked = Device("02:00:00:00:00:04", "192.168.100.4", "Phone", alias = "Guest phone", blocked = true)
    @Test fun searchMatchesAliasIpAndMac() {
        assertEquals(listOf(blocked), selectDevices(listOf(online, blocked), "guest", "All", "Name"))
        assertEquals(listOf(online), selectDevices(listOf(online, blocked), "100.2", "All", "Name"))
        assertEquals(listOf(blocked), selectDevices(listOf(online, blocked), "00:04", "All", "Name"))
    }
    @Test fun filtersSeparateOnlineOfflineAndBlocked() {
        assertEquals(listOf(online), selectDevices(listOf(blocked, online), "", "Online", "Name"))
        assertEquals(listOf(blocked), selectDevices(listOf(blocked, online), "", "Blocked", "Name"))
        assertEquals(listOf(blocked), selectDevices(listOf(blocked, online), "", "Offline", "Name"))
    }
    @Test fun onlineSortAndFriendlyNameFallback() {
        assertEquals(online, selectDevices(listOf(blocked, online), "", "All", "Name").first())
        assertEquals("Guest phone", blocked.name)
        assertEquals("02:00:00:00:00:06", Device("02:00:00:00:00:06").name)
    }
    @Test fun bothSortOptionsKeepOnlineDevicesFirstAndSortWithinEachGroup() {
        val onlineA = online.copy(hostname = "Alpha", last_seen = "2026-10-20T08:00:00Z")
        val onlineZ = online.copy(mac = "02:00:00:00:00:06", hostname = "Zulu", last_seen = "2026-10-20T09:00:00Z")
        val offlineA = blocked.copy(alias = "Alpha", last_seen = "2026-10-20T11:00:00Z")
        val offlineZ = blocked.copy(mac = "02:00:00:00:00:08", alias = "Zulu", last_seen = "2026-10-20T12:00:00Z")
        val devices = listOf(offlineZ, onlineZ, offlineA, onlineA)
        assertEquals(listOf(onlineA, onlineZ, offlineA, offlineZ), selectDevices(devices, "", "All", "Name"))
        assertEquals(listOf(onlineZ, onlineA, offlineZ, offlineA), selectDevices(devices, "", "All", "Recent"))
    }

    @Test fun authenticationAndVerificationErrorsAreActionable() {
        val auth = HttpException(Response.error<Any>(401, "".toResponseBody()))
        val verify = HttpException(Response.error<Any>(502, "".toResponseBody()))
        assertTrue(errorMessage(auth).contains("token"))
        assertTrue(errorMessage(verify).contains("verify"))
        assertTrue(errorMessage(java.io.IOException()).contains("Tailscale"))
    }
    @Test fun accessChangesRequireConfirmationByDefault() {
        assertTrue(AccessChange(true).confirmed)
        assertFalse(AccessChange(false).blocked)
    }
}
