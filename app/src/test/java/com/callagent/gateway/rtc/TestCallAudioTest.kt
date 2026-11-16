package com.callagent.gateway.rtc

import org.junit.Assert.*
import org.junit.Test

class TestCallAudioTest {
    @Test fun stereo44kBecomesMono16kOfTheSameDuration() {
        val seconds = 2
        val stereo = ShortArray(44100 * 2 * seconds) { i -> if (i % 2 == 0) 1000 else 3000 }
        val mono = TestCallAudio.toMono16k(stereo, 2, 44100)
        assertEquals(16000 * seconds, mono.size)
        assertTrue(mono.all { it.toInt() == 2000 })
    }

    @Test fun resamplingInterpolatesAndClamps() {
        val ramp = ShortArray(48000) { (it % 2 * 65535 - 32768).toShort() }
        val out = TestCallAudio.toMono16k(ramp, 1, 48000)
        assertEquals(16000, out.size)
        assertTrue(out.all { it in Short.MIN_VALUE..Short.MAX_VALUE })
        assertArrayEquals(shortArrayOf(5, -5), TestCallAudio.toMono16k(shortArrayOf(5, -5), 1, 16000))
    }

    @Test fun framesAre20msLittleEndianAndPadWithSilence() {
        val pcm = ShortArray(TestCallAudio.FRAME_SAMPLES + 10) { 0x1234 }
        val first = TestCallAudio.frame(pcm, 0)
        assertEquals(640, first.size)
        assertEquals(0x34.toByte(), first[0]); assertEquals(0x12.toByte(), first[1])
        val second = TestCallAudio.frame(pcm, 1)
        assertEquals(0x34.toByte(), second[18]); assertEquals(0.toByte(), second[20]); assertEquals(0.toByte(), second[639])
    }
}
