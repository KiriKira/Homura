package com.callagent.rtcmedia

/** Configuration counts only: never retain or log URLs, credentials, tokens or source names.
 * No candidates proves that relay ICE is unusable, not why a TURN transaction failed. */
internal class RelayGatheringDiagnostics(
    nodeServers: List<RelayIceServer>,
    private val nodeEnabled: Boolean,
    additionalSources: List<RelaySource>,
    selection: RelaySelection
) {
    private val nodeSources = if (nodeServers.isEmpty()) 0 else 1
    private val nodeServerCount = nodeServers.size
    private val externalConfigured = additionalSources.size
    private val externalEnabled = additionalSources.count { it.enabled }
    private val externalSelected: Int
    private val selectedTurnUrls: Int
    init {
        val selectedUrls = selection.servers.flatMap { it.urls }.toSet()
        externalSelected = selectedUrls.flatMap { selection.sourcesForUrl(it) }
            .filter { it != RelaySources.NODE_ID }.toSet().size
        selectedTurnUrls = selectedUrls.count {
            it.startsWith("turn:", ignoreCase = true) || it.startsWith("turns:", ignoreCase = true)
        }
    }

    fun completed(forceRelay: Boolean, localCandidates: Int): String? {
        if (!forceRelay || localCandidates != 0) return null
        val reason = if (selectedTurnUrls == 0) "no_turn_servers_configured" else "turn_candidates_unavailable"
        return "relay_no_local_candidates reason=$reason force_relay=true local_candidates=0" +
            " external_sources_configured=$externalConfigured external_sources_enabled=$externalEnabled" +
            " external_sources_selected=$externalSelected node_sources_configured=$nodeSources" +
            " node_servers_configured=$nodeServerCount node_enabled=$nodeEnabled selected_turn_urls=$selectedTurnUrls"
    }
}
