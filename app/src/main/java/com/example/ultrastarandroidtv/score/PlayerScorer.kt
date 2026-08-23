package com.example.ultrastarandroidtv.score

import com.example.ultrastarandroidtv.pitch.PitchReading
import com.example.ultrastarandroidtv.song.BeatTimeConverter
import com.example.ultrastarandroidtv.song.VoicePart
import kotlin.math.roundToInt

/**
 * Scores one singer against one [VoicePart]. A duet needs two of these, one per part; two
 * players on a solo song get two scorers over the same part.
 *
 * **Scoring is per beat, not per pitch reading.** Each beat of each note is worth points, and
 * is judged by sampling the singer's pitch once, at the beat's midpoint. That keeps the
 * maximum a property of the song alone — a dropped USB packet or a change to the analysis hop
 * size must not change what a perfect performance is worth. The pitch tracker produces roughly
 * 47 readings a second against roughly 16 beats a second at a typical UltraStar `#BPM`, so
 * every beat has a reading to hand; where a beat is shorter than the gap between readings,
 * consecutive beats simply share one, which is the honest answer.
 *
 * The beat is judged by whichever of the two readings bracketing its midpoint is nearer to it,
 * rather than simply the first one to arrive after it. On a one-beat note the reading that
 * crosses the midpoint can already be past the end of the note, and scoring a note by audio
 * from the note after it is exactly the kind of error that makes fast passages unsingable.
 *
 * Feed it with [update] as readings arrive. Time must run forwards: the scorer walks the notes
 * with a cursor and never looks back, so seeking backwards means [reset] and starting again.
 * Beats that pass before the first reading arrives count as unsung.
 *
 * Not thread-safe, and the reading and the clock come from different places — capture runs on
 * its own thread while playback position comes from the player. Timestamp each reading where
 * it is produced, then drive [update] and read [snapshot] from a single thread.
 *
 * @param beats converts the song's beats to playback seconds; build it from the song metadata.
 */
class PlayerScorer(
    voicePart: VoicePart,
    private val beats: BeatTimeConverter,
    private val config: ScoringConfig = ScoringConfig(),
) {
    /** Every note of the part in singing order, each carrying its own running result. */
    val noteScores: List<NoteScore> =
        voicePart.lines.flatMapIndexed { lineIndex, line ->
            line.notes.map { NoteScore(it, lineIndex) }
        }

    private val lineMaxPoints = IntArray(voicePart.lines.size)

    init {
        for (score in noteScores) lineMaxPoints[score.lineIndex] += score.maxPoints
    }

    private val maxPoints = lineMaxPoints.sum()
    private val scorableLineCount = lineMaxPoints.count { it > 0 }
    private val lineEarnedPoints = IntArray(lineMaxPoints.size)

    private var noteIndex = 0
    private var beatOffset = 0
    private var beatsScored = 0
    private var beatsHit = 0
    private var basePoints = 0
    private var goldenPoints = 0
    private var lastReading: PitchReading? = null
    private var lastTime = 0.0

    /** The note being sung right now, or null once the part is finished. */
    val activeNote: NoteScore? get() = noteScores.getOrNull(noteIndex)

    /**
     * Offers [reading] as the singer's pitch at [songTimeSeconds], scoring every beat whose
     * midpoint has now passed.
     *
     * [songTimeSeconds] is the playback position of the *audio the reading describes*, not of
     * the moment it was delivered — a reading covers a window ending when it arrives, so
     * subtract half of `PitchTracker.windowSeconds` from the playback clock.
     */
    fun update(songTimeSeconds: Double, reading: PitchReading) {
        while (noteIndex < noteScores.size) {
            val score = noteScores[noteIndex]
            if (beatOffset >= score.note.durationBeats) {
                noteIndex++
                beatOffset = 0
                continue
            }

            val beat = score.note.startBeat + beatOffset
            val midpoint = (beats.beatToSeconds(beat) + beats.beatToSeconds(beat + 1)) / 2.0
            if (songTimeSeconds < midpoint) break

            evaluate(score, beatOffset, nearest(midpoint, songTimeSeconds, reading))
            beatOffset++
        }
        lastReading = reading
        lastTime = songTimeSeconds
    }

    /**
     * Whichever of the readings bracketing [midpoint] is closer to it, or null when even that
     * one is too old to say anything about the beat — a stalled capture must cost the beats it
     * swallowed rather than scoring them from whatever was sung before the stall.
     */
    private fun nearest(midpoint: Double, now: Double, reading: PitchReading): PitchReading? {
        val previous = lastReading
        if (previous != null && midpoint - lastTime < now - midpoint) {
            return if (midpoint - lastTime > config.maxHoldSeconds) null else previous
        }
        return if (now - midpoint > config.maxHoldSeconds) null else reading
    }

    fun snapshot(): ScoreSnapshot {
        if (maxPoints == 0) return ScoreSnapshot.EMPTY

        // Scale golden out of the combined figure rather than rounding the two separately, so
        // the parts always add back up to the whole.
        val noteTotal = scaleToPool(basePoints + goldenPoints)
        val golden = scaleToPool(goldenPoints)
        return ScoreSnapshot(
            notePoints = noteTotal - golden,
            goldenPoints = golden,
            lineBonus = lineBonus(),
            beatsHit = beatsHit,
            beatsScored = beatsScored,
        )
    }

    /** Returns to the start of the part, discarding every point and every recorded beat. */
    fun reset() {
        noteIndex = 0
        beatOffset = 0
        beatsScored = 0
        beatsHit = 0
        basePoints = 0
        goldenPoints = 0
        lastReading = null
        lastTime = 0.0
        lineEarnedPoints.fill(0)
        for (score in noteScores) score.reset()
    }

    /** A null [reading] means nothing usable covered this beat, which scores as unsung. */
    private fun evaluate(score: NoteScore, beatOffset: Int, reading: PitchReading?) {
        val note = score.note
        val sungMidi = if (reading != null && reading.voiced) reading.midi else Float.NaN
        val hit = !sungMidi.isNaN() &&
            (note.type.ignoresPitch ||
                isPitchHit(sungMidi, note.pitch, config.toleranceSemitones))

        val weight = note.type.beatWeight
        val firstHitOfNote = hit && score.beatsHit == 0
        score.record(
            beatOffset, sungMidi, hit, weight,
            reading?.level ?: Float.NaN,
            reading?.probability ?: Float.NaN,
        )
        if (weight == 0) return // Freestyle: drawn on the pitch bar, never scored.

        beatsScored++
        if (!hit) return

        beatsHit++
        basePoints++
        if (note.type.isGolden) goldenPoints++
        lineEarnedPoints[score.lineIndex] += weight

        if (firstHitOfNote) creditOnset(score, beatOffset, weight)
    }

    /**
     * Gives back the beats between a note's start and the moment the singer landed it.
     *
     * Only ever runs on the *first* beat of a note to be hit, so a singer who drops out in the
     * middle of a note is not handed the gap back — this pays for the run-up to a note, which is
     * the part nobody is actually late for, and nothing else.
     */
    private fun creditOnset(score: NoteScore, hitBeatOffset: Int, weight: Int) {
        if (hitBeatOffset == 0 || config.onsetGraceSeconds <= 0.0) return

        // All or nothing, measured to the beat that was actually landed. Forgiving whatever
        // happens to fall inside the window regardless of when the singer arrived would pay a
        // genuinely late entry for the beginning of a note they were nowhere near.
        val note = score.note
        val noteStart = beats.beatToSeconds(note.startBeat)
        val noteSeconds = beats.beatToSeconds(note.startBeat + note.durationBeats) - noteStart

        // Never more than a share of the note itself: a short one fits entirely inside the
        // window, and catching only its last beat must not hand over the whole thing.
        val grace = minOf(config.onsetGraceSeconds, noteSeconds * config.maxGraceShare)
        val landedAfter = beats.beatToSeconds(note.startBeat + hitBeatOffset) - noteStart
        if (landedAfter > grace) return

        for (offset in 0 until hitBeatOffset) {
            if (!score.creditOnset(offset, weight)) continue

            beatsHit++
            basePoints++
            if (score.note.type.isGolden) goldenPoints++
            lineEarnedPoints[score.lineIndex] += weight
        }
    }

    private fun scaleToPool(points: Int): Int =
        (NOTE_POOL.toDouble() * points / maxPoints).roundToInt()

    /** Each scorable line is worth an equal share of [MAX_LINE_BONUS], scaled by how much of it landed. */
    private fun lineBonus(): Int {
        if (scorableLineCount == 0) return 0
        var completeness = 0.0
        for (i in lineMaxPoints.indices) {
            val max = lineMaxPoints[i]
            if (max > 0) completeness += lineEarnedPoints[i].toDouble() / max
        }
        return (MAX_LINE_BONUS.toDouble() * completeness / scorableLineCount).roundToInt()
    }
}
