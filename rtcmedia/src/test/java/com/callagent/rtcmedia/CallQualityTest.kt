package com.callagent.rtcmedia

import org.junit.Assert.*
import org.junit.Test

class CallQualityTest {
    private fun report(rxPackets: Long, lost: Long, rxBytes: Long, samples: Long, concealed: Long, jitter: Double,
                       local: String = "relay", network: String = "wifi", address: String = "203.0.113.5") = listOf(
        QualityStat("in", "inbound-rtp", mapOf("kind" to "audio", "packetsReceived" to rxPackets, "packetsLost" to lost,
            "bytesReceived" to rxBytes, "totalSamplesReceived" to samples, "concealedSamples" to concealed, "concealmentEvents" to 1L,
            "jitter" to jitter, "jitterBufferDelay" to samples / 1000.0 * 0.06, "jitterBufferEmittedCount" to samples / 1000,
            "codecId" to "codec", "audioLevel" to 0.25)),
        QualityStat("out", "outbound-rtp", mapOf("kind" to "audio", "bytesSent" to rxBytes)),
        QualityStat("codec", "codec", mapOf("mimeType" to "audio/opus", "clockRate" to 48000L)),
        QualityStat("remote-in", "remote-inbound-rtp", mapOf("kind" to "audio", "roundTripTime" to 0.09, "fractionLost" to 0.01)),
        QualityStat("t", "transport", mapOf("selectedCandidatePairId" to "pair")),
        QualityStat("pair", "candidate-pair", mapOf("localCandidateId" to "l", "remoteCandidateId" to "r", "currentRoundTripTime" to 0.08, "availableOutgoingBitrate" to 300000.0)),
        QualityStat("l", "local-candidate", mapOf("candidateType" to local, "networkType" to network, "protocol" to "udp", "relayProtocol" to "tls", "address" to address)),
        QualityStat("r", "remote-candidate", mapOf("candidateType" to "srflx", "protocol" to "udp"))
    )

    @Test fun reportsIntervalDeltasPathAndSummaryWithoutAddresses() {
        var now = 0L
        val quality = CallQuality { now }
        now = 5_000_000_000L
        val first = quality.sample(report(250, 0, 20_000, 240_000, 0, 0.010)).single()
        assertTrue(first, first.startsWith("quality interval_ms=5000 path=relay/srflx net=wifi proto=udp relay_proto=tls family=v4"))
        assertTrue(first, first.contains(" rx_pkts=250 rx_lost=0 rx_loss_pct=0.0"))
        assertTrue(first, first.contains(" rtt_ms=80.0 remote_rtt_ms=90.0"))
        assertTrue(first, first.contains(" in_kbps=32.0"))
        assertTrue(first, first.contains(" codec=opus/48000"))
        assertFalse("no addresses in diagnostics", first.contains("203.0.113.5"))
        now = 10_000_000_000L
        val second = quality.sample(report(490, 10, 40_000, 480_000, 4_800, 0.030, local = "srflx", network = "cellular", address = "2001:db8::1"))
        assertEquals(2, second.size)
        assertTrue(second[0], second[0].startsWith("path_changed from=relay/srflx,wifi,udp,tls,v4 to=srflx/srflx,cellular,udp,tls,v6"))
        assertTrue(second[1], second[1].contains(" rx_pkts=240 rx_lost=10 rx_loss_pct=4.0"))
        assertTrue(second[1], second[1].contains(" concealed_pct=2.0"))
        assertTrue(second[1], second[1].contains(" jitter_ms=30.0"))
        assertFalse(second.joinToString(), second.joinToString().contains("2001:db8"))
        val summary = checkNotNull(quality.summary())
        assertTrue(summary, summary.startsWith("quality_summary duration_s=10.0 samples=2 rx_pkts=490 rx_lost=10"))
        assertTrue(summary, summary.contains(" jitter_max_ms=30.0"))
        assertTrue(summary, summary.contains(" path_changes=1 paths=relay/srflx,srflx/srflx"))
    }

    @Test fun noPathBeforeIceSelectsOneIsNeitherAPathNorAChange() {
        var now = 0L
        val quality = CallQuality { now }
        now = 1_000_000_000L
        val early = report(0, 0, 0, 0, 0, 0.0).filter { it.type != "transport" && it.type != "candidate-pair" }
        assertTrue(quality.sample(early).single().contains(" path=none/none "))
        now = 6_000_000_000L
        assertEquals(1, quality.sample(report(250, 0, 20_000, 240_000, 0, 0.010)).size)
        val summary = checkNotNull(quality.summary())
        assertTrue(summary, summary.endsWith(" path_changes=0 paths=relay/srflx"))
    }

    @Test fun noSampleMeansNoSummary() { assertNull(CallQuality().summary()) }
}
