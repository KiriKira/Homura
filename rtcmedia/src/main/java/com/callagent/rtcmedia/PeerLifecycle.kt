package com.callagent.rtcmedia

/** One monitor owns start, publication and terminal state. Never close native
 * resources under this monitor: WebRTC close can wait for observer callbacks. */
internal class PeerLifecycle<T : AutoCloseable>(
    // Deterministic test barrier at the former check/publication race window.
    private val beforePublish: () -> Unit = {}
) {
    private val lock = Any()
    private var closed = false
    private var started = false
    private var peer: T? = null
    val isClosed: Boolean get() = synchronized(lock) { closed }
    val isStarted: Boolean get() = synchronized(lock) { started }
    fun get(): T? = synchronized(lock) { peer }

    fun start(): Boolean = synchronized(lock) {
        if (closed) return@synchronized false
        check(!started)
        started = true
        true
    }

    fun install(resource: T): Boolean {
        val accepted = synchronized(lock) {
            if (closed) false else {
                check(peer == null)
                beforePublish()
                peer = resource
                true
            }
        }
        if (!accepted) resource.close()
        return accepted
    }

    data class Retired<T>(val peer: T?, val notifyPeer: Boolean)
    fun closeAndDetach(notifyPeer: Boolean): Retired<T>? = synchronized(lock) {
        if (closed) return@synchronized null
        closed = true
        Retired(peer, notifyPeer && started).also { peer = null }
    }
}
