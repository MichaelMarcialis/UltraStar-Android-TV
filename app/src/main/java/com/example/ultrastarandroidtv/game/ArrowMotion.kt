package com.example.ultrastarandroidtv.game

import kotlin.math.exp

/** Longest step believed to be a real frame; anything larger is a stall or a restart. */
private const val MAX_STEP_SECONDS = 0.1

/**
 * Smooths one singer's pitch arrow so it glides instead of twitching.
 *
 * Readings arrive about 47 times a second and are honest rather than tidy: a steady note still
 * wobbles a fraction of a semitone reading to reading. Drawn literally, the arrow buzzes, and a
 * buzzing arrow is hard to read and looks like the detector is unsure when it is not.
 *
 * The smoothing is deliberately fast — around a twentieth of a second — because this is an
 * instrument, not a decoration. Too much smoothing and a singer correcting their pitch sees the
 * arrow agree with them late, which is worse than a little jitter.
 *
 * Silence hides the arrow rather than freezing it, so "not singing" never looks like "singing
 * the same note". Coming back from a *short* gap eases from where the arrow was, since that is
 * usually the same phrase continuing; coming back from a long one snaps, since the singer has
 * almost certainly moved somewhere new and sliding across the whole track to reach it would be
 * a lie about what they sang.
 *
 * Not thread-safe; owned by the draw pass.
 */
class ArrowMotion(
    private val secondsToSettle: Double = 0.05,
    /** A silence longer than this is treated as a fresh start rather than a continuation. */
    private val snapAfterSilenceSeconds: Double = 0.35,
) {
    private var shown = Float.NaN
    private var lastNowSeconds = Double.NaN
    private var lastVoicedSeconds = Double.NaN

    /**
     * Returns where to draw the arrow, or NaN to hide it.
     *
     * @param targetMidi the singer's pitch now, already folded into the drawn octave, or NaN
     *   if nothing is being sung.
     */
    fun update(targetMidi: Float, nowSeconds: Double): Float {
        val step = when {
            lastNowSeconds.isNaN() -> 0.0
            else -> (nowSeconds - lastNowSeconds).coerceIn(0.0, MAX_STEP_SECONDS)
        }
        lastNowSeconds = nowSeconds

        // Hide the arrow, but remember where it was: a breath between two syllables of the same
        // phrase should not cost the arrow its place and force it to snap on the way back.
        if (targetMidi.isNaN()) return Float.NaN

        val silence = if (lastVoicedSeconds.isNaN()) {
            Double.MAX_VALUE
        } else {
            nowSeconds - lastVoicedSeconds
        }
        lastVoicedSeconds = nowSeconds

        if (shown.isNaN() || silence > snapAfterSilenceSeconds) {
            shown = targetMidi
            return shown
        }

        val approach = (1.0 - exp(-step / secondsToSettle)).toFloat()
        shown += (targetMidi - shown) * approach
        return shown
    }

    fun reset() {
        shown = Float.NaN
        lastNowSeconds = Double.NaN
        lastVoicedSeconds = Double.NaN
    }
}
