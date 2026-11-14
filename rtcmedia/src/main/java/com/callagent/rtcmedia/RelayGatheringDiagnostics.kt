package com.callagent.rtcmedia

import java.util.concurrent.atomic.AtomicBoolean

/** Configuration counts only: never render URLs, source tokens or credentials. */
internal class RelayGatheringDiagnostics(
    private val forceRelay: Boolean,
    node: List<RelayIceServer>,
    nodeEnabled: Boolean,
    additional: List<RelaySource>,
    selected: RelaySelection
) {
    private val reported = AtomicBoolean()
    private val details: String
    init {
        val selectedUrls = selected.servers.flatMap { it.urls }.distinct()
        val nodeTurnUrls = node.flatMap { it.urls }.filter(::isTurn).distinct()
        val selectedTurnUrls = selectedUrls.filter(::isTurn)
        val sources = selectedTurnUrls.flatMap { selected.sourcesForUrl(it) }.toSet()
        details = "external_configured=${additional.isNotEmpty()} external_sources=${additional.size}" +
            " external_enabled_sources=${additional.count { it.enabled }}" +
            " node_enabled=$nodeEnabled node_sources=${if(nodeTurnUrls.isEmpty())0 else 1}" +
            " node_servers=${node.size} node_turn_urls=${nodeTurnUrls.size}" +
            " selected_sources=${sources.size} selected_turn_urls=${selectedTurnUrls.size}"
    }
    fun completed(localCandidates: Int): String? {
        if (!forceRelay || localCandidates != 0 || !reported.compareAndSet(false, true)) return null
        // Gathering proves absence of local candidates, NOT that credentials,
        // connectivity or quota is the specific cause. Probe events refine it.
        return "relay_no_local_candidates force_relay=true local_candidates=0 $details"
    }
    private fun isTurn(url: String) = url.startsWith("turn:") || url.startsWith("turns:")
}
