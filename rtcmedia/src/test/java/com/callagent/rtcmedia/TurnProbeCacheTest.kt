package com.callagent.rtcmedia

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class TurnProbeCacheTest {
    @Test fun successAndFailureExpireAndCredentialsHaveIndependentKeys() {
        var now=0L
        val cache=TurnProbeCache({now},30L,5L,2)
        var calls=0
        assertTrue(cache.check("credential-a",100){calls++;true})
        assertTrue(cache.check("credential-a",100){error("cached")})
        assertFalse(cache.check("credential-b",100){calls++;false})
        now=5L
        assertTrue(cache.check("credential-b",100){calls++;true})
        now=30L
        assertFalse(cache.check("credential-a",100){calls++;false})
        assertEquals(4,calls)
    }
    @Test fun concurrentChecksShareOneProbeWithoutHoldingCacheMonitor() {
        val cache=TurnProbeCache()
        val entered=CountDownLatch(1)
        val finish=CountDownLatch(1)
        val ready=CountDownLatch(4)
        val calls=AtomicInteger()
        val executor=Executors.newFixedThreadPool(5)
        try {
            val owner=executor.submit<Boolean>{cache.check("same",2000){
                calls.incrementAndGet();entered.countDown()
                check(finish.await(2,TimeUnit.SECONDS));true
            }}
            assertTrue(entered.await(2,TimeUnit.SECONDS))
            val waiters=(1..4).map{executor.submit<Boolean>{
                ready.countDown();cache.check("same",2000){calls.incrementAndGet();true}
            }}
            assertTrue(ready.await(2,TimeUnit.SECONDS))
            finish.countDown()
            assertTrue(owner.get(2,TimeUnit.SECONDS))
            waiters.forEach{assertTrue(it.get(2,TimeUnit.SECONDS))}
            assertEquals(1,calls.get())
        } finally { finish.countDown();executor.shutdownNow() }
    }
    @Test fun capacityEvictsOldResultsAndFailedProbeDoesNotPoisonSingleFlight() {
        val cache=TurnProbeCache(capacity=1)
        try { cache.check("a",100){error("failure")} }catch(_:IllegalStateException){}
        assertTrue(cache.check("a",100){true})
        assertFalse(cache.check("b",100){false})
        assertFalse(cache.check("a",100){false})
    }
}
