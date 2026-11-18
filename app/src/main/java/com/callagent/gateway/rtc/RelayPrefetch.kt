package com.callagent.gateway.rtc

import android.content.Context
import android.util.Log
import com.callagent.rtcmedia.RelayRepository
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Prepares relay sources while a call rings, as Madoka does, so answering
 * does not wait for other nodes' and external sources' credentials: over a
 * distant source (a Worker at a few hundred ms) that is two HTTPS requests
 * and a TLS handshake, much of the 1.5 s preparation deadline.
 *
 * The call later resolves with the owner id from its media binding; the
 * cache only serves that same scope, so the owner id seen in a node's last
 * binding is kept (it is not secret) and used for the next prefetch.
 */
object RelayPrefetch {
    private const val PREFS = "rtc-relay-owners"
    private val owners = ConcurrentHashMap<String, String>()
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "RTC-Relay-Prefetch").apply { isDaemon = true } }

    /** The owner id a node's media binding carried. */
    fun learned(context: Context, gatewayId: String, ownerId: String) {
        if (ownerId.isBlank() || owners.put(gatewayId, ownerId) == ownerId) return
        runCatching { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(gatewayId, ownerId).apply() }
    }

    internal fun owner(context: Context, gatewayId: String): String? = owners[gatewayId]
        ?: runCatching { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(gatewayId, null) }.getOrNull()
            ?.also { owners[gatewayId] = it }

    /** Starts preparing relay sources for a call through [gatewayId]; nothing before its first call. */
    fun start(context: Context, gatewayId: String, callId: String) {
        val owner = owner(context, gatewayId) ?: return
        runCatching {
            worker.execute {
                val started = System.nanoTime()
                val sources = runCatching { RelayRepository.resolve(context, owner, "node-$gatewayId") }.getOrNull()
                runCatching {
                    Log.i("GsmRtc", "media=webrtc call_id=$callId event=relay_sources_prefetched elapsed_ms=${(System.nanoTime() - started) / 1_000_000} sources=${sources?.size ?: -1}")
                }
            }
        }
    }
}
