package com.homewifi.manager

import kotlin.math.abs
import kotlin.math.pow

/** One nearby 2.4 GHz access point as seen by the phone. */
data class NearbyNetwork(val bssid: String, val ssid: String, val frequencyMhz: Int, val levelDbm: Int) {
    val channel: Int? get() = channelForFrequency(frequencyMhz)
}

data class ChannelLoad(val channel: Int, val networks: Int, val score: Double)
data class ChannelReport(val loads: List<ChannelLoad>, val best: Int?, val maxScore: Double)

/** 2.4 GHz channel for a frequency, or null for 5/6 GHz and anything else. */
fun channelForFrequency(mhz: Int): Int? = when (mhz) {
    in 2412..2472 -> if ((mhz - 2407) % 5 == 0) (mhz - 2407) / 5 else null
    2484 -> 14
    else -> null
}

/** Roughly 0 (barely audible) to 1 (very strong), non-linear so strong neighbors dominate. */
internal fun signalWeight(levelDbm: Int): Double = ((levelDbm + 100) / 60.0).coerceIn(0.0, 1.0).pow(2)

/**
 * Scores each selectable channel by how much nearby traffic overlaps it. A 20 MHz channel overlaps
 * neighbors up to four channels away, fading with distance. Channels 1, 6 and 11 win ties because they
 * do not overlap each other. [excludeBssid] drops the network the user is managing so it does not
 * count against its own channel.
 */
fun analyzeChannels(
    scans: List<NearbyNetwork>, candidates: List<Int> = (1..13).toList(), excludeBssid: String? = null
): ChannelReport {
    val others = scans.filter { !it.bssid.equals(excludeBssid, true) && it.channel != null }
    val loads = candidates.map { candidate ->
        var score = 0.0
        var count = 0
        for (net in others) {
            val distance = abs(net.channel!! - candidate)
            if (distance <= 4) {
                score += signalWeight(net.levelDbm) * (1.0 - distance / 5.0)
                if (distance == 0) count++
            }
        }
        ChannelLoad(candidate, count, score)
    }
    val best = loads.minWithOrNull(compareBy<ChannelLoad>({ it.score + if (it.channel in setOf(1, 6, 11)) 0.0 else 0.05 }, { it.channel }))?.channel
    return ChannelReport(loads, best, loads.maxOfOrNull { it.score } ?: 0.0)
}
