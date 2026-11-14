package com.callagent.rtcmedia

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Short-lived, bounded cache plus single-flight. Keys must be credential digests,
 * never credentials. Network work and waits always run outside the monitor. */
internal class TurnProbeCache(
    private val clock: () -> Long = System::nanoTime,
    private val successTtl: Long = TimeUnit.SECONDS.toNanos(30),
    private val failureTtl: Long = TimeUnit.SECONDS.toNanos(5),
    private val capacity: Int = 128
) {
    private data class Entry(val result: Boolean, val expiresAt: Long)
    private val results = LinkedHashMap<String, Entry>()
    private val pending = HashMap<String, CompletableFuture<Boolean>>()

    fun check(key: String, waitMs: Long, probe: () -> Boolean): Boolean {
        val future: CompletableFuture<Boolean>
        val owner: Boolean
        synchronized(this) {
            results.entries.removeAll { clock() >= it.value.expiresAt }
            results[key]?.let { return it.result }
            val existing = pending[key]
            if (existing != null) {
                future = existing
                owner = false
            } else {
                // Do not start unbounded allocations on saturation.
                if (pending.size >= capacity) return false
                future = CompletableFuture()
                pending[key] = future
                owner = true
            }
        }
        if (!owner) return future.get(waitMs, TimeUnit.MILLISECONDS)
        try {
            val result = probe()
            synchronized(this) {
                if (results.size >= capacity) results.remove(results.keys.first())
                results[key] = Entry(result, clock() + if (result) successTtl else failureTtl)
            }
            future.complete(result)
            return result
        } catch (failure: Throwable) {
            future.completeExceptionally(failure)
            throw failure
        } finally {
            synchronized(this) { pending.remove(key) }
        }
    }
}
