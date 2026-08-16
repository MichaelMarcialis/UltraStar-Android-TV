package com.example.ultrastarandroidtv.game

/**
 * Decides which microphone somebody is singing into.
 *
 * This is how a singer says "I am this one" without touching the remote: whoever speaks first
 * takes the first free slot, the second speaker takes the next. It is the "press a button to
 * join" step every console game has, using the only controller a singer is actually holding.
 *
 * Three conditions, and all of them earn their place:
 *
 *  - **Loud enough.** Fed the same threshold gameplay scores with, so a mic that can claim a
 *    slot is a mic that can score.
 *  - **Clearly louder than the others.** The mics hear each other across a room — that is the
 *    whole reason the sensitivity setting exists — so absolute loudness alone would let one
 *    voice claim both slots. Requiring a clear margin makes proximity the deciding factor,
 *    which is exactly what "the mic in my hand" means.
 *  - **Held for a moment.** A cough, a chair, a door: one loud instant should not commit
 *    anybody to a slot they then have to undo.
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
        var best = -1
        var bestLevel = 0f
        var runnerUp = 0f

        for (i in levels.indices) {
            if (i >= eligible.size || !eligible[i]) continue
            val level = levels[i]
            if (level > bestLevel) {
                runnerUp = bestLevel
                bestLevel = level
                best = i
            } else if (level > runnerUp) {
                runnerUp = level
            }
        }

        val clear = best >= 0 && bestLevel >= minLevel && bestLevel >= runnerUp * dominance
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
    }
}
