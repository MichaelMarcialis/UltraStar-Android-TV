package com.example.ultrastarandroidtv.game

import kotlin.math.exp

/**
 * Longest step believed to be a real frame; anything larger is a stall or a restart.
 *
 * Three frames at 60 Hz rather than six. It has to be read against [ArrowMotion.secondsToSettle]
 * rather than on its own: the clamp exists so that a rendering hitch is eased through like a
 * plausible frame instead of teleporting the arrow, and once the easing got faster a tenth of a
 * second stopped being a plausible frame — it was more than three time constants, which is
 * arrival, not a step. Tightening it is what keeps the clamp doing the job it is named for.
 */
private const val MAX_STEP_SECONDS = 0.05

/**
 * Smooths one singer's pitch arrow: where it sits, and whether it is there at all.
 *
 * Readings arrive about 47 times a second and are honest rather than tidy: a steady note still
 * wanders, and the detector occasionally throws a single wild reading. Drawn literally that
 * becomes a twitching arrow that looks like the detector is unsure when it is not. The worst of
 * those outliers are already gone by here — `GameSession.Singer` medians them first.
 *
 * **The position easing is deliberately tiny and the lean's is not.** They are separate numbers
 * because they answer different questions: the position is a measurement and every millisecond it
 * lags is a millisecond of the instrument lying, while the lean is advice, and advice that
 * changes six times a second is noise. See [tiltSecondsToSettle].
 *
 * The history is the argument for keeping the position number small: median-of-5 plus a twentieth
 * of a second put the arrow 150 ms behind the voice, and since the scorer reads the *raw* pitch,
 * notes lit up well before the arrow reached them — which makes the game look like it is guessing,
 * and makes any judgement about whether the scoring is fair impossible, because what the app heard
 * cannot be told from what the animation did to it.
 *
 * **[alpha] still fades, because presence is not placement.** A voice stops and starts
 * constantly — between syllables, between breaths — and an arrow that blinks in and out on every
 * one of those is exhausting to watch. Measured with the easing off and on, fading costs the
 * arrow's *position* nothing: it decides only whether the arrow is drawn, never where. It was
 * taken off with everything else to get a clean baseline and put back once that showed it was
 * not part of the problem.
 *
 * Coming back from a *short* gap eases from where the arrow was, since that is usually the same
 * phrase continuing; coming back from a long one snaps, since the singer has almost certainly
 * moved somewhere new and sliding across the whole track to reach it would be a lie about what
 * they sang.
 *
 * Not thread-safe; owned by the draw pass.
 */
class ArrowMotion(
    /** Zero draws the reading as it arrives. */
    private val secondsToSettle: Double = 0.02,
    /**
     * How long the *lean* takes to settle, which is deliberately far longer than the position.
     *
     * The two are separate because they are answering different questions. The position is a
     * measurement — where this voice is — and every millisecond it lags is a millisecond of
     * the instrument lying. The tilt is *advice*: sing higher, sing lower. Advice that changes
     * six times a second is not advice, it is noise, and the tilt amplifies whatever the
     * position does — at 26 degrees across three semitones, a third of a semitone of ordinary
     * wobble on a held note swings the arrow three degrees.
     *
     * It also has a step to absorb that the position never has. When the note under the arrow
     * changes, the *target* moves by the interval between the two notes even though the singer
     * has not moved at all, so the lean jumped by up to its full deflection between one frame
     * and the next. Sharing the position's time constant made that jump instantaneous. This is
     * what makes it a turn.
     */
    private val tiltSecondsToSettle: Double = 0.13,
    /** A silence longer than this is treated as a fresh start rather than a continuation. */
    private val snapAfterSilenceSeconds: Double = 0.35,
    /** Roughly how long the arrow takes to fade in or out. Zero switches the arrow outright. */
    private val fadeSeconds: Double = 0.12,
) {
    private var shown = Float.NaN
    private var lastNowSeconds = Double.NaN
    private var lastVoicedSeconds = Double.NaN
    private var lastStep = 0.0
    private var shownTilt = 0f

    /** How solid to draw the arrow, 0..1. Zero means do not draw it at all. */
    var alpha: Float = 0f
        private set

    /** True when there is anything worth drawing this frame. */
    val isVisible: Boolean get() = alpha > 0.01f && !shown.isNaN()

    /**
     * Where the arrow is drawn right now, or NaN before it has a place.
     *
     * Read by the octave fold, which needs to know which octave the arrow is *already* in before
     * deciding whether the new reading is worth moving it out of — see [foldToOctaveNear].
     */
    val shownMidi: Float get() = shown

    /**
     * Returns where to draw the arrow, or NaN if it has never had a position.
     *
     * Check [alpha] as well: a silent singer keeps their last position while the arrow fades
     * out, so a returned value does not on its own mean "draw this".
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
        lastStep = step

        val voiced = !targetMidi.isNaN()
        alpha += ((if (voiced) 1f else 0f) - alpha) * approach(step, fadeSeconds)

        // Hold position through the fade-out, and remember it: a breath between two syllables
        // of the same phrase should not cost the arrow its place.
        if (!voiced) return shown

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

        shown += (targetMidi - shown) * approach(step, secondsToSettle)
        return shown
    }

    /**
     * Eases the arrow's lean toward [targetDegrees], using the step measured by the last
     * [update] — so call it once per frame, after that.
     *
     * Smoothed separately even though it is derived from an already-smoothed pitch, because the
     * tilt *amplifies*: at 26° across three semitones, a third of a semitone of ordinary wobble
     * on a held note swings the arrow about three degrees. That is invisible as a position and
     * very visible as a rotation.
     */
    fun tiltTowards(targetDegrees: Float): Float {
        shownTilt += (targetDegrees - shownTilt) * approach(lastStep, tiltSecondsToSettle)
        return shownTilt
    }

    fun reset() {
        shown = Float.NaN
        lastNowSeconds = Double.NaN
        lastVoicedSeconds = Double.NaN
        lastStep = 0.0
        shownTilt = 0f
        alpha = 0f
    }

    /**
     * Frame-rate independent easing: the same journey takes the same time whatever the fps.
     *
     * A time constant of zero means arrive immediately, which is how the position easing is
     * turned off — and is what [secondsToSettle] now defaults to.
     */
    private fun approach(step: Double, timeConstant: Double): Float =
        if (timeConstant <= 0.0) 1f else (1.0 - exp(-step / timeConstant)).toFloat()
}
