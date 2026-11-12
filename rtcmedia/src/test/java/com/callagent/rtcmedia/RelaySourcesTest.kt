package com.callagent.rtcmedia

import org.junit.Assert.*
import org.junit.Test

class RelaySourcesTest {
    private fun server(url: String, user: String = "user", password: String = "password") = RelayIceServer(listOf(url), user, password)
    private fun source(id: String = "backup", vararg servers: RelayIceServer) = RelaySource(id, servers.toList())
    private fun rejects(code: String, work: () -> Unit) {
        try { work(); fail("expected $code") } catch (e: IllegalArgumentException) { assertEquals(code, e.message) }
    }

    @Test fun nodeOnlyRetainsGroupingOrderDuplicatesAndAbsentCredentials() {
        val urls = listOf("stun:example.org:19302", "turn:example.org:49100", "turn:example.org:49100")
        val selected = RelaySources.select(listOf(RelayIceServer(urls), server("turn:other.org:3478")))
        assertEquals(2, selected.servers.size)
        assertEquals(urls, selected.servers[0].urls)
        assertNull(selected.servers[0].username)
        assertNull(selected.servers[0].credential)
        assertEquals(setOf(RelaySources.NODE_ID), selected.sourcesForUrl(urls[1]))
    }

    @Test fun staticTurnRetainsCustomTlsAndIpv6PortsIncluding53() {
        val urls = listOf("turn:example.org:49100?transport=udp", "turns:example.org:5349?transport=tcp", "turn:[2001:db8::1]:53?transport=tcp")
        val selected = RelaySources.select(emptyList(), listOf(source("static", RelayIceServer(urls, "u", "p"))))
        assertEquals(urls, selected.servers.single().urls)
        RelaySources.requireNativeCompatible(selected)
    }

    @Test fun exactDuplicateMergesWithoutLosingProvenance() {
        val url = "turn:relay.org:3478?transport=udp"
        val selected = RelaySources.select(listOf(server(url)), listOf(source("backup", server(url))))
        assertEquals(1, selected.servers.size)
        assertEquals(setOf(RelaySources.NODE_ID, "backup"), selected.sourcesForUrl(url))
    }

    @Test fun differentCredentialsSurviveResolutionAndFailExplicitlyAtNativeBoundary() {
        val url = "turn:relay.org:3478"
        val selected = RelaySources.select(listOf(server(url)), listOf(source("backup", server(url, password="other"))))
        assertEquals(2, selected.servers.size)
        assertEquals("other", selected.servers[1].credential)
        rejects("relay_credential_fallback_required") { RelaySources.requireNativeCompatible(selected) }
    }

    @Test fun disabledStaticSourceDoesNotChangeNodeOrValidateUnusedCredentials() {
        val selected = RelaySources.select(listOf(server("turn:node:12345")), listOf(RelaySource("disabled", listOf(RelayIceServer(listOf("invalid"))), false)))
        assertEquals(listOf("turn:node:12345"), selected.servers.single().urls)
    }

    @Test fun rejectsAmbiguousSourceIdentity() {
        rejects("duplicate_source_id") { RelaySources.select(emptyList(), listOf(source("same"), source("same"))) }
        rejects("duplicate_source_id") { RelaySources.select(emptyList(), listOf(source(RelaySources.NODE_ID))) }
        rejects("invalid_source_id") { source("line\nbreak") }
    }

    @Test fun rejectsUnsafeStaticUrlsWithoutExposingTheirContents() {
        for (url in listOf("turn:user:secret@host:3478", "turn:host:3478/path", "turn:host:3478#secret", "turn:host:3478\n")) {
            rejects("invalid_turn_url") { RelaySources.select(emptyList(), listOf(source("bad", server(url)))) }
        }
        rejects("invalid_turn_scheme") { RelaySources.select(emptyList(), listOf(source("bad", server("https://host/secret")))) }
        rejects("invalid_turn_port") { RelaySources.select(emptyList(), listOf(source("bad", server("turn:host:65536")))) }
        rejects("invalid_turn_transport") { RelaySources.select(emptyList(), listOf(source("bad", server("turns:host:443?transport=udp")))) }
    }

    @Test fun requiresStaticCredentials() {
        rejects("static_credentials_required") { RelaySources.select(emptyList(), listOf(source("bad", RelayIceServer(listOf("turn:host:3478"))))) }
    }

    @Test fun budgetsAreEnforcedBeforeNativeAllocation() {
        rejects("source_budget") { RelaySources.select(emptyList(), (1..8).map { source("s$it") }) }
        val many = (1..17).map { server("turn:host$it:3478") }
        rejects("server_budget") { RelaySources.select(emptyList(), listOf(RelaySource("large", many))) }
        val node = (1..16).map { server("turn:host$it:3478") }
        rejects("ice_server_budget") { RelaySources.select(node, listOf(source("extra", server("turn:extra:3478")))) }
        rejects("url_budget") { RelaySources.select(emptyList(), listOf(source("large", RelayIceServer((1..33).map { "turn:host$it:3478" }, "u", "p")))) }
    }

    @Test fun sourceInputMutationCannotChangeSelection() {
        val urls = mutableListOf("turn:host:3478")
        val selected = RelaySources.select(listOf(RelayIceServer(urls)))
        urls.clear()
        assertEquals(listOf("turn:host:3478"), selected.servers.single().urls)
    }

    @Test fun diagnosticsRedactCredentialsAndUnknownUrls() {
        val secret = "do-not-log"
        val item = server("turn:host:3478", secret, secret)
        val selected = RelaySources.select(listOf(item))
        assertFalse(item.toString().contains(secret))
        assertFalse(selected.toString().contains(secret))
        assertTrue(selected.sourcesForUrl("turn:unknown:3478").isEmpty())
    }
}
