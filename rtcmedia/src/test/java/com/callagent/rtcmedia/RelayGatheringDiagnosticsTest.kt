package com.callagent.rtcmedia

import org.junit.Assert.*
import org.junit.Test

class RelayGatheringDiagnosticsTest {
    @Test fun relayOnlyEmptyGatheringReportsConfigurationCountsOnceWithoutSecrets() {
        val node=listOf(RelayIceServer(listOf("turn:node:3478"),"node-user","node-secret"))
        val extra=listOf(RelaySource("backup",listOf(RelayIceServer(listOf("turn:backup:3478"),"external-user","external-secret"))))
        val diagnostics=RelayGatheringDiagnostics(true,node,true,extra,RelaySources.select(node,extra))
        val event=checkNotNull(diagnostics.completed(0))
        assertTrue(event.startsWith("relay_no_local_candidates "))
        assertTrue(event.contains("external_configured=true external_sources=1 external_enabled_sources=1"))
        assertTrue(event.contains("node_sources=1"))
        assertTrue(event.contains("selected_sources=2 selected_turn_urls=2"))
        for(secret in listOf("node-user","node-secret","external-user","external-secret","turn:"))assertFalse(event.contains(secret))
        assertNull(diagnostics.completed(0))
    }
    @Test fun directPolicyOrNonemptyGatheringDoesNotWarn() {
        val empty=RelaySources.select(emptyList())
        assertNull(RelayGatheringDiagnostics(false,emptyList(),true,emptyList(),empty).completed(0))
        assertNull(RelayGatheringDiagnostics(true,emptyList(),true,emptyList(),empty).completed(1))
        val warning=checkNotNull(RelayGatheringDiagnostics(true,emptyList(),false,emptyList(),empty).completed(0))
        assertTrue(warning.contains("selected_sources=0 selected_turn_urls=0"))
    }
}
