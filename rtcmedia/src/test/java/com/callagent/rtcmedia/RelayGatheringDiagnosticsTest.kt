package com.callagent.rtcmedia

import org.junit.Assert.*
import org.junit.Test

/** Regression tests authored for CI; not compiled or run in the toolchain-free repair workspace. */
class RelayGatheringDiagnosticsTest {
    @Test fun forceRelayWithoutAnyTurnInputExplainsMissingConfiguration() {
        val diagnostic = RelayGatheringDiagnostics(emptyList(), true, emptyList(), RelaySources.select(emptyList()))
        val event = checkNotNull(diagnostic.completed(true, 0))
        assertTrue(event.startsWith("relay_no_local_candidates reason=no_turn_servers_configured "))
        assertTrue(event.contains("external_sources_configured=0 "))
        assertTrue(event.contains("node_sources_configured=0 "))
        assertTrue(event.contains("selected_turn_urls=0"))
    }

    @Test fun selectedTurnWithoutCandidatesReportsCountsAndNoSecrets() {
        val node = listOf(RelayIceServer(listOf("stun:node.invalid:3478", "turn:node.invalid:3478"), "node-user", "node-secret"))
        val external = listOf(RelaySource("private-source", listOf(
            RelayIceServer(listOf("turns:external.invalid:443?transport=tcp"), "external-user", "external-secret")
        )))
        val diagnostic = RelayGatheringDiagnostics(node, true, external, RelaySources.select(node, external))
        val event = checkNotNull(diagnostic.completed(true, 0))
        assertTrue(event.contains("reason=turn_candidates_unavailable "))
        assertTrue(event.contains("external_sources_configured=1 external_sources_enabled=1 external_sources_selected=1 "))
        assertTrue(event.contains("node_sources_configured=1 node_servers_configured=1 node_enabled=true selected_turn_urls=2"))
        for (secret in listOf("node.invalid", "external.invalid", "node-user", "node-secret", "external-user", "external-secret", "private-source")) {
            assertFalse("diagnostics must redact $secret", event.contains(secret))
        }
    }

    @Test fun configuredButDisabledSourcesAreNotReportedAsSelected() {
        val node = listOf(RelayIceServer(listOf("turn:node.invalid:3478")))
        val disabled = listOf(RelaySource("disabled", listOf(
            RelayIceServer(listOf("turn:external.invalid:3478"), "user", "secret")
        ), enabled = false))
        val selection = RelaySources.select(emptyList(), disabled)
        val event = checkNotNull(RelayGatheringDiagnostics(node, false, disabled, selection).completed(true, 0))
        assertTrue(event.contains("reason=no_turn_servers_configured "))
        assertTrue(event.contains("external_sources_configured=1 external_sources_enabled=0 external_sources_selected=0 "))
        assertTrue(event.contains("node_enabled=false selected_turn_urls=0"))
    }

    @Test fun invalidExternalConfigurationIsDistinguishedFromNoExternalConfiguration() {
        val invalid = listOf(RelaySource("bad", listOf(RelayIceServer(listOf("https://invalid.invalid")))))
        val selection = RelaySources.selectForCall(emptyList(), invalid)
        assertNotNull(selection.fallbackReason)
        val event = checkNotNull(RelayGatheringDiagnostics(emptyList(), true, invalid, selection).completed(true, 0))
        assertTrue(event.contains("external_sources_configured=1 external_sources_enabled=1 external_sources_selected=0 "))
        assertTrue(event.contains("selected_turn_urls=0"))
    }

    @Test fun stunOnlyInputDoesNotCountAsTurnConfiguration() {
        val node = listOf(RelayIceServer(listOf("stun:node.invalid:3478")))
        val event = checkNotNull(RelayGatheringDiagnostics(node, true, emptyList(), RelaySources.select(node)).completed(true, 0))
        assertTrue(event.contains("reason=no_turn_servers_configured "))
        assertTrue(event.contains("selected_turn_urls=0"))
    }

    @Test fun ordinaryIceAndSuccessfulRelayGatheringDoNotWarn() {
        val diagnostic = RelayGatheringDiagnostics(emptyList(), true, emptyList(), RelaySources.select(emptyList()))
        assertNull(diagnostic.completed(false, 0))
        assertNull(diagnostic.completed(true, 1))
    }
}
