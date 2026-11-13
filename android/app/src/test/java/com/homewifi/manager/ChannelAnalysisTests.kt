package com.homewifi.manager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelAnalysisTests {
    private fun net(bssid: String, mhz: Int, level: Int) = NearbyNetwork(bssid, "n-$bssid", mhz, level)

    @Test fun mapsFrequenciesToChannels() {
        assertEquals(1, channelForFrequency(2412))
        assertEquals(6, channelForFrequency(2437))
        assertEquals(13, channelForFrequency(2472))
        assertEquals(14, channelForFrequency(2484))
        assertNull(channelForFrequency(5180))
        assertNull(channelForFrequency(2413))
    }

    @Test fun emptyAirRecommendsChannelOne() {
        val report = analyzeChannels(emptyList())
        assertEquals(1, report.best)
        assertEquals(13, report.loads.size)
    }

    @Test fun avoidsCrowdedChannelsAndIgnoresFiveGhz() {
        val scans = listOf(net("a", 2412, -40), net("b", 2412, -50), net("c", 2437, -45), net("d", 5180, -30))
        val report = analyzeChannels(scans)
        assertEquals(11, report.best)
        assertEquals(2, report.loads.first { it.channel == 1 }.networks)
        assertTrue(report.loads.first { it.channel == 1 }.score > report.loads.first { it.channel == 11 }.score)
    }

    @Test fun neighborsOverlapButFarChannelsDoNot() {
        val report = analyzeChannels(listOf(net("a", 2437, -40)))
        val byChannel = report.loads.associateBy { it.channel }
        assertTrue(byChannel.getValue(7).score > 0.0)
        assertEquals(0.0, byChannel.getValue(1).score, 0.0)
        assertEquals(0.0, byChannel.getValue(11).score, 0.0)
    }

    @Test fun excludesTheManagedNetwork() {
        val scans = listOf(net("AA:BB", 2462, -30), net("cc", 2412, -70))
        val report = analyzeChannels(scans, excludeBssid = "aa:bb")
        assertEquals(0.0, report.loads.first { it.channel == 11 }.score, 0.0)
    }
}
