package com.homewifi.manager

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test

class NavigationTest {
    @get:Rule val compose = createComposeRule()
    @Test fun overviewOffersConnectionSetup() {
        var setup = false
        compose.setContent { MaterialTheme { Overview(UiState(), {}, { setup = true }, {}) } }
        compose.onNodeWithText("Welcome home").assertIsDisplayed()
        compose.onNodeWithText("Set up connection").performClick()
        assert(setup)
    }
    @Test fun blockedScreenShowsAnHonestEmptyState() {
        compose.setContent { MaterialTheme { DeviceList(UiState(), true, {}, {}) } }
        compose.onNodeWithText("No restrictions found").assertIsDisplayed()
    }
    @Test fun overviewHidesUnconfiguredAdGuard() {
        compose.setContent { MaterialTheme { Overview(UiState(authenticated = true), {}, {}, {}) } }
        compose.onNodeWithText("AdGuard Home").assertDoesNotExist()
        compose.onNodeWithText("View devices").assertIsDisplayed()
    }
    @Test fun overviewShowsConfiguredAdGuardWhenOffline() {
        val state = UiState(authenticated = true,
            dashboard = Dashboard(adguard = AdGuardStatus(configured = true, online = false)))
        compose.setContent { MaterialTheme { Overview(state, {}, {}, {}) } }
        compose.onNodeWithText("AdGuard Home").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Needs attention").assertIsDisplayed()
    }
    @Test fun overviewShowsConfiguredAdGuardWhenHealthy() {
        val state = UiState(authenticated = true, dashboard = Dashboard(adguard = AdGuardStatus(
            configured = true, online = true, running = true, protection_enabled = true, filtering_enabled = true)))
        compose.setContent { MaterialTheme { Overview(state, {}, {}, {}) } }
        compose.onNodeWithText("AdGuard Home").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Protection active").assertIsDisplayed()
    }
}
