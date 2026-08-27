package com.example.ultrastarandroidtv.audio

import kotlin.math.log10
import kotlin.math.pow

/**
 * How loud one recording is, measured rather than declared.
 *
 * Both numbers are needed and they answer different questions. [rms] is how loud the song
 * *sounds*, which is what has to be made consistent between songs. [peak] is how much room is
 * left before it clips, which is what decides how far a quiet song may actually be turned up —
 * a track can be quiet on average and still touch full scale on one snare hit.
 *
 * Both are normalised to full scale, so 1.0 is the loudest a sample can be.
 */
data class Loudness(val rms: Float, val peak: Float) {

    /** Loudness in dB relative to full scale — negative, and around -12 for a modern pop master. */
    val rmsDbfs: Double get() = amplitudeToDb(rms)

    companion object {
        /** Nothing measurable: silence, or a file that could not be decoded. */
        val UNKNOWN = Loudness(0f, 0f)
    }
}

/**
 * What every song is turned towards, in dB relative to full scale.
 *
 * Measured against this library rather than picked off a standard: the songs on the card sit
 * between roughly -19 and -9 dBFS RMS, and this is near the top of that spread. Aiming high is
 * deliberate — the alternative is turning the loud majority down to meet the few quiet ones,
 * which leaves the whole evening quieter than the television was set for and simply moves the
 * problem to the remote.
 *
 * Plain RMS rather than a K-weighted LUFS measurement. The weighting matters when comparing a
 * podcast with an orchestra; for a card full of pop records it moves everything by about the same
 * amount, and it would be a filter to write, tune and get wrong for no audible gain here.
 */
const val TARGET_RMS_DBFS: Double = -13.0

/** The most a quiet song may be turned up. Past this it is a bad rip, and lifting the noise with it. */
const val MAX_BOOST_DB: Double = 10.0

/** The most a loud song may be turned down. */
const val MAX_CUT_DB: Double = 12.0

/**
 * How close to full scale the loudest sample is allowed to land after the gain.
 *
 * Not 1.0: the peak is measured from a *sample* of the file, so the true peak is very likely a
 * little higher than what was seen, and inter-sample peaks are higher again.
 */
const val PEAK_CEILING: Float = 0.94f

/**
 * How far past the peak headroom a boost may still go, in dB.
 *
 * Without this, one snare hit touching full scale would block the normalisation of an otherwise
 * quiet recording entirely — and quiet-with-transients is exactly the shape of the old rips this
 * is meant to rescue. The overshoot lands on [softClip] rather than on the wall, so what it costs
 * is a moment of gentle compression on the loudest hits instead of the whole song staying quiet.
 */
const val PEAK_OVERSHOOT_DB: Double = 3.0

/** Amplitude, 0..1, as dB relative to full scale. Silence is a large negative rather than -inf. */
fun amplitudeToDb(amplitude: Float): Double =
    if (amplitude <= 0f) -120.0 else 20.0 * log10(amplitude.toDouble())

fun dbToAmplitude(db: Double): Float = 10.0.pow(db / 20.0).toFloat()

/**
 * The playback gain that brings [loudness] to [targetDbfs], bounded by what is safe and sensible.
 *
 * Returns 1 for anything unmeasurable, which is the right answer for a file that would not
 * decode: leave it exactly as it was rather than guess.
 *
 * Three limits apply, in this order — how far a song may be moved at all ([MAX_BOOST_DB] /
 * [MAX_CUT_DB]), and then how much room is left above its own loudest sample. The peak limit can
 * only ever *reduce* a boost, never turn one into a cut, because a song that already clips does
 * not need to be made quieter than it is to sound as loud as the others.
 */
fun gainFor(
    loudness: Loudness,
    targetDbfs: Double = TARGET_RMS_DBFS,
    maxBoostDb: Double = MAX_BOOST_DB,
    maxCutDb: Double = MAX_CUT_DB,
    peakCeiling: Float = PEAK_CEILING,
    peakOvershootDb: Double = PEAK_OVERSHOOT_DB,
): Float {
    if (loudness.rms <= 0f) return 1f

    val wanted = (targetDbfs - loudness.rmsDbfs).coerceIn(-maxCutDb, maxBoostDb)
    val gain = dbToAmplitude(wanted)
    if (gain <= 1f || loudness.peak <= 0f) return gain

    val headroom = (peakCeiling / loudness.peak) * dbToAmplitude(peakOvershootDb)
    return gain.coerceAtMost(headroom.coerceAtLeast(1f))
}

/**
 * Where the soft knee starts, as a fraction of full scale.
 *
 * Everything below this is passed through untouched — which on real material is very nearly all
 * of it, so the normalisation is a level change rather than a compression.
 */
private const val SOFT_KNEE = 0.82f

/**
 * Rounds off anything that would otherwise clip, instead of chopping it flat.
 *
 * Hard clipping is a discontinuity and it sounds like one: a buzz on the loudest moment of the
 * song, which is the moment everybody is listening to. Bending the top of the curve instead
 * costs a little harmonic distortion on peaks nobody can hear individually.
 */
fun softClip(sample: Float): Float = when {
    sample > SOFT_KNEE -> SOFT_KNEE + (1f - SOFT_KNEE) * kotlin.math.tanh((sample - SOFT_KNEE) / (1f - SOFT_KNEE))
    sample < -SOFT_KNEE -> -SOFT_KNEE - (1f - SOFT_KNEE) * kotlin.math.tanh((-sample - SOFT_KNEE) / (1f - SOFT_KNEE))
    else -> sample
}
