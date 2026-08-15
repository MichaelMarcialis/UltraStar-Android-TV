package com.example.ultrastarandroidtv.game

import kotlin.math.exp

/** Longest step believed to be a real frame; anything larger is a stall or a restart. */
private const val MAX_STEP_SECONDS = 0.1

/**
 * The slice of pitch the track is drawing, following the passage being sung.
 *
 * A real song is wide — "Free" covers 22 semitones — so a fixed scale over the whole range
 * leaves any one phrase in a third of the height, with the intervals too small to read and the
 * notes bunched wherever that phrase happens to sit. Following the visible notes instead gives
 * every passage the full height.
 *
 * The cost of following is that the ground can move under the singer, so it is eased rather
 * than snapped: the range slides toward its target with a time constant, which reads as the
 * view panning gently and never as a jump. Easing is done against elapsed song time rather than
 * per frame, so a dropped frame slows nothing down and the motion is the same whatever the
 * display is doing.
 *
 * Not thread-safe; owned by the draw pass, which is single-threaded.
 */
class PitchRange(
    private val minSpanSemitones: Float = 11f,
    private val paddingSemitones: Float = 2.5f,
    /** Roughly how long the view takes to settle after the melody moves. */
    private val secondsToSettle: Double = 0.4,
) {
    var low: Float = Float.NaN
        private set

    var high: Float = Float.NaN
        private set

    private var lastNowSeconds = Double.NaN

    /** False until the first passage has been seen and there is anything to draw against. */
    val isReady: Boolean get() = !low.isNaN()

    /**
     * Eases toward covering [target] at song time [nowSeconds].
     *
     * A null [target] — nothing on screen, during an intro or a long rest — holds the current
     * range rather than collapsing it, so the notes after the rest arrive where the singer last
     * saw them instead of sliding in from somewhere else.
     */
    fun follow(target: IntRange?, nowSeconds: Double) {
        val step = when {
            lastNowSeconds.isNaN() -> 0.0
            else -> (nowSeconds - lastNowSeconds).coerceIn(0.0, MAX_STEP_SECONDS)
        }
        lastNowSeconds = nowSeconds
        if (target == null) return

        var wantLow = target.first - paddingSemitones
        var wantHigh = target.last + paddingSemitones

        // A phrase sitting on one note would otherwise be magnified until a semitone of wobble
        // looked like a leap.
        val shortfall = minSpanSemitones - (wantHigh - wantLow)
        if (shortfall > 0f) {
            wantLow -= shortfall / 2f
            wantHigh += shortfall / 2f
        }

        if (!isReady) {
            low = wantLow
            high = wantHigh
            return
        }

        val approach = (1.0 - exp(-step / secondsToSettle)).toFloat()
        low += (wantLow - low) * approach
        high += (wantHigh - high) * approach
    }

    /** Forgets where it was, so the next passage is snapped to rather than slid toward. */
    fun reset() {
        low = Float.NaN
        high = Float.NaN
        lastNowSeconds = Double.NaN
    }
}
