package com.example.ultrastarandroidtv.game

/**
 * How much louder than the *scoring* gate a voice must be before it claims a microphone.
 *
 * Claiming and scoring used to share one number, on the reasoning that a microphone able to claim
 * a slot must be a microphone able to score. Raising this only strengthens that: anything loud
 * enough to claim is comfortably loud enough to score.
 *
 * It was raised because of what actually happened in the room — **microphones were claiming
 * themselves as they were picked up.** A hand closing round a mic is a loud broadband thump, well
 * over a singing gate tuned to keep the television out, and it is heard by the mic being lifted
 * far more than by the one on the table, so it passes the dominance test too.
 */
const val CLAIM_LEVEL_HEADROOM = 1.6f

/**
 * How long that has to hold, and the real defence against being claimed by handling noise.
 *
 * A thump is over in a fraction of a second; a claim now needs the better part of a second of
 * continuous voice, and any dropout restarts the clock. It was 0.35 s, which is longer than a
 * cough and shorter than picking a microphone up.
 */
const val CLAIM_HOLD_SECONDS = 0.9

/**
 * Decides which microphone somebody is singing into.
 *
 * This is how a singer says "I am this one" without touching the remote, using the only
 * controller a singer is actually holding.
 *
 * Three conditions, and all of them earn their place:
 *
 *  - **Loud enough.** Comfortably above the threshold gameplay will score with — see
 *    [CLAIM_LEVEL_HEADROOM] — so a mic that can claim a slot is certainly a mic that can score.
 *  - **Clearly louder than every other mic.** The mics hear each other across a room — that is
 *    the whole reason the sensitivity setting exists — so absolute loudness alone would let one
 *    voice claim both slots. Requiring a clear margin makes proximity the deciding factor,
 *    which is exactly what "the mic in my hand" means. The margin is measured against *all* the
 *    mics, including ones already spoken for, because the singer who has to be ruled out is
 *    usually the one who has already claimed a microphone and is standing next to you.
 *  - **Held for the better part of a second.** A cough, a chair, a door — or a hand closing
 *    round the microphone as it is picked up, which is what actually kept happening. One loud
 *    instant must not commit anybody to a slot they then have to undo. See [CLAIM_HOLD_SECONDS].
 *
 * When the margin is what fails, [contested] says so, and the screen can ask for one voice at a
 * time rather than leaving two children shouting at a meter that never fills.
 *
 * Pure and frame-driven — no audio, no Android — so all of that is testable without a device.
 */
class MicClaim(
    /**
     * How loud a voice has to be to claim, as a normalised RMS.
     *
     * Callers pass the gate gameplay will score with, multiplied by [CLAIM_LEVEL_HEADROOM]. It is
     * deliberately *higher* than the scoring gate rather than equal to it — which is the stronger
     * form of the old rule that a mic able to claim is a mic able to score.
     */
    private val minLevel: Float,
    /** How much louder than the next mic a voice must be. */
    private val dominance: Float = 2.0f,
    /** How long that has to hold before it counts. */
    private val holdSeconds: Double = CLAIM_HOLD_SECONDS,
) {
    private var candidate = -1
    private var since = Double.NaN

    /** The mic currently being sung into, or -1. Drives the "keep going" feedback on screen. */
    val leading: Int get() = candidate

    /**
     * True when two or more mics are hearing a voice at once.
     *
     * The screen needs this because the alternative to saying so is saying nothing: two children
     * both singing is the commonest reason a claim will not land, and with no explanation it
     * looks like the microphones are broken rather than like the room is.
     */
    var contested: Boolean = false
        private set

    /** How far through the hold the leader is, 0..1, for a progress indicator. */
    fun progress(nowSeconds: Double): Float {
        if (candidate < 0 || since.isNaN()) return 0f
        return ((nowSeconds - since) / holdSeconds).coerceIn(0.0, 1.0).toFloat()
    }

    /**
     * How close one microphone is to being claimed, 0..1 — the number its bar draws.
     *
     * **One bar, one meaning: how close this microphone is to being yours.** The lower half is
     * getting loud enough, the upper half is holding it. They join exactly at the middle, because
     * leading requires the level to have reached [minLevel], at which point the lower half is
     * already full — so the bar rises smoothly through the handover rather than jumping.
     *
     * Nothing else is drawn beside it. The screen used to stack a level meter and a separate hold
     * bar, which asked the room to read two moving things at once and work out which one meant
     * "keep going".
     */
    fun claimProgress(index: Int, level: Float, nowSeconds: Double): Float {
        if (index == candidate) return 0.5f + 0.5f * progress(nowSeconds)
        return (level / minLevel).coerceIn(0f, 1f) * 0.5f
    }

    /**
     * Offers the current level of every mic, and returns the index of one that has just been
     * claimed — or null.
     *
     * @param levels normalised RMS per mic, parallel to [eligible].
     * @param eligible false for mics already spoken for, so a second singer cannot take a slot
     *   that is taken.
     */
    fun update(levels: FloatArray, eligible: BooleanArray, nowSeconds: Double): Int? {
        // The two loudest mics *of all of them*, claimable or not. Measuring the margin only
        // against the mics still free is what let the second slot be taken by nothing but the
        // first singer's voice bleeding across the room: with one mic left there was nothing to
        // be louder than, so the margin stopped applying exactly when it was needed most.
        var loudest = 0f
        var loudestIndex = -1
        var runnerUp = 0f
        var voices = 0

        for (i in levels.indices) {
            val level = levels[i]
            if (level >= minLevel) voices++
            if (level > loudest) {
                runnerUp = loudest
                loudest = level
                loudestIndex = i
            } else if (level > runnerUp) {
                runnerUp = level
            }
        }
        contested = voices >= 2

        var best = -1
        var bestLevel = 0f
        for (i in levels.indices) {
            if (i >= eligible.size || !eligible[i]) continue
            if (levels[i] > bestLevel) {
                bestLevel = levels[i]
                best = i
            }
        }

        // What the claimer has to beat: the next mic down if it is already the loudest, and the
        // loudest itself if it is not.
        val rival = if (best == loudestIndex) runnerUp else loudest

        val clear = best >= 0 && bestLevel >= minLevel && bestLevel >= rival * dominance
        if (!clear) {
            candidate = -1
            since = Double.NaN
            return null
        }

        // Switching singers restarts the clock: the hold has to be one continuous voice.
        if (best != candidate) {
            candidate = best
            since = nowSeconds
            return null
        }

        return if (nowSeconds - since >= holdSeconds) best else null
    }

    fun reset() {
        candidate = -1
        since = Double.NaN
        contested = false
    }
}
