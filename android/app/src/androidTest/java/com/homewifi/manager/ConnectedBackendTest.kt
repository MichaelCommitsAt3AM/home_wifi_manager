package com.homewifi.manager

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import com.google.gson.Gson

/** Opt-in real-backend smoke test. Provision only into target app-private storage via adb run-as. */
class ConnectedBackendTest {
    @get:Rule(order = 0) val provision = object : ExternalResource() {
        override fun before() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val file = java.io.File(context.filesDir, "test-connection.json")
            if (file.exists()) {
                try {
                    val setup = Gson().fromJson(file.readText(), Setup::class.java)
                    SecureConfig(context).save(setup.url, setup.token)
                } finally { file.delete() }
            }
            org.junit.Assume.assumeTrue("Requires privately provisioned backend token", SecureConfig(context).token.isNotBlank())
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private data class Setup(val url: String, val token: String)

    @Test fun realBackendLoadsAndNativeNavigationWorks() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking {
            val api = ApiFactory(SecureConfig(context)).create()
            val health = api.dashboard()
            assertTrue("Router must be reachable from the deployed controller", health.router_online == true)
            assertFalse("Live router data must be fresh", health.stale)
            val devices = api.devices()
            assertTrue("Real router devices must be returned", devices.isNotEmpty())
            assertTrue("Infrastructure protections must be present", devices.count { it.protected } >= 3)
            assertEquals("Displayed write capability must match the server setting", api.settings().writes_enabled, health.router.block_supported)
            val adguard = api.adguard()
            assertTrue("AdGuard API must be reachable", adguard.online == true)
            assertTrue("AdGuard protection must be enabled", adguard.protection_enabled && adguard.filtering_enabled)
            assertTrue("Server DNS must resolve normally", adguard.normal_resolution)
            assertTrue("Ad domain blocking must be observed", adguard.blocking_observed == true)
            println("REAL_BACKEND_VERIFIED known=${devices.size} online=${devices.count { it.online }}")
        }
        compose.waitUntil(60_000) { compose.onAllNodesWithText("Router online").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Your home network").assertIsDisplayed()
        compose.onNodeWithText("AdGuard Home").assertIsDisplayed()
        compose.onNodeWithContentDescription("Devices", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Your devices").assertIsDisplayed()
        compose.onNodeWithContentDescription("Blocked", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Access restrictions").assertIsDisplayed()
        compose.onNodeWithContentDescription("Settings", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Preferences").assertIsDisplayed()
        compose.onNodeWithContentDescription("Activity", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Router access").performScrollTo().performClick()
        compose.onNodeWithText("Release router access").performScrollTo().performClick()
        compose.onNodeWithText("Release router access?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Network activity").performScrollTo().performClick()
        compose.onNodeWithText("Network timeline").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Preferences").assertIsDisplayed()
        // Read-only UI verification: never press the live management button.
        val canWrite = runBlocking { ApiFactory(SecureConfig(context)).create().settings().writes_enabled }
        if (canWrite) {
            val testDevice = runBlocking { ApiFactory(SecureConfig(context)).create().devices().first { !it.protected } }
            compose.onNodeWithContentDescription("Devices", useUnmergedTree = true).performClick()
            compose.onNode(hasSetTextAction()).performTextReplacement(testDevice.mac)
            compose.onNodeWithText(testDevice.name).performClick()
            compose.onNodeWithText("Block device").performScrollTo()
            compose.waitUntil(20_000) { compose.onAllNodes(hasText("Block device") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Block device").assertIsEnabled()
        }
    }
}
