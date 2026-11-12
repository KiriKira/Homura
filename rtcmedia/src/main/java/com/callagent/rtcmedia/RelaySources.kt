package com.callagent.rtcmedia

import java.net.URI

/** Credentials are deliberately excluded from diagnostics, including toString(). */
class RelayIceServer(val urls: List<String>, val username: String? = null, val credential: String? = null) {
    override fun toString() = "RelayIceServer(redacted)"
}

class RelaySource(val id: String, val servers: List<RelayIceServer>, val enabled: Boolean = true) {
    init { require(id.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "invalid_source_id" } }
    override fun toString() = "RelaySource(id=$id)"
}

/** Immutable input to native ICE, with provenance retained independently of credentials. */
class RelaySelection internal constructor(val servers: List<RelayIceServer>, private val origins: Map<String, Set<String>>) {
    fun sourcesForUrl(url: String): Set<String> = origins[url].orEmpty()
    override fun toString() = "RelaySelection(servers=${servers.size})"
}

object RelaySources {
    const val NODE_ID = "current-node"
    const val MAX_SOURCES = 8
    const val MAX_SERVERS = 16
    const val MAX_URLS = 32

    /** Node-only mode preserves the original wire order, grouping and optional credentials.
     * Additional sources are static TURN inputs; this layer performs no I/O or media admission.
     * URL counts are configuration bounds, not an estimate of native candidate counts.
     */
    fun select(node: List<RelayIceServer>, additional: List<RelaySource> = emptyList()): RelaySelection {
        require(additional.size < MAX_SOURCES) { "source_budget" }
        require(additional.none { it.id == NODE_ID } && additional.map { it.id }.distinct().size == additional.size) { "duplicate_source_id" }
        val origins = linkedMapOf<String, MutableSet<String>>()
        fun origin(url: String, id: String) { origins.getOrPut(url) { linkedSetOf() }.add(id) }
        val enabled = additional.filter { it.enabled }
        if (enabled.isEmpty()) {
            node.forEach { server -> server.urls.forEach { origin(it, NODE_ID) } }
            return RelaySelection(node.map { RelayIceServer(it.urls.toList(), it.username, it.credential) }, origins.mapValues { it.value.toSet() })
        }
        // Exact URL+credentials deduplication; never discard a different credential set.
        val entries = mutableListOf<RelayIceServer>()
        val keys = mutableSetOf<Triple<String, String?, String?>>()
        fun append(server: RelayIceServer, id: String) {
            val urls = server.urls.filter { url ->
                origin(url, id)
                keys.add(Triple(url, server.username, server.credential))
            }
            if (urls.isNotEmpty()) entries.add(RelayIceServer(urls.toList(), server.username, server.credential))
        }
        node.forEach { append(it, NODE_ID) }
        enabled.forEach { source ->
            require(source.servers.size <= MAX_SERVERS) { "server_budget" }
            source.servers.forEach { server ->
                require(server.urls.isNotEmpty() && server.urls.size <= MAX_URLS) { "url_budget" }
                require(!server.username.isNullOrBlank() && !server.credential.isNullOrBlank()) { "static_credentials_required" }
                server.urls.forEach(::validateTurnUrl)
                append(server, source.id)
            }
        }
        require(entries.size <= MAX_SERVERS && entries.sumOf { it.urls.size } <= MAX_URLS) { "ice_server_budget" }
        return RelaySelection(entries.toList(), origins.mapValues { it.value.toSet() })
    }

    /** The pinned native SDK's duplicate-address credential fallback is not yet validated.
     * Fail explicitly at this boundary instead of silently losing a usable credential set.
     */
    fun requireNativeCompatible(selection: RelaySelection) {
        val credentials = mutableMapOf<String, Pair<String?, String?>>()
        selection.servers.forEach { server -> server.urls.forEach { url ->
            val value = server.username to server.credential
            require(credentials[url]?.let { it == value } != false) { "relay_credential_fallback_required" }
            credentials[url] = value
        } }
    }

    private fun validateTurnUrl(url: String) {
        require(url.length <= 2048 && url.none { it.isWhitespace() || it.isISOControl() }) { "invalid_turn_url" }
        val scheme = url.substringBefore(':')
        require(scheme == "turn" || scheme == "turns") { "invalid_turn_scheme" }
        val uri = try { URI("$scheme://${url.substringAfter(':')}") } catch (_: Exception) { throw IllegalArgumentException("invalid_turn_url") }
        require(!uri.host.isNullOrEmpty() && uri.rawUserInfo == null && uri.rawFragment == null && uri.rawPath.isNullOrEmpty()) { "invalid_turn_url" }
        require(uri.port == -1 || uri.port in 1..65535) { "invalid_turn_port" }
        require(uri.rawQuery == null || uri.rawQuery in setOf("transport=udp", "transport=tcp")) { "invalid_turn_transport" }
        require(scheme != "turns" || uri.rawQuery != "transport=udp") { "invalid_turn_transport" }
    }
}
