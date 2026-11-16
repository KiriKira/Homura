package com.callagent.rtcmedia

import java.util.Locale

/** One WebRTC stats entry, detached from the native report for testing. */
internal class QualityStat(val id: String, val type: String, val members: Map<String, Any?>)

/**
 * Audio-quality diagnostics for one call. Each [sample] turns a stats report
 * into a `quality` line for the interval since the previous sample; [summary]
 * describes the whole call. Lines carry the network path (candidate types,
 * Wi-Fi or cellular, UDP/TCP/TLS, IPv4/IPv6) but never addresses, ports,
 * URLs or credentials.
 */
internal class CallQuality(private val clock: () -> Long = System::nanoTime) {
    private class Counters(
        val at: Long, val rxPackets: Long, val rxLost: Long, val rxBytes: Long, val txBytes: Long,
        val samples: Long, val concealed: Long, val concealEvents: Long, val jbDelay: Double, val jbEmitted: Long
    )
    private val startedAt = clock()
    private var previous: Counters? = null
    private var path: String? = null
    private val paths = linkedSetOf<String>()
    private var pathChanges = 0
    private var sampleCount = 0
    private var jitterSum = 0.0
    private var jitterMax = 0.0
    private var rttSum = 0.0
    private var rttCount = 0
    private var rttMax = 0.0

    private fun Map<String, Any?>.num(key: String): Double? = (this[key] as? Number)?.toDouble()
    private fun Map<String, Any?>.long(key: String): Long = (this[key] as? Number)?.toLong() ?: 0L
    private fun Map<String, Any?>.text(key: String): String? = (this[key] as? String)?.takeIf { it.isNotBlank() }
    private fun f1(value: Double) = String.format(Locale.ROOT, "%.1f", value)
    private fun ms(seconds: Double?) = seconds?.let { f1(it * 1000) } ?: "na"
    private fun pct(part: Long, whole: Long) = if (whole <= 0) "na" else f1(part * 100.0 / whole)

    /** Returns the interval line, preceded by a `path_changed` line when the selected path moved. */
    @Synchronized fun sample(stats: Collection<QualityStat>): List<String> {
        val byId = stats.associateBy { it.id }
        val inbound = stats.filter { it.type == "inbound-rtp" && it.members["kind"] != "video" }
        val outbound = stats.filter { it.type == "outbound-rtp" && it.members["kind"] != "video" }
        val remoteInbound = stats.firstOrNull { it.type == "remote-inbound-rtp" && it.members["kind"] != "video" }?.members
        val transport = stats.firstOrNull { it.type == "transport" && it.members["selectedCandidatePairId"] != null }
        val pair = (transport?.members?.get("selectedCandidatePairId") as? String)?.let(byId::get)?.members
            ?: stats.firstOrNull { it.type == "candidate-pair" && it.members["nominated"] == true && it.members["state"] == "succeeded" }?.members
        val local = (pair?.get("localCandidateId") as? String)?.let(byId::get)?.members
        val remote = (pair?.get("remoteCandidateId") as? String)?.let(byId::get)?.members
        val codec = (inbound.firstOrNull()?.members?.get("codecId") as? String)?.let(byId::get)?.members

        val now = Counters(
            clock(),
            inbound.sumOf { it.members.long("packetsReceived") }, inbound.sumOf { it.members.long("packetsLost") },
            inbound.sumOf { it.members.long("bytesReceived") }, outbound.sumOf { it.members.long("bytesSent") },
            inbound.sumOf { it.members.long("totalSamplesReceived") }, inbound.sumOf { it.members.long("concealedSamples") },
            inbound.sumOf { it.members.long("concealmentEvents") },
            inbound.sumOf { it.members.num("jitterBufferDelay") ?: 0.0 }, inbound.sumOf { it.members.long("jitterBufferEmittedCount") }
        )
        val before = previous ?: Counters(startedAt, 0, 0, 0, 0, 0, 0, 0, 0.0, 0)
        previous = now
        sampleCount++

        val family = local?.text("address")?.let { if (it.contains(':')) "v6" else "v4" }
            ?: local?.text("ip")?.let { if (it.contains(':')) "v6" else "v4" } ?: "na"
        val describe = listOf(
            "${local?.text("candidateType") ?: "none"}/${remote?.text("candidateType") ?: "none"}",
            local?.text("networkType") ?: "na", local?.text("protocol") ?: "na",
            local?.text("relayProtocol") ?: "-", family
        ).joinToString(" ")
        val lines = mutableListOf<String>()
        if (path != null && path != describe) {
            pathChanges++
            lines += "path_changed from=${path!!.replace(' ', ',')} to=${describe.replace(' ', ',')}"
        }
        path = describe
        paths += describe.substringBefore(' ')

        val seconds = ((now.at - before.at) / 1e9).coerceAtLeast(0.001)
        val received = now.rxPackets - before.rxPackets
        val lost = (now.rxLost - before.rxLost).coerceAtLeast(0)
        val jitter = inbound.mapNotNull { it.members.num("jitter") }.maxOrNull()
        jitter?.let { jitterSum += it; jitterMax = maxOf(jitterMax, it) }
        val rtt = pair?.num("currentRoundTripTime") ?: remoteInbound?.num("roundTripTime")
        rtt?.let { rttSum += it; rttCount++; rttMax = maxOf(rttMax, it) }
        val emitted = now.jbEmitted - before.jbEmitted
        val jitterBuffer = if (emitted > 0) (now.jbDelay - before.jbDelay) / emitted else null
        val (localType, remoteType, net, proto, relay) = describe.split(' ').let { parts ->
            val types = parts[0].split('/')
            listOf(types[0], types.getOrElse(1) { "none" }, parts[1], parts[2], parts[3])
        }
        lines += "quality interval_ms=${(seconds * 1000).toLong()} path=$localType/$remoteType net=$net proto=$proto relay_proto=$relay family=$family" +
            " rtt_ms=${ms(rtt)} remote_rtt_ms=${ms(remoteInbound?.num("roundTripTime"))}" +
            " in_kbps=${f1((now.rxBytes - before.rxBytes) * 8 / 1000.0 / seconds)} out_kbps=${f1((now.txBytes - before.txBytes) * 8 / 1000.0 / seconds)}" +
            " avail_out_kbps=${pair?.num("availableOutgoingBitrate")?.let { f1(it / 1000) } ?: "na"}" +
            " rx_pkts=$received rx_lost=$lost rx_loss_pct=${pct(lost, received + lost)}" +
            " remote_loss_pct=${remoteInbound?.num("fractionLost")?.let { f1(it * 100) } ?: "na"}" +
            " jitter_ms=${ms(jitter)} jb_ms=${ms(jitterBuffer)}" +
            " concealed_pct=${pct(now.concealed - before.concealed, now.samples - before.samples)} conceal_events=${now.concealEvents - before.concealEvents}" +
            " codec=${codec?.text("mimeType")?.substringAfter('/')?.lowercase(Locale.ROOT) ?: "na"}/${codec?.long("clockRate")?.takeIf { it > 0 } ?: "na"}" +
            " audio_level=${inbound.firstOrNull()?.members?.num("audioLevel")?.let { String.format(Locale.ROOT, "%.3f", it) } ?: "na"}"
        return lines
    }

    /** The whole call, from the first sample to the last; null before any sample. */
    @Synchronized fun summary(): String? {
        val last = previous ?: return null
        val received = last.rxPackets
        val lost = last.rxLost
        return "quality_summary duration_s=${f1((last.at - startedAt) / 1e9)} samples=$sampleCount" +
            " rx_pkts=$received rx_lost=$lost rx_loss_pct=${pct(lost, received + lost)}" +
            " jitter_avg_ms=${if (sampleCount > 0) f1(jitterSum / sampleCount * 1000) else "na"} jitter_max_ms=${f1(jitterMax * 1000)}" +
            " rtt_avg_ms=${if (rttCount > 0) f1(rttSum / rttCount * 1000) else "na"} rtt_max_ms=${f1(rttMax * 1000)}" +
            " jb_avg_ms=${if (last.jbEmitted > 0) f1(last.jbDelay / last.jbEmitted * 1000) else "na"}" +
            " concealed_pct=${pct(last.concealed, last.samples)} conceal_events=${last.concealEvents}" +
            " path_changes=$pathChanges paths=${paths.joinToString(",").ifEmpty { "none" }}"
    }
}
