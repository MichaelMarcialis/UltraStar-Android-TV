package com.example.ultrastarandroidtv.game

import kotlin.math.exp

/** Longest step believed to be a real frame; anything larger is a stall or a restart. */
private const val MAX_STEP_SECONDS = 0.1

/**
 * Which slice of pitch the track is showing.
 *
 * **The span never changes — only the centre moves.** A range that resized to fit each passage
 * made the notes visibly stretch and squash as the melody moved, which is distracting out of all
 * proportion to what it buys. Holding the span fixed means a semitone is the same distance on
 * screen for the whole song, so the shape of a phrase is something the eye can learn.
 *
 * The centre still has to move, because real songs are wide: "Free" spans 22 semitones, so a
 * view locked to one place would leave whole verses off the track. It moves as little as
 * possible — a **deadband** keeps it perfectly still until the notes start pressing against an
 * edge, so most of the song is spent completely motionless, and when it does move it eases
 * rather than jumps.
 *
 * Easing is measured against *song time* rather than per frame, so a dropped frame does not
 * change how fast the view travels.
 *
 * Not thread-safe; owned by the draw pass, which is single-threaded.
 *
 * @param spanSemitones how much pitch is on screen at once. See
 *   [TrackGeometry.visibleSpanSemitones], which sizes it from the song.
 * @param deadbandSemitones how close to the edge a note may get before the view re-centres.
 * @param secondsToSettle roughly how long a re-centre takes.
 */
class PitchRange(
    private val spanSemitones: Float,
    private val deadbandSemitones: Float = 1.5f,
    private val secondsToSettle: Double = 0.5,
) {
    private var centre = Float.NaN
    private var targetCentre = Float.NaN
    private var lastNowSeconds = Double.NaN

    /** False until the first passage has been seen and there is anything to draw against. */
    val isReady: Boolean get() = !centre.isNaN()

    val low: Float get() = centre - spanSemitones / 2f
    val high: Float get() = centre + spanSemitones / 2f

    /**
     * Eases toward showing [target] at song time [nowSeconds].
     *
     * A null [target] — nothing on screen, during an intro or a long rest — holds the view where
     * it is rather than drifting somewhere neutral, so the notes after the rest arrive where the
     * singer was already looking.
     */
    fun follow(target: IntRange?, nowSeconds: Double) {
        val step = when {
            lastNowSeconds.isNaN() -> 0.0
            else -> (nowSeconds - lastNowSeconds).coerceIn(0.0, MAX_STEP_SECONDS)
        }
        lastNowSeconds = nowSeconds
        if (target == null) return

        val wanted = (target.first + target.last) / 2f

        if (!isReady) {
            centre = wanted
            targetCentre = wanted
            return
        }

        // Only chase the melody when it is actually running out of room. Re-centring on every
        // small move would put the whole track in constant gentle motion for no benefit.
        val half = spanSemitones / 2f
        val crowdingBottom = target.first < targetCentre - half + deadbandSemitones
        val crowdingTop = target.last > targetCentre + half - deadbandSemitones
        if (crowdingBottom || crowdingTop) targetCentre = wanted

        val approach = (1.0 - exp(-step / secondsToSettle)).toFloat()
        centre += (targetCentre - centre) * approach
    }

    /** Forgets where it was, so the next passage is snapped to rather than slid toward. */
    fun reset() {
        centre = Float.NaN
        targetCentre = Float.NaN
        lastNowSeconds = Double.NaN
    }
}
