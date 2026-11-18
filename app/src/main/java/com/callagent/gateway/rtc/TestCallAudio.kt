package com.callagent.gateway.rtc

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.callagent.gateway.R
import java.nio.ByteOrder

/**
 * The recording a test call plays: res/raw/test_call_music, muque
 * 「いいんじゃない」. Decoded once to 16 kHz mono PCM16, the format the WebRTC
 * PCM bridge takes, and kept in memory (about 3 MB for the 93 s song) and in
 * the app cache, so only the first test after an install pays for decoding.
 */
object TestCallAudio {
    const val RATE = 16000
    /** 20 ms at 16 kHz: the frame the PCM bridge accepts. */
    const val FRAME_SAMPLES = RATE / 50
    private const val MAX_SECONDS = 180
    /** Bump when the decoded output changes (resampler, source file). */
    private const val CACHE_FILE = "test_call_music_16k_v2.pcm"
    @Volatile private var cached: ShortArray? = null

    class Decoded(val pcm: ShortArray, val sourceRate: Int, val sourceChannels: Int, val decodeMs: Long, val cached: Boolean)

    @Synchronized fun load(context: Context): Decoded {
        cached?.let { return Decoded(it, RATE, 1, 0, true) }
        val started = System.nanoTime()
        val file = java.io.File(context.cacheDir, CACHE_FILE)
        readCache(file)?.let { pcm ->
            cached = pcm
            return Decoded(pcm, RATE, 1, (System.nanoTime() - started) / 1_000_000, true)
        }
        val afd = context.resources.openRawResourceFd(R.raw.test_call_music)
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            val track = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val decoder = MediaCodec.createDecoderByType(checkNotNull(format.getString(MediaFormat.KEY_MIME)))
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()
            val chunks = ArrayList<ShortArray>()
            var total = 0L
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                // Queue every free input buffer without waiting, then wait
                // briefly for output. One input per 10 ms wait made the first
                // decode take seconds, and the call waited for it.
                while (!inputDone) {
                    val index = decoder.dequeueInputBuffer(0)
                    if (index < 0) break
                    val buffer = checkNotNull(decoder.getInputBuffer(index))
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
                val index = decoder.dequeueOutputBuffer(info, 2_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    rate = decoder.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = decoder.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else if (index >= 0) {
                    if (info.size > 0 && total < MAX_SECONDS.toLong() * rate * channels) {
                        val buffer = checkNotNull(decoder.getOutputBuffer(index))
                        buffer.position(info.offset); buffer.limit(info.offset + info.size)
                        val shorts = buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val chunk = ShortArray(shorts.remaining()); shorts.get(chunk)
                        chunks += chunk; total += chunk.size
                    }
                    decoder.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
            val interleaved = ShortArray(total.toInt())
            var offset = 0
            for (chunk in chunks) { chunk.copyInto(interleaved, offset); offset += chunk.size }
            val pcm = toMono16k(interleaved, channels, rate)
            cached = pcm
            writeCache(file, pcm)
            return Decoded(pcm, rate, channels, (System.nanoTime() - started) / 1_000_000, false)
        } finally {
            runCatching { codec?.stop() }; runCatching { codec?.release() }
            extractor.release(); afd.close()
        }
    }

    private fun readCache(file: java.io.File): ShortArray? = runCatching {
        val length = file.length()
        if (length <= 0 || length % 2 != 0L || length > MAX_SECONDS.toLong() * RATE * 2) return null
        val bytes = file.readBytes()
        val pcm = ShortArray(bytes.size / 2)
        java.nio.ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm)
        pcm
    }.getOrNull()

    private fun writeCache(file: java.io.File, pcm: ShortArray) {
        runCatching {
            val bytes = java.nio.ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            bytes.asShortBuffer().put(pcm)
            val partial = java.io.File(file.parentFile, file.name + ".part")
            partial.writeBytes(bytes.array())
            if (!partial.renameTo(file)) partial.delete()
        }
    }

    /**
     * Averages channels and resamples to 16 kHz through a windowed-sinc
     * low-pass at 7.2 kHz. Plain interpolation folded everything above 8 kHz
     * (most of a song's cymbals and sibilance) back into the band as harsh
     * aliasing, which made the test call sound far worse than the line.
     */
    internal fun toMono16k(interleaved: ShortArray, channels: Int, rate: Int): ShortArray {
        require(channels >= 1 && rate > 0)
        val frames = interleaved.size / channels
        val mono = FloatArray(frames) { frame ->
            var sum = 0f
            for (c in 0 until channels) sum += interleaved[frame * channels + c]
            sum / channels
        }
        if (rate == RATE) return ShortArray(frames) { Math.round(mono[it]).coerceIn(-32768, 32767).toShort() }
        if (frames == 0) return ShortArray(0)
        val outFrames = (frames.toLong() * RATE / rate).toInt()
        // Cutoff as a fraction of the input rate, below the lower Nyquist.
        val cutoff = 0.45 * minOf(rate, RATE) / rate
        val half = Math.ceil(ZERO_CROSSINGS / (2 * cutoff)).toInt()
        val taps = 2 * half
        // Polyphase table: PHASES fractional offsets, each normalized to unity gain.
        val table = Array(PHASES + 1) { phase ->
            val fraction = phase.toDouble() / PHASES
            val kernel = DoubleArray(taps) { k ->
                val distance = (k - half + 1) - fraction
                val x = 2 * cutoff * distance
                val sinc = if (x == 0.0) 1.0 else Math.sin(Math.PI * x) / (Math.PI * x)
                val w = distance / (half + 1)
                val window = if (kotlin.math.abs(w) >= 1) 0.0 else 0.42 + 0.5 * Math.cos(Math.PI * w) + 0.08 * Math.cos(2 * Math.PI * w)
                sinc * window
            }
            val sum = kernel.sum()
            FloatArray(taps) { (kernel[it] / sum).toFloat() }
        }
        val step = rate.toDouble() / RATE
        return ShortArray(outFrames) { i ->
            val position = i * step
            val base = position.toInt()
            val kernel = table[Math.round((position - base) * PHASES).toInt()]
            var acc = 0f
            val first = base - half + 1
            for (k in 0 until taps) {
                val index = (first + k).coerceIn(0, frames - 1)
                acc += mono[index] * kernel[k]
            }
            Math.round(acc).coerceIn(-32768, 32767).toShort()
        }
    }

    private const val PHASES = 256
    /** Sinc zero crossings on each side of the kernel: ~70 dB stopband with the Blackman window. */
    private const val ZERO_CROSSINGS = 10

    /** One 20 ms frame as little-endian PCM16; silence past the end. */
    internal fun frame(pcm: ShortArray, index: Int): ByteArray {
        val out = ByteArray(FRAME_SAMPLES * 2)
        val start = index * FRAME_SAMPLES
        for (i in 0 until FRAME_SAMPLES) {
            val sample = if (start + i < pcm.size) pcm[start + i].toInt() else 0
            out[i * 2] = sample.toByte(); out[i * 2 + 1] = (sample shr 8).toByte()
        }
        return out
    }
}
