package com.callagent.rtcmedia

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PeerLifecycleTest {
    @Test fun closeBetweenConstructionAndPublicationClosesLatePeerExactlyOnce() {
        val lifecycle=PeerLifecycle<AutoCloseable>()
        assertTrue(lifecycle.start())
        val constructed=CountDownLatch(1)
        val publish=CountDownLatch(1)
        val closes=AtomicInteger()
        val worker=Executors.newSingleThreadExecutor()
        try {
            val installed=worker.submit<Boolean> {
                val native=AutoCloseable { closes.incrementAndGet() }
                // Deterministically stop after construction, before publication.
                constructed.countDown()
                check(publish.await(2,TimeUnit.SECONDS))
                lifecycle.install(native)
            }
            assertTrue(constructed.await(2,TimeUnit.SECONDS))
            val retired=checkNotNull(lifecycle.closeAndDetach(true))
            assertNull(retired.peer)
            assertTrue(retired.notifyPeer)
            publish.countDown()
            assertFalse(installed.get(2,TimeUnit.SECONDS))
            assertNull(lifecycle.closeAndDetach(true))
            assertNull(lifecycle.get())
            assertEquals(1,closes.get())
        } finally { publish.countDown();worker.shutdownNow() }
    }

    @Test fun closeCannotPassTheClosedCheckBeforePublication() {
        val checked=CountDownLatch(1)
        val publish=CountDownLatch(1)
        val closeAttempted=CountDownLatch(1)
        val closes=AtomicInteger()
        val lifecycle=PeerLifecycle<AutoCloseable> {
            checked.countDown()
            check(publish.await(2,TimeUnit.SECONDS))
        }
        val executor=Executors.newFixedThreadPool(2)
        try {
            assertTrue(lifecycle.start())
            val installed=executor.submit<Boolean>{lifecycle.install(AutoCloseable{closes.incrementAndGet()})}
            assertTrue(checked.await(2,TimeUnit.SECONDS))
            val closed=executor.submit<Boolean>{
                closeAttempted.countDown()
                val retired=checkNotNull(lifecycle.closeAndDetach(true))
                retired.peer?.close()
                retired.notifyPeer
            }
            assertTrue(closeAttempted.await(2,TimeUnit.SECONDS))
            try {
                closed.get(100,TimeUnit.MILLISECONDS)
                fail("close passed the publication monitor before publication")
            } catch(_:java.util.concurrent.TimeoutException) { /* expected: monitor held */ }
            publish.countDown()
            assertTrue(installed.get(2,TimeUnit.SECONDS))
            assertTrue(closed.get(2,TimeUnit.SECONDS))
            assertNull(lifecycle.get())
            assertEquals(1,closes.get())
        } finally { publish.countDown();executor.shutdownNow() }
    }

    @Test fun installedPeerIsDetachedBeforeNativeCloseWithoutHoldingMonitor() {
        val lifecycle=PeerLifecycle<AutoCloseable>()
        val worker=Executors.newSingleThreadExecutor()
        var closes=0
        try {
            assertTrue(lifecycle.start())
            val peer=AutoCloseable {
                assertNull(worker.submit<AutoCloseable?> { lifecycle.get() }.get(2,TimeUnit.SECONDS))
                closes++
            }
            assertTrue(lifecycle.install(peer))
            val retired=checkNotNull(lifecycle.closeAndDetach(true))
            assertSame(peer,retired.peer)
            retired.peer!!.close()
            assertNull(lifecycle.closeAndDetach(true))
            assertEquals(1,closes)
        } finally { worker.shutdownNow() }
    }

    @Test fun preparedSessionNeverNotifiesAndCannotBeStartedAfterClose() {
        val lifecycle=PeerLifecycle<AutoCloseable>()
        assertFalse(lifecycle.isStarted)
        assertFalse(checkNotNull(lifecycle.closeAndDetach(true)).notifyPeer)
        assertTrue(lifecycle.isClosed)
        assertFalse(lifecycle.start())
    }

    @Test fun controlTerminalSuppressesNotificationEvenAfterStart() {
        val lifecycle=PeerLifecycle<AutoCloseable>()
        assertTrue(lifecycle.start())
        assertFalse(checkNotNull(lifecycle.closeAndDetach(false)).notifyPeer)
    }
}
