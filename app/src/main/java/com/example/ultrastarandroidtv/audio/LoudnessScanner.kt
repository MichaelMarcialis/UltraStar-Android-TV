package com.example.ultrastarandroidtv.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.sqrt

private const val TAG = "Loudness"

/**
 * Short stretches decoded from across the song, rather than the whole of it.
 *
 * Decoding three minutes of audio to find out how loud it is costs several seconds, and the
 * answer is the same. Five windows spread through the file catch the quiet intro, the loud
 * chorus and the outro, which is the whole of what a single number can express anyway.
 */
private const val WINDOW_COUNT = 5

/** How much audio is taken at each of those points. */
private const val WINDOW_SECONDS = 2.0

/** How long to wait on the codec at each step. Short: this loop is polled, not blocked. */
private const val CODEC_TIMEOUT_US = 10_000L

/**
 * A wall-clock ceiling on the whole measurement.
 *
 * This runs while a title card is on screen and the room is waiting to sing. A file that decodes
 * slowly — or a codec that will not start at all — must cost the song a moment and then be given
 * up on, never the song itself. Whatever was measured before the budget ran out is still used;
 * a partial measurement of a pop record is a good measurement.
 */
private const val BUDGET_MILLIS = 2_500L

/**
 * Measures how loud a recording actually is, by decoding a little of it.
 *
 * This exists because the library is a pile of rips from thirty years of sources and the volume
 * genuinely varies by more than 10 dB across it — which on a television means somebody reaching
 * for the remote between every song. The answer everywhere else in audio is the same one: measure
 * each recording once, store a number, and apply it at playback. See [gainFor].
 *
 * Blocking, and does real I/O. Call it off the main thread.
 */
object LoudnessScanner {

    /**
     * Returns the loudness of the audio at [uri], or [Loudness.UNKNOWN] if it cannot be read.
     *
     * Unknown is a real answer and not an error: a file that will not decode here will not decode
     * for the player either, and the caller's response to both is to leave the volume alone.
     */
    fun measure(context: Context, uri: String): Loudness {
        val extractor = MediaExtractor()
        return try {
            if (uri.startsWith("content://") || uri.startsWith("file://")) {
                extractor.setDataSource(context, Uri.parse(uri), null)
            } else {
                extractor.setDataSource(uri)
            }
            measureWith(extractor)
        } catch (e: Exception) {
            Log.w(TAG, "could not measure $uri: ${e.message}")
            Loudness.UNKNOWN
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun measureWith(extractor: MediaExtractor): Loudness {
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: return Loudness.UNKNOWN

        val format = extractor.getTrackFormat(track)
        extractor.selectTrack(track)

        val mime = format.getString(MediaFormat.KEY_MIME) ?: return Loudness.UNKNOWN
        val codec = runCatching { MediaCodec.createDecoderByType(mime) }.getOrNull()
            ?: return Loudness.UNKNOWN

        return try {
            codec.configure(format, null, null, 0)
            codec.start()

            val durationUs =
                if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            val deadline = System.currentTimeMillis() + BUDGET_MILLIS
            val accumulator = Accumulator()

            for (point in seekPoints(durationUs)) {
                if (System.currentTimeMillis() > deadline) break
                extractor.seekTo(point, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                codec.flush()
                decodeWindow(extractor, codec, accumulator, deadline)
            }
            accumulator.result()
        } catch (e: Exception) {
            Log.w(TAG, "decode failed: ${e.message}")
            Loudness.UNKNOWN
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
    }

    /**
     * Where in the file to listen, in microseconds.
     *
     * Never the very start or the very end: songs fade in and out, and a window of silence would
     * drag the average down and make a normal recording look quiet enough to boost.
     */
    private fun seekPoints(durationUs: Long): LongArray {
        if (durationUs <= 0L) return longArrayOf(0L)
        return LongArray(WINDOW_COUNT) { i ->
            (durationUs * (0.12 + 0.76 * i / (WINDOW_COUNT - 1).toDouble())).toLong()
        }
    }

    /** Decodes about [WINDOW_SECONDS] from wherever the extractor is now, into [into]. */
    private fun decodeWindow(
        extractor: MediaExtractor,
        codec: MediaCodec,
        into: Accumulator,
        deadline: Long,
    ) {
        val info = MediaCodec.BufferInfo()
        var startUs = -1L
        var inputDone = false

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

            val out = codec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)
            when {
                out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // Anything but 16-bit PCM is not worth a second code path: this device's
                    // decoders produce 16-bit for every format in the library, and a wrong guess
                    // about the layout would report a confidently wrong loudness.
                    val encoding = codec.outputFormat.let {
                        if (it.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            it.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        } else {
                            AudioFormat.ENCODING_PCM_16BIT
                        }
                    }
                    if (encoding != AudioFormat.ENCODING_PCM_16BIT) return
                }

                out >= 0 -> {
                    val buffer = codec.getOutputBuffer(out)
                    if (buffer != null && info.size > 0) {
                        if (startUs < 0L) startUs = info.presentationTimeUs
                        into.add(buffer, info.offset, info.size)
                    }
                    codec.releaseOutputBuffer(out, false)

                    val elapsed = (info.presentationTimeUs - startUs) / 1_000_000.0
                    if (startUs >= 0L && elapsed >= WINDOW_SECONDS) return
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }

            if (inputDone && out < 0) return
        }
    }

    /** Running sum of squares and peak over every sample seen, in 16-bit units. */
    private class Accumulator {
        private var sumSquares = 0.0
        private var count = 0L
        private var peak = 0f

        fun add(buffer: ByteBuffer, offset: Int, size: Int) {
            // Stepwise rather than chained: `Buffer.position` is declared to return `Buffer`
            // here, so a fluent chain loses the `ByteBuffer` type before `order` can be called.
            val view = buffer.duplicate()
            view.position(offset)
            view.limit(offset + size)
            val shorts = view.slice().order(ByteOrder.nativeOrder()).asShortBuffer()

            while (shorts.hasRemaining()) {
                val sample = shorts.get() / 32768f
                sumSquares += sample.toDouble() * sample
                peak = max(peak, kotlin.math.abs(sample))
                count++
            }
        }

        fun result(): Loudness =
            if (count == 0L) Loudness.UNKNOWN
            else Loudness(sqrt(sumSquares / count).toFloat(), peak)
    }
}
