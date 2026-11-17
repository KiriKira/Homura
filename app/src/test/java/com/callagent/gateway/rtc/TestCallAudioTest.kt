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

    @Test fun resamplingKeepsTheBandAndRejectsWhatWouldAlias() {
        fun tone(hz: Double, rate: Int) = ShortArray(rate) { (10000 * Math.sin(2 * Math.PI * hz * it / rate)).toInt().toShort() }
        // RMS over the middle, away from the clamped edges.
        fun rms(pcm: ShortArray) = Math.sqrt(pcm.copyOfRange(2000, pcm.size - 2000).sumOf { it.toDouble() * it } / (pcm.size - 4000))
        for (rate in listOf(44100, 48000)) {
            val passband = rms(TestCallAudio.toMono16k(tone(1000.0, rate), 1, rate))
            assertEquals(10000 / Math.sqrt(2.0), passband, 150.0)
            // 12 kHz folds to 4 kHz at 16 kHz; linear interpolation let it through at about -6 dB.
            val aliased = rms(TestCallAudio.toMono16k(tone(12000.0, rate), 1, rate))
            assertTrue("rate=$rate aliased rms=$aliased", aliased < passband / 300)
        }
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
