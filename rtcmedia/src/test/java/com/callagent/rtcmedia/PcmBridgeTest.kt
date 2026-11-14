package com.callagent.rtcmedia

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PcmBridgeTest {
    private fun frame(value:Int)=ByteArray(640){value.toByte()}
    private fun pull(bridge:PcmBridge):ByteArray {
        val buffer=ByteBuffer.allocate(320)
        bridge.fill(buffer,2,1,16000)
        return buffer.array()
    }
    @Test fun overflowDropsExactlyTwoOldBlocksAndRetainsNewestTwelve() {
        val bridge=PcmBridge()
        repeat(7){bridge.capture(frame(it+1),16000)}
        assertEquals(2L,bridge.overruns.get())
        for(value in 2..7)repeat(2){assertArrayEquals(ByteArray(320){value.toByte()},pull(bridge))}
        assertEquals(12L,bridge.captureFrames.get())
        assertArrayEquals(ByteArray(320),pull(bridge))
        assertEquals(1L,bridge.underruns.get())
    }
    @Test fun concurrentProducersAndConsumerConserveEveryTenMillisecondBlock() {
        val bridge=PcmBridge()
        val executor=Executors.newFixedThreadPool(3)
        val go=CountDownLatch(1)
        try {
            val producers=(1..2).map{value->executor.submit {
                check(go.await(2,TimeUnit.SECONDS))
                repeat(1000){bridge.capture(frame(value),16000)}
            }}
            val consumer=executor.submit {
                check(go.await(2,TimeUnit.SECONDS))
                repeat(2000){pull(bridge)}
            }
            go.countDown()
            producers.forEach{it.get(5,TimeUnit.SECONDS)}
            consumer.get(5,TimeUnit.SECONDS)
            // Drain up to the unchanged queue bound after all producers stop.
            repeat(12){pull(bridge)}
            assertEquals(4000L,bridge.captureFrames.get()+bridge.overruns.get())
        } finally { go.countDown();executor.shutdownNow() }
    }
    @Test fun clearIsAnExplicitResetNotAnOverflow() {
        val bridge=PcmBridge()
        bridge.capture(frame(1),16000);bridge.clear()
        assertArrayEquals(ByteArray(320),pull(bridge))
        assertEquals(0L,bridge.overruns.get())
    }
}
