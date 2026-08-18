package com.example.ultrastarandroidtv.game

/**
 * Decides which microphone somebody is singing into.
 *
 * This is how a singer says "I am this one" without touching the remote, using the only
 * controller a singer is actually holding.
 *
 * Three conditions, and all of them earn their place:
 *
 *  - **Loud enough.** Fed the same threshold gameplay scores with, so a mic that can claim a
 *    slot is a mic that can score.
 *  - **Clearly louder than every other mic.** The mics hear each other across a room — that is
 *    the whole reason the sensitivity setting exists — so absolute loudness alone would let one
 *    voice claim both slots. Requiring a clear margin makes proximity the deciding factor,
 *    which is exactly what "the mic in my hand" means. The margin is measured against *all* the
 *    mics, including ones already spoken for, because the singer who has to be ruled out is
 *    usually the one who has already claimed a microphone and is standing next to you.
 *  - **Held for a moment.** A cough, a chair, a door: one loud instant should not commit
 *    anybody to a slot they then have to undo.
 *
 * When the margin is what fails, [contested] says so, and the screen can ask for one voice at a
 * time rather than leaving two children shouting at a meter that never fills.
 *
 * Pure and frame-driven — no audio, no Android — so all of that is testable without a device.
 */
class MicClaim(
    /** Same figure gameplay uses, so claiming and scoring agree about what counts as singing. */
    private val minLevel: Float,
    /** How much louder than the next mic a voice must be. */
    private val dominance: Float = 2.0f,
    /** How long that has to hold before it counts. */
    private val holdSeconds: Double = 0.35,
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

/**
 * Whether the screen can *name* the microphone it is asking about, or has to discover it.
 *
 * Naming one — "who has microphone 1?" — is the better question whenever it is answerable,
 * because it removes the race: one slot is open, it belongs to a specific device, and two people
 * singing at once can no longer produce a wrong answer, only a pause. But it is only answerable
 * when **every attached microphone must be in somebody's hand**. Ask "who has microphone 1?" with
 * a spare mic sitting on the table and the honest answer may be "nobody", which is a dead end the
 * room cannot get out of by singing.
 *
 * So the rule is a count. With as many singers as microphones, each one is held by definition and
 * the app names it. With microphones to spare, the app cannot know which ones were picked up, so
 * it asks the person to sing and works out which mic heard them.
 *
 * That single rule covers both awkward cases. **One singer with two mics** discovers, because
 * only the microphone is in doubt. **Two singers with three mics** discovers too, for the same
 * reason rather than as a special case. And discovery is no longer the old free-for-all: the
 * claim lands visibly on one microphone's own meter and the next question names it, so even when
 * two people do sing at once, the answer is something you can see rather than something you have
 * to take on trust.
 */
fun namesTheMicrophone(micCount: Int, playerCount: Int): Boolean = micCount in 1..playerCount
