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

    private fun scrollTo(text: String): SemanticsNodeInteraction {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text))
        return compose.onNodeWithText(text)
    }

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
        compose.waitUntil(60_000) { compose.onAllNodesWithText("Everything looks good").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("See all devices").assertIsDisplayed()
        compose.onNodeWithText("Ad blocking").assertIsDisplayed()
        val adguardBounds = compose.onNodeWithText("Ad blocking").fetchSemanticsNode().boundsInRoot
        val connectionBounds = compose.onNodeWithText("Internet is working").fetchSemanticsNode().boundsInRoot
        assertTrue("Internet status sits above ad blocking", adguardBounds.top > connectionBounds.bottom)
        repeat(2) {
            compose.onNodeWithText("See all devices").performClick()
            compose.onNodeWithText("Search devices").assertIsDisplayed()
            compose.onNode(isSelectable() and hasAnyDescendant(hasContentDescription("Devices")), useUnmergedTree = true).assertIsSelected()
            compose.onNodeWithContentDescription("Home", useUnmergedTree = true).performClick()
            compose.onNodeWithText("See all devices").assertIsDisplayed()
            compose.onNode(isSelectable() and hasAnyDescendant(hasContentDescription("Home")), useUnmergedTree = true).assertIsSelected()
        }
        compose.onNodeWithContentDescription("Devices", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Search devices").assertIsDisplayed()
        compose.onNodeWithContentDescription("Settings", useUnmergedTree = true).performClick()
        scrollTo("Appearance").assertIsDisplayed()
        val beforeBanner = compose.onNodeWithText("Appearance").fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithContentDescription("Preview update banner").performClick()
        compose.onNodeWithText("Update available").assertIsDisplayed()
        val bannerBounds = compose.onNodeWithText("Update available").fetchSemanticsNode().boundsInRoot
        val headerBounds = compose.onNodeWithText("Home WiFi").fetchSemanticsNode().boundsInRoot
        assertTrue("Update notice belongs above the app header", bannerBounds.bottom < headerBounds.top)
        val afterBanner = compose.onNodeWithText("Appearance").fetchSemanticsNode().boundsInRoot.top
        assertTrue("The update banner pushes app content down", afterBanner > beforeBanner)
        compose.onNodeWithText("Update").performClick()
        compose.onNodeWithText("Preview · no download will start").assertIsDisplayed()
        compose.onNodeWithContentDescription("Dismiss update banner").performClick()
        compose.onNodeWithText("Update available").assertDoesNotExist()
        compose.onNodeWithContentDescription("Preview update banner").assertIsOff()
        compose.onNodeWithContentDescription("Activity", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Server address (HTTPS)").assertDoesNotExist()
        scrollTo("Advanced").performClick()
        scrollTo("Router access").performClick()
        scrollTo("Release router access").performClick()
        compose.onNodeWithText("Release router access?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        scrollTo("Activity").performClick()
        compose.onNode(hasText("days on your network", substring = true)).assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        scrollTo("Appearance").assertIsDisplayed()
        scrollTo("Internet").performClick()
        scrollTo("Speed test").assertIsDisplayed()
        scrollTo("Run speed test").performClick()
        compose.onNodeWithText("Test the server connection?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        scrollTo("Outages").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        scrollTo("Ad blocking").performClick()
        scrollTo("Allowed domains").assertIsDisplayed()
        scrollTo("Refresh filter lists").assertIsDisplayed()
        // Read-only UI verification: never press the live management button.
        val canWrite = runBlocking { ApiFactory(SecureConfig(context)).create().settings().writes_enabled }
        if (canWrite) {
            val testDevice = runBlocking { ApiFactory(SecureConfig(context)).create().devices().first { !it.protected && !it.blocked && it.online } }
            compose.onNodeWithContentDescription("Devices", useUnmergedTree = true).performClick()
            compose.onNode(hasSetTextAction()).performTextReplacement(testDevice.mac)
            compose.onNode(hasText(testDevice.name) and !hasSetTextAction()).performClick()
            scrollTo("Pause internet")
            compose.waitUntil(20_000) { compose.onAllNodes(hasText("Pause internet") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Pause internet").assertIsEnabled()
            scrollTo("15 min").performClick()
            compose.onNode(hasText("for 15 minutes?", substring = true)).assertIsDisplayed()
            compose.onNodeWithText("Cancel").performClick()
        }
    }
}
