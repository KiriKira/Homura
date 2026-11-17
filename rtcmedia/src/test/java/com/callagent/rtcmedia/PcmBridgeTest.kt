package com.callagent.rtcmedia

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Regression tests authored for CI; not compiled or run in the toolchain-free repair workspace. */
class PcmBridgeTest {
    @Test fun fullQueueEvictsTwoOldBlocksAndRetainsNewestAudioInOrder() {
        val bridge = PcmBridge()
        repeat(7) { index -> bridge.capture(frame(index * 2, index * 2 + 1), 16000) }
        assertEquals(2L, bridge.overruns.get())
        for (expected in 2 until 14) assertArrayEquals(block(expected), fill(bridge))
        assertEquals(12L, bridge.captureFrames.get())
        assertArrayEquals(ByteArray(320), fill(bridge))
        assertEquals(1L, bridge.underruns.get())
    }

    @Test fun oneFreeSlotEvictsExactlyOneOldBlock() {
        val bridge = PcmBridge()
        repeat(6) { index -> bridge.capture(frame(index * 2, index * 2 + 1), 16000) }
        assertArrayEquals(block(0), fill(bridge))
        bridge.capture(frame(12, 13), 16000)
        assertEquals(1L, bridge.overruns.get())
        for (expected in 2 until 14) assertArrayEquals(block(expected), fill(bridge))
        assertEquals(13L, bridge.captureFrames.get())
    }

    @Test fun eightKhzStillProducesTwoTenMsBlocksWithoutOverrun() {
        val bridge = PcmBridge()
        bridge.capture(ByteArray(320), 8000)
        repeat(2) { assertArrayEquals(ByteArray(320), fill(bridge)) }
        assertEquals(2L, bridge.captureFrames.get())
        assertEquals(0L, bridge.overruns.get())
        assertEquals(0L, bridge.underruns.get())
    }

    @Test fun concurrentProducersAndConsumerAccountForEveryBlock() {
        val bridge = PcmBridge()
        val producers = 4
        val framesPerProducer = 1000
        val start = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(producers + 1)
        try {
            val writes = (0 until producers).map { producer ->
                workers.submit {
                    check(start.await(5, TimeUnit.SECONDS))
                    repeat(framesPerProducer) { bridge.capture(frame(producer, producer), 16000) }
                }
            }
            val reads = workers.submit {
                check(start.await(5, TimeUnit.SECONDS))
                val buffer = ByteBuffer.allocate(320)
                repeat(producers * framesPerProducer * 2) { bridge.fill(buffer, 2, 1, 16000) }
            }
            start.countDown()
            writes.forEach { it.get(10, TimeUnit.SECONDS) }
            reads.get(10, TimeUnit.SECONDS)
            // At most twelve blocks remain. Drain them before checking conservation.
            repeat(12) { fill(bridge) }
            val produced = producers.toLong() * framesPerProducer * 2
            assertEquals(produced, bridge.captureFrames.get() + bridge.overruns.get())
            assertEquals(producers.toLong() * framesPerProducer * 2 + 12,
                bridge.captureFrames.get() + bridge.underruns.get())
        } finally { start.countDown(); workers.shutdownNow() }
    }

    @Test fun explicitClearIsNotCountedAsCapturePressure() {
        val bridge = PcmBridge()
        bridge.capture(frame(1, 2), 16000)
        bridge.clear()
        assertEquals(0L, bridge.overruns.get())
        assertArrayEquals(ByteArray(320), fill(bridge))
        assertEquals(1L, bridge.underruns.get())
    }

    private fun block(value: Int) = ByteArray(320) { value.toByte() }
    private fun frame(first: Int, second: Int) = block(first) + block(second)
    private fun fill(bridge: PcmBridge): ByteArray {
        val buffer = ByteBuffer.allocate(320)
        bridge.fill(buffer, 2, 1, 16000)
        return buffer.array()
    }
}
