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
 * PCM bridge takes, and kept in memory (about 3 MB for the 93 s song).
 */
object TestCallAudio {
    const val RATE = 16000
    /** 20 ms at 16 kHz: the frame the PCM bridge accepts. */
    const val FRAME_SAMPLES = RATE / 50
    private const val MAX_SECONDS = 180
    @Volatile private var cached: ShortArray? = null

    class Decoded(val pcm: ShortArray, val sourceRate: Int, val sourceChannels: Int, val decodeMs: Long, val cached: Boolean)

    @Synchronized fun load(context: Context): Decoded {
        cached?.let { return Decoded(it, RATE, 1, 0, true) }
        val started = System.nanoTime()
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
                if (!inputDone) {
                    val index = decoder.dequeueInputBuffer(10_000)
                    if (index >= 0) {
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
                }
                val index = decoder.dequeueOutputBuffer(info, 10_000)
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
            return Decoded(pcm, rate, channels, (System.nanoTime() - started) / 1_000_000, false)
        } finally {
            runCatching { codec?.stop() }; runCatching { codec?.release() }
            extractor.release(); afd.close()
        }
    }

    /** Averages channels and resamples linearly to 16 kHz. A test signal, not mastering. */
    internal fun toMono16k(interleaved: ShortArray, channels: Int, rate: Int): ShortArray {
        require(channels >= 1 && rate > 0)
        val frames = interleaved.size / channels
        val mono = FloatArray(frames) { frame ->
            var sum = 0f
            for (c in 0 until channels) sum += interleaved[frame * channels + c]
            sum / channels
        }
        if (rate == RATE) return ShortArray(frames) { mono[it].toInt().toShort() }
        val outFrames = (frames.toLong() * RATE / rate).toInt()
        return ShortArray(outFrames) { i ->
            val position = i.toDouble() * rate / RATE
            val left = position.toInt().coerceAtMost(frames - 1)
            val right = (left + 1).coerceAtMost(frames - 1)
            val fraction = (position - left).toFloat()
            (mono[left] + (mono[right] - mono[left]) * fraction).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

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
