package com.homewifi.manager

import org.junit.Assert.*
import org.junit.Test

class UpdateTests {
    private val release = UpdateRelease(4, "1.3.0", "https://github.com/$RELEASE_REPOSITORY/releases/download/v1.3.0/HomeWiFi-v1.3.0.apk", "ab".repeat(32), 123456, 26)
    @Test fun onlyNewerCompatibleVersionsAreOffered() {
        assertTrue(validateUpdate(release, "v1.3.0", 3, 36))
        assertFalse(validateUpdate(release, "v1.3.0", 4, 36))
        assertFalse(validateUpdate(release, "v1.3.0", 5, 36))
        assertFalse(validateUpdate(release.copy(minSdk = 37), "v1.3.0", 3, 36))
    }
    @Test fun badSourcesIntegrityAndMismatchedReleasesAreRejected() {
        val invalid = listOf(release.copy(apkUrl = "http://github.com/$RELEASE_REPOSITORY/releases/download/v1.3.0/HomeWiFi-v1.3.0.apk"),
            release.copy(apkUrl = release.apkUrl.replace("github.com", "github.com.evil.invalid")),
            release.copy(apkUrl = release.apkUrl+"?redirect=elsewhere"), release.copy(sha256 = "bad"),
            release.copy(size = MAX_UPDATE_BYTES+1), release.copy(size = 0), release.copy(versionName = "9.0.0"))
        invalid.forEach { candidate ->
            assertTrue(runCatching { validateUpdate(candidate, "v1.3.0", 3, 36) }.isFailure)
        }
        assertTrue(runCatching { validateUpdate(release, "v1.3.0-beta", 3, 36) }.isFailure)
    }
    @Test fun releaseMetadataReadsAreBounded() {
        assertEquals("small", readLimited("small".byteInputStream(), 5))
        assertTrue(runCatching { readLimited("too much".byteInputStream(), 5) }.isFailure)
    }
    @Test fun apkDigestUsesSha256() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", updateDigest("abc".toByteArray()))
    }
}
