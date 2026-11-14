package com.callagent.rtcmedia

import java.net.URI

/** Credentials are deliberately excluded from diagnostics, including toString(). */
class RelayIceServer(val urls: List<String>, val username: String? = null, val credential: String? = null) {
    override fun toString() = "RelayIceServer(redacted)"
}

class RelaySource(val id: String, val servers: List<RelayIceServer>, val enabled: Boolean = true, val pinned: Boolean = false, val staticOnly: Boolean = true, val rankMillis:Long?=null) {
    init { require(id.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "invalid_source_id" } }
    override fun toString() = "RelaySource(id=$id)"
}

/** Immutable input to native ICE, with provenance retained independently of credentials. */
class RelaySelection internal constructor(val servers: List<RelayIceServer>, private val origins: Map<String, Set<String>>, private val credentialOrigins: Map<Triple<String,String?,String?>,Set<String>> = emptyMap(), val truncated:Boolean=false, val fallbackReason:String?=null) {
    fun sourcesForUrl(url: String): Set<String> = origins[url].orEmpty()
    internal fun withServers(servers:List<RelayIceServer>):RelaySelection {
        val chosen=linkedMapOf<String,Set<String>>()
        servers.forEach{s->s.urls.forEach{url->chosen[url]=credentialOrigins[Triple(url,s.username,s.credential)]?:origins[url].orEmpty()}}
        return RelaySelection(servers,chosen,credentialOrigins,truncated,fallbackReason)
    }
    val hasAdditionalSources:Boolean get()=origins.values.any{ids->ids.any{it!=RelaySources.NODE_ID}}
    internal fun fallback()=RelaySelection(servers,origins,credentialOrigins,fallbackReason="invalid_additional_configuration")
    internal fun truncated()=RelaySelection(servers,origins,credentialOrigins,truncated=true)
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
    fun select(node: List<RelayIceServer>, additional: List<RelaySource> = emptyList(), nodeRank:Long?=null,nodePinned:Boolean=false): RelaySelection {
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
        val credentialOrigins=linkedMapOf<Triple<String,String?,String?>,MutableSet<String>>()
        val keys = mutableSetOf<Triple<String, String?, String?>>()
        fun append(server: RelayIceServer, id: String) {
            val urls = server.urls.filter { url ->
                origin(url, id)
                credentialOrigins.getOrPut(Triple(url,server.username,server.credential)){linkedSetOf()}.add(id)
                keys.add(Triple(url, server.username, server.credential))
            }
            if (urls.isNotEmpty()) entries.add(RelayIceServer(urls.toList(), server.username, server.credential))
        }
        enabled.forEach { source ->
            require(source.servers.size <= MAX_SERVERS) { "server_budget" }
            source.servers.forEach { server ->
                require(server.urls.isNotEmpty() && server.urls.size <= MAX_URLS) { "url_budget" }
                require(!source.staticOnly || (!server.username.isNullOrBlank() && !server.credential.isNullOrBlank())) { "static_credentials_required" }
                server.urls.forEach { validateTurnUrl(it, !source.staticOnly) }
            }
        }
        val ordered=(enabled+RelaySource(NODE_ID,node,pinned=nodePinned,staticOnly=false,rankMillis=nodeRank))
            .sortedWith(compareByDescending<RelaySource>{it.pinned}.thenBy{it.rankMillis?:Long.MAX_VALUE}.thenBy{if(it.id==NODE_ID)0 else 1})
        // Reserve the complete node configuration first, independently of its display rank.
        // Charge only exact unique credentials/URLs, retaining whole extra sources in priority order.
        val reserved=mutableSetOf<Triple<String,String?,String?>>()
        fun cost(servers:List<RelayIceServer>,seen:MutableSet<Triple<String,String?,String?>>):Int=
            servers.count{server->var fresh=false;server.urls.forEach{if(seen.add(Triple(it,server.username,server.credential)))fresh=true};fresh}
        val groups=cost(node,reserved)
        if(groups>MAX_SERVERS||reserved.size>MAX_URLS)return select(node).truncated()
        val chosen=mutableSetOf(NODE_ID)
        var truncated=false
        for(source in ordered.filter{it.id!=NODE_ID}){
            val proposed=mutableSetOf<Triple<String,String?,String?>>()
            val proposedGroups=cost(ordered.filter{it.id in chosen||it.id==source.id}.flatMap{it.servers},proposed)
            if(proposedGroups>MAX_SERVERS||proposed.size>MAX_URLS){truncated=true;continue}
            chosen.add(source.id)
        }
        ordered.filter{it.id in chosen}.forEach{source->source.servers.forEach{append(it,source.id)}}
        return RelaySelection(entries.toList(), origins.mapValues { it.value.toSet() },credentialOrigins.mapValues{it.value.toSet()},truncated)
    }

    /** Configuration editors keep strict validation; media construction fails back to node input. */
    fun selectForCall(node:List<RelayIceServer>,additional:List<RelaySource> = emptyList(),nodeRank:Long?=null,nodePinned:Boolean=false):RelaySelection =
        try{select(node,additional,nodeRank,nodePinned)}catch(_:IllegalArgumentException){select(node).fallback()}

    /** Postcondition for the bounded, authenticated credential fallback. */
    fun requireNativeCompatible(selection: RelaySelection) {
        val credentials = mutableMapOf<String, Pair<String?, String?>>()
        selection.servers.forEach { server -> server.urls.forEach { url ->
            val value = server.username to server.credential
            require(credentials[url]?.let { it == value } != false) { "relay_credential_fallback_required" }
            credentials[url] = value
        } }
    }

    private fun validateTurnUrl(url: String, allowStun: Boolean = false) {
        require(url.length <= 2048 && url.none { it.isWhitespace() || it.isISOControl() }) { "invalid_turn_url" }
        val scheme = url.substringBefore(':')
        require(scheme == "turn" || scheme == "turns" || (allowStun && scheme == "stun")) { "invalid_turn_scheme" }
        val uri = try { URI("$scheme://${url.substringAfter(':')}") } catch (_: Exception) { throw IllegalArgumentException("invalid_turn_url") }
        require(!uri.host.isNullOrEmpty() && uri.rawUserInfo == null && uri.rawFragment == null && uri.rawPath.isNullOrEmpty()) { "invalid_turn_url" }
        require(uri.port == -1 || uri.port in 1..65535) { "invalid_turn_port" }
        require(uri.rawQuery == null || uri.rawQuery in setOf("transport=udp", "transport=tcp")) { "invalid_turn_transport" }
        require(scheme != "turns" || uri.rawQuery != "transport=udp") { "invalid_turn_transport" }
    }
}
