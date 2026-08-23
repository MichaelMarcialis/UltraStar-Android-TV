package com.example.ultrastarandroidtv.score

/**
 * Why a performance scored what it did, broken down by the only distinction that decides what to
 * fix next: whether the beats that were missed had **a voice in them at all**.
 *
 * A missed beat has exactly one of two stories, and they lead in opposite directions:
 *
 *  - **Nothing was heard.** The singer was singing — they said so — but no pitch reached the
 *    scorer for that beat. That is this app's problem: the loudness gate, the analysis window,
 *    the moment a note takes to become a pitch. More work on latency would move it.
 *  - **Something was heard and it was too far from the note.** That is a judgement about how
 *    close is close enough, and no amount of latency work will change it. The lever is
 *    [ScoringConfig.toleranceSemitones], which is a difficulty setting.
 *
 * Guessing between those two is how a project spends a week optimising the half that was already
 * fine, which is why this counts them instead.
 */
data class MissBreakdown(
    val beatsScored: Int,
    val beatsHit: Int,
    /** Missed with no pitch detected at all — silence, or a voice under the gate. */
    val missedUnheard: Int,
    /** Of those, the ones quieter than the gate: too quiet to count as singing. */
    val missedTooQuiet: Int,
    /** Of those, the ones loud enough but with no pitch the detector would commit to. */
    val missedNoPitch: Int,
    /**
     * Of the no-pitch beats, those no reading covered at all.
     *
     * Should be near zero: readings land every 21 ms against beats of 50-60 ms. Anything else
     * means capture stalled, which is a bug rather than a tuning question.
     */
    val missedNoReading: Int,
    /**
     * Of the no-pitch beats, those where the audio simply had no periodic signal in it —
     * consonants, breath, the noise between words. Not recoverable by any threshold: there is
     * no pitch in a `t` to find.
     */
    val missedNoPeriod: Int,
    /** Missed with a pitch detected, but outside the tolerance. */
    val missedOffPitch: Int,
    /** Distance from the note for every off-pitch miss, in semitones, ascending. */
    val offPitchDistances: List<Float>,
    /** Off-pitch misses falling in the first third of their note — the run-up into it. */
    val offPitchAtNoteStart: Int,
    /**
     * For the beats that were loud enough but got no pitch, how near the detector came to
     * committing, ascending. Beats where it found no period at all are absent: no confidence
     * floor would have rescued them.
     */
    val nearMissConfidences: List<Float>,
) {
    /** Beats a lower confidence floor would have turned into readings. */
    fun heardAtConfidence(floor: Float): Int = nearMissConfidences.count { it >= floor }

    val accuracy: Double get() = if (beatsScored == 0) 0.0 else beatsHit.toDouble() / beatsScored

    /**
     * What the accuracy would have been at [tolerance], had nothing else changed.
     *
     * The number that settles the argument: if widening the window barely moves it, the misses
     * are not near misses and being more generous would only be handing out points.
     */
    fun accuracyAt(tolerance: Float): Double {
        if (beatsScored == 0) return 0.0
        val extra = offPitchDistances.count { it <= tolerance }
        return (beatsHit + extra).toDouble() / beatsScored
    }

    /** Half the off-pitch misses are nearer than this. */
    val medianOffPitch: Float
        get() = if (offPitchDistances.isEmpty()) 0f
        else offPitchDistances[offPitchDistances.size / 2]

    fun summary(): String = buildString {
        append("beats %d, hit %d (%.0f%%)".format(beatsScored, beatsHit, accuracy * 100))
        append(
            " | unheard %d (%.0f%%): too quiet %d, no pitch %d (no reading %d, no period %d)"
                .format(
                    missedUnheard, share(missedUnheard) * 100, missedTooQuiet, missedNoPitch,
                    missedNoReading, missedNoPeriod,
                ),
        )
        if (nearMissConfidences.isNotEmpty()) {
            append(" (of the no-pitch,")
            for (floor in listOf(0.8f, 0.75f, 0.7f, 0.6f)) {
                append(" >=%.2f:%d".format(floor, heardAtConfidence(floor)))
            }
            append(")")
        }
        append(" | off pitch %d (%.0f%%)".format(missedOffPitch, share(missedOffPitch) * 100))
        if (offPitchDistances.isNotEmpty()) {
            append(
                ", %.0f%% of them in the first third of a note".format(
                    100.0 * offPitchAtNoteStart / missedOffPitch,
                ),
            )
            append(" | median miss %.2f semitones".format(medianOffPitch))
            append(" | would score")
            for (tolerance in listOf(1.25f, 1.5f, 2f, 3f)) {
                append(" @%.2f=%.0f%%".format(tolerance, accuracyAt(tolerance) * 100))
            }
        }
    }

    private fun share(count: Int) = if (beatsScored == 0) 0.0 else count.toDouble() / beatsScored
}

/**
 * Reads the breakdown back off the notes a [PlayerScorer] has already filled in.
 *
 * Nothing extra is captured during the song for this — `NoteScore` keeps the pitch sung at every
 * beat precisely so that "sang nothing" and "sang the wrong note" stay tellable apart, and this
 * is the question that distinction was being kept for.
 */
fun missBreakdown(
    noteScores: List<NoteScore>,
    config: ScoringConfig,
    /** The loudness a voice had to reach to count — `GameSettings.micThreshold`. */
    gate: Float,
): MissBreakdown {
    var scored = 0
    var hit = 0
    var unheard = 0
    var tooQuiet = 0
    var noPitch = 0
    var noReading = 0
    var noPeriod = 0
    var atNoteStart = 0
    val offPitch = mutableListOf<Float>()
    val nearMiss = mutableListOf<Float>()

    for (score in noteScores) {
        val note = score.note
        if (note.type.beatWeight == 0) continue // Freestyle scores nothing either way.
        val target = ultraStarPitchToMidi(note.pitch).toFloat()
        val firstThird = (note.durationBeats + 2) / 3

        for (beat in 0 until score.beatsScored.coerceAtMost(note.durationBeats)) {
            scored++
            when {
                score.wasHit(beat) -> hit++
                score.sungMidi(beat).isNaN() || note.type.ignoresPitch -> {
                    unheard++
                    val level = score.levelAt(beat)
                    // NaN means no reading covered the beat at all, which is a capture stall
                    // rather than a quiet singer — counted with "no pitch" as it is not a
                    // loudness problem and the gate would not have saved it.
                    if (!level.isNaN() && level < gate) {
                        tooQuiet++
                    } else {
                        noPitch++
                        val confidence = score.probabilityAt(beat)
                        when {
                            level.isNaN() -> noReading++
                            confidence > 0f -> nearMiss += confidence
                            else -> noPeriod++
                        }
                    }
                }
                else -> {
                    offPitch += pitchClassDistance(score.sungMidi(beat), target)
                    if (beat < firstThird) atNoteStart++
                }
            }
        }
    }

    offPitch.sort()
    nearMiss.sort()
    return MissBreakdown(
        beatsScored = scored,
        beatsHit = hit,
        missedUnheard = unheard,
        missedTooQuiet = tooQuiet,
        missedNoPitch = noPitch,
        missedNoReading = noReading,
        missedNoPeriod = noPeriod,
        missedOffPitch = offPitch.size,
        offPitchDistances = offPitch,
        offPitchAtNoteStart = atNoteStart,
        nearMissConfidences = nearMiss,
    )
}
