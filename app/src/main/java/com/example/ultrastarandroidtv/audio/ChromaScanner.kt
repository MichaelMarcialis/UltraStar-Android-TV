package com.example.ultrastarandroidtv.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import com.example.ultrastarandroidtv.song.CHROMA_RATE
import com.example.ultrastarandroidtv.song.ChromaBuilder
import com.example.ultrastarandroidtv.song.ChromaProfile
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "ChromaScanner"

/** Long enough for any song on a karaoke card, short enough that a stuck decoder gives up. */
private const val BUDGET_MILLIS = 30_000L

private const val CODEC_TIMEOUT_US = 10_000L

/**
 * Reduces a whole recording to the pitch-class profile [checkSync] needs.
 *
 * ## Why it streams
 *
 * Three and a half minutes at 11 kHz is nine megabytes held as float, on a device whose heap
 * growth limit is 192 MB and which is also holding Compose, a decoder and a card's worth of
 * cover art. Nothing is kept: samples are mixed to mono, dropped to [CHROMA_RATE] and pushed
 * straight into a [ChromaBuilder], which emits one frame of twelve numbers every 46 ms. The whole
 * profile for a long song is about two hundred kilobytes.
 *
 * ## Why it decodes the whole file
 *
 * Unlike [LoudnessScanner], which samples five windows because loudness is an average, this needs
 * the *sequence*: the melody a song moves through is what identifies where the chart belongs, and
 * a sample of it identifies nothing. Whole-file decode of a four-megabyte AAC is a few seconds on
 * this device, and it happens once, immediately after a download that has just waited half a
 * minute on somebody else's throttle.
 *
 * Returns null when the file cannot be read, which is a real answer rather than an error: a file
 * that will not decode here will not decode for the player either.
 */
object ChromaScanner {

    fun scan(context: Context, uri: String): ChromaProfile? {
        val extractor = MediaExtractor()
        return try {
            if (uri.startsWith("content://") || uri.startsWith("file://")) {
                extractor.setDataSource(context, Uri.parse(uri), null)
            } else {
                extractor.setDataSource(uri)
            }
            scanWith(extractor)
        } catch (e: Exception) {
            Log.w(TAG, "could not read $uri: ${e.message}")
            null
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun scanWith(extractor: MediaExtractor): ChromaProfile? {
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: return null

        val format = extractor.getTrackFormat(track)
        extractor.selectTrack(track)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
        val codec = runCatching { MediaCodec.createDecoderByType(mime) }.getOrNull() ?: return null

        return try {
            codec.configure(format, null, null, 0)
            codec.start()
            decodeAll(extractor, codec)
        } catch (e: Exception) {
            Log.w(TAG, "decode failed: ${e.message}")
            null
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
    }

    private fun decodeAll(extractor: MediaExtractor, codec: MediaCodec): ChromaProfile? {
        val info = MediaCodec.BufferInfo()
        val builder = ChromaBuilder()
        val down = Downsampler(builder)
        val deadline = System.currentTimeMillis() + BUDGET_MILLIS
        var inputDone = false
        var channels = 0
        var rate = 0

        while (System.currentTimeMillis() < deadline) {
            if (!inputDone) {
                val index = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                if (index >= 0) {
                    val buffer = codec.getInputBuffer(index)
                    val size = if (buffer == null) -1 else extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(index, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            when (val out = codec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val output = codec.outputFormat
                    // 16-bit PCM only, and no second code path for anything else: every decoder
                    // on this device produces it, and a wrong guess about the layout would give a
                    // confidently wrong answer rather than no answer.
                    val encoding = if (output.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        output.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    } else {
                        AudioFormat.ENCODING_PCM_16BIT
                    }
                    if (encoding != AudioFormat.ENCODING_PCM_16BIT) return null
                    channels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    rate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    down.configure(channels, rate)
                }

                else -> if (out >= 0) {
                    val buffer = codec.getOutputBuffer(out)
                    if (buffer != null && info.size > 0 && rate > 0) {
                        down.add(buffer, info.offset, info.size)
                    }
                    codec.releaseOutputBuffer(out, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        }

        val profile = builder.build()
        return profile.takeIf { it.frames > 0 }
    }

    /**
     * Mixes to mono and drops the rate, straight into the builder.
     *
     * A box average rather than a resampling filter, deliberately: averaging every input sample
     * that falls inside an output sample *is* a low-pass, which is the only part of resampling
     * that matters here — everything above 5.5 kHz is discarded by the band limit a moment later
     * anyway, so aliasing has nothing left to spoil.
     */
    private class Downsampler(private val builder: ChromaBuilder) {
        private var channels = 2
        private var perInput = 0.0
        private var phase = 0.0
        private var sum = 0.0
        private var count = 0
        private val out = FloatArray(2_048)
        private var filled = 0

        fun configure(channels: Int, rate: Int) {
            this.channels = channels.coerceAtLeast(1)
            perInput = CHROMA_RATE.toDouble() / rate
        }

        fun add(buffer: ByteBuffer, offset: Int, size: Int) {
            if (perInput <= 0.0) return
            // Stepwise rather than chained: `Buffer.position` is declared to return `Buffer` here,
            // so a fluent chain loses the `ByteBuffer` type before `order` can be called.
            val view = buffer.duplicate()
            view.position(offset)
            view.limit(offset + size)
            val shorts = view.slice().order(ByteOrder.nativeOrder()).asShortBuffer()

            var channel = 0
            var frame = 0.0
            while (shorts.hasRemaining()) {
                frame += shorts.get() / 32768.0
                channel++
                if (channel < channels) continue
                channel = 0

                sum += frame / channels
                count++
                frame = 0.0

                phase += perInput
                if (phase >= 1.0) {
                    phase -= 1.0
                    out[filled++] = (sum / count).toFloat()
                    sum = 0.0
                    count = 0
                    if (filled == out.size) {
                        builder.add(out, filled)
                        filled = 0
                    }
                }
            }
            if (filled > 0) {
                builder.add(out, filled)
                filled = 0
            }
        }
    }
}
