package com.example.ultrastarandroidtv.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * How long the gain takes to move, in seconds of audio.
 *
 * It normally moves once, before the first note, and then never again — so this only matters in
 * the case where the measurement lands late and the song is already playing. Half a second is
 * slow enough to read as somebody easing a fader rather than as a click.
 */
private const val RAMP_SECONDS = 0.5

/**
 * Applies a fixed playback gain to the song, so that every song is about as loud as every other.
 *
 * The library is thirty years of rips from every source there is and the volume across it varies
 * by more than 10 dB — which on a television means reaching for the remote between every song.
 * [LoudnessScanner] measures each recording once and [gainFor] turns that into this number.
 *
 * **This is a processor rather than `ExoPlayer.volume`, and that is the whole reason it exists.**
 * The player's volume can only ever turn a song *down*, so normalising with it would mean pulling
 * the loud majority down to meet the few quiet ones and leaving the entire evening quieter than
 * the television was set for. Being inside the pipeline means a quiet recording can genuinely be
 * turned up.
 *
 * It is also why this cannot be Android's `LoudnessEnhancer`, which would otherwise do the job in
 * ten lines: this Shield re-encodes to E-AC3 for HDMI and the audio reaches the sink as a direct
 * stream, and system audio effects do not run on that path. Inside ExoPlayer the samples are
 * still PCM, which `SpectrumTap` already proves.
 *
 * **It alters the audio on purpose**, which makes it the exception to the pass-through rule the
 * tap follows — and the reason that rule exists still holds here. What must not change is the
 * *timing*: this touches sample values only, never their number or their order, so the 127 ms
 * calibration and everything scored against it are untouched.
 */
@OptIn(UnstableApi::class)
class GainProcessor : BaseAudioProcessor() {

    /** Where the gain is heading. 1 leaves the song exactly as it was recorded. */
    @Volatile
    var gain: Float = 1f

    private var applied = 1f
    private var step = 0f

    /**
     * False for any encoding this cannot touch, which makes the whole processor a pass-through.
     *
     * **Not an exception, deliberately.** Refusing a format the way `SpectrumTap` does looks like
     * opting out, and in Media3 it is not: `DefaultAudioSink` turns an unhandled format into a
     * `ConfigurationException` and the song does not play at all. Trading every song on an unusual
     * device for a level adjustment would be a spectacularly bad bargain, so an encoding this does
     * not understand simply gets no normalisation.
     */
    private var supported = true

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        supported = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT
        if (!supported) return inputAudioFormat
        // Per sample rather than per frame, so the channel count belongs in it: the loop below
        // walks interleaved samples, and a stereo song would otherwise ramp twice as fast as a
        // mono one for no reason anybody could name.
        val samplesPerSecond = RAMP_SECONDS * inputAudioFormat.sampleRate * inputAudioFormat.channelCount
        step = (1.0 / samplesPerSecond).toFloat()
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val target = gain
        val output = replaceOutputBuffer(remaining)

        // Byte for byte when there is nothing to do, which is the case for a song already at the
        // target, for any file whose loudness could not be measured, and for any encoding this
        // does not understand.
        if (!supported || (target == 1f && applied == 1f)) {
            output.put(inputBuffer)
            output.flip()
            return
        }

        val input = inputBuffer.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer()
        val out = output.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer()

        while (input.hasRemaining()) {
            if (applied != target) {
                applied = if (applied < target) {
                    (applied + step).coerceAtMost(target)
                } else {
                    (applied - step).coerceAtLeast(target)
                }
            }
            val scaled = softClip(input.get() / 32768f * applied)
            out.put((scaled * 32767f).toInt().coerceIn(-32768, 32767).toShort())
        }

        // The shorts were written through a view, which leaves the byte buffer's own position
        // where it started — so it is moved on by hand before flipping, exactly as far as was
        // read. `replaceOutputBuffer` hands back a buffer whose limit is its *capacity*, which
        // can be larger, so flipping without this would publish silence or stale audio.
        inputBuffer.position(inputBuffer.limit())
        output.position(remaining)
        output.flip()
    }

    override fun onFlush() {
        // A seek does not change how loud the recording is, so the gain stays; only the ramp is
        // abandoned, since the audio either side of a seek is not continuous anyway.
        applied = gain
    }

    override fun onReset() {
        gain = 1f
        applied = 1f
    }
}
