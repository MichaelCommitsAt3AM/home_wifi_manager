package com.homewifi.manager

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in download verification against a published release; never opens the installer. */
class UpdaterReleaseTest {
    @Test fun publishedApkDownloadsAndMatchesInstalledSigningIdentity() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifyGitHubUpdate") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("app-updates", 0).edit().remove("checked-at").apply()
        val updater = AppUpdater(context)
        updater.check(true)
        val available = withTimeout(60_000) { updater.state.first { it.release != null || (!it.checking && it.message != null) } }
        assertNotNull("Run with a debug version code below the latest published release", available.release)
        updater.download()
        val downloaded = withTimeout(180_000) { updater.state.first { it.stage == "ready" || it.stage == "error" } }
        assertEquals(downloaded.message, "ready", downloaded.stage)
        println("GITHUB_UPDATE_VERIFIED version=${downloaded.release!!.versionName} signature_and_checksum=true")
    }
}
