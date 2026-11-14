package com.callagent.rtcmedia

import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

/** Bounded mono PCM16 adapter. Cellular side supplies 20 ms at 8/16 kHz;
 * WebRTC pulls/pushes 10 ms at 16 kHz. There is no microphone/speaker fallback. */
class PcmBridge {
    // Twelve 10 ms blocks = 120 ms. Keep the latency bound; do not hide overruns.
    private val captureLock = Any()
    private val captured = ArrayDeque<ByteArray>(CAPTURE_BLOCKS)
    private var pendingPlayout: ByteArray? = null
    @Volatile var onDecodedFrame: ((ByteArray) -> Unit)? = null
    val captureFrames = AtomicLong()
    val decodedFrames = AtomicLong()
    val underruns = AtomicLong()
    /** Number of 10 ms blocks evicted under capture pressure, not 20 ms input frames.
     * Explicit clear() teardown is not an overrun. New blocks are never dropped. */
    val overruns = AtomicLong()

    fun capture(frame: ByteArray, sampleRate: Int) {
        require(sampleRate == 8000 || sampleRate == 16000)
        require(frame.size == sampleRate / 50 * 2)
        val pcm = if (sampleRate == 16000) frame else upsample(frame)
        val first = pcm.copyOfRange(0, 320)
        val second = pcm.copyOfRange(320, 640)
        synchronized(captureLock) {
            for (block in arrayOf(first, second)) {
                if (captured.size == CAPTURE_BLOCKS) {
                    captured.removeFirst()
                    overruns.incrementAndGet()
                }
                captured.addLast(block)
            }
        }
    }

    fun fill(buffer: ByteBuffer, format: Int, channels: Int, sampleRate: Int) {
        require(format == 2 && channels == 1 && sampleRate == 16000 && buffer.capacity() == 320)
        buffer.clear()
        val block = synchronized(captureLock) { captured.pollFirst() }
        if (block == null) { underruns.incrementAndGet(); buffer.put(SILENCE) }
        else { buffer.put(block); captureFrames.incrementAndGet() }
        buffer.rewind()
    }

    @Synchronized fun decoded(format: Int, channels: Int, sampleRate: Int, data: ByteArray) {
        require(format == 2 && channels == 1 && sampleRate == 16000 && data.size == 320)
        decodedFrames.incrementAndGet()
        val previous = pendingPlayout
        if (previous == null) pendingPlayout = data.copyOf()
        else { pendingPlayout = null; onDecodedFrame?.invoke(previous + data) }
    }

    @Synchronized fun clear() {
        synchronized(captureLock) { captured.clear() }
        pendingPlayout = null; onDecodedFrame = null
    }

    private fun upsample(input: ByteArray): ByteArray {
        val out = ByteArray(input.size * 2)
        // Linear interpolation, with a repeated boundary sample. The source is
        // genuinely resampled; changing its rate label would pitch-shift audio.
        for (i in 0 until input.size / 2) {
            val current = sample(input, i)
            val next = sample(input, (i + 1).coerceAtMost(input.size / 2 - 1))
            put(out, i * 2, current); put(out, i * 2 + 1, (current + next) / 2)
        }
        return out
    }
    private fun sample(data: ByteArray, i: Int) = (data[i*2].toInt() and 255) or (data[i*2+1].toInt() shl 8)
    private fun put(data: ByteArray, i: Int, value: Int) { data[i*2]=value.toByte();data[i*2+1]=(value shr 8).toByte() }
    private companion object { const val CAPTURE_BLOCKS = 12; val SILENCE = ByteArray(320) }
}
