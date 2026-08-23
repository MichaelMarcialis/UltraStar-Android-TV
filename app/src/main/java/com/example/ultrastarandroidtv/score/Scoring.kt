package com.example.ultrastarandroidtv.score

import com.example.ultrastarandroidtv.song.NoteType
import kotlin.math.roundToInt

/**
 * MIDI note number for UltraStar pitch 0.
 *
 * The format stores each note's pitch as a signed semitone offset from this reference, so
 * pitch 0 is C4 and pitch 12 is C5. Real songs land in roughly -20..20, i.e. E2..G#5 — which
 * is the range a voice actually covers, and the reason to be confident in the offset.
 *
 * Scoring itself never depends on this constant: it compares pitch *classes*, and 60 is a
 * multiple of 12, so `pitch mod 12` is already the pitch class either way. The offset matters
 * only for display and for drawing notes at the right height.
 */
const val ULTRASTAR_PITCH_ZERO_MIDI: Int = 60

/** Concrete MIDI note for an UltraStar note pitch. */
fun ultraStarPitchToMidi(pitch: Int): Int = pitch + ULTRASTAR_PITCH_ZERO_MIDI

/** Points a perfect performance is worth, matching UltraStar's familiar 10000. */
const val MAX_SCORE: Int = 10_000

/** The share of [MAX_SCORE] awarded for completing whole lines rather than single beats. */
const val MAX_LINE_BONUS: Int = 1_000

/** The share of [MAX_SCORE] awarded beat by beat, golden notes included. */
internal const val NOTE_POOL: Int = MAX_SCORE - MAX_LINE_BONUS

/**
 * Points one beat of a note of this type is worth.
 *
 * Golden notes count double — that second point is reported separately as the golden bonus.
 * Freestyle notes are worth nothing and are excluded from the maximum too, so they can
 * neither earn points nor dilute the rest of the song.
 */
val NoteType.beatWeight: Int
    get() = when (this) {
        NoteType.NORMAL, NoteType.RAP -> 1
        NoteType.GOLDEN, NoteType.GOLDEN_RAP -> 2
        NoteType.FREESTYLE -> 0
    }

val NoteType.isGolden: Boolean
    get() = this == NoteType.GOLDEN || this == NoteType.GOLDEN_RAP

/** Rap notes are scored on rhythm alone: any voiced beat counts, whatever pitch it was. */
val NoteType.ignoresPitch: Boolean
    get() = this == NoteType.RAP || this == NoteType.GOLDEN_RAP

/**
 * Distance between two pitch classes in semitones, always 0..6 — the shorter way round the
 * circle, so B and C are one semitone apart rather than eleven.
 *
 * Fractional rather than rounded to whole semitones. Rounding first silently widened every
 * tolerance by half a semitone in each direction, which is how a singer could sit visibly off
 * the note bar and still be credited: the zone being scored was never the zone being drawn.
 */
fun pitchClassDistance(midiA: Float, midiB: Float): Float {
    val forward = ((midiA - midiB) % 12f + 12f) % 12f
    return minOf(forward, 12f - forward)
}

/**
 * Whether [sungMidi] counts as hitting [notePitch], octave-agnostically — singing the right
 * note an octave down scores exactly the same, which is how UltraStar has always worked and
 * the only sane rule when adults and children sing the same song.
 *
 * The window is exactly ±[toleranceSemitones] around the note, with nothing added behind the
 * scenes, so the note bar can be drawn exactly this tall and "the arrow is on the bar" and "the
 * beat scored" become the same statement.
 */
fun isPitchHit(sungMidi: Float, notePitch: Int, toleranceSemitones: Float): Boolean =
    pitchClassDistance(sungMidi, ultraStarPitchToMidi(notePitch).toFloat()) <= toleranceSemitones

/**
 * Knobs for how forgiving scoring is.
 *
 * @param toleranceSemitones how far off the target pitch class still counts as a hit, in
 *   semitones either side. The difficulty setting: 0.5 is strict, 1 is the default, 2 is
 *   generous. **This is also the drawn height of a note**, so changing it changes what the
 *   singer sees as well as what they score.
 * @param maxHoldSeconds how late a reading may arrive and still be used for a beat. Readings
 *   normally land within one hop (~21 ms) of the beat they score. Anything later means capture
 *   stalled, and guessing from stale audio would score beats nobody sang.
 * @param onsetGraceSeconds how long after a note begins the singer may take to land it without
 *   losing the beats before they did.
 *
 *   **This is not generosity, it is a debt being repaid.** A note takes real time to become a
 *   pitch: a voice has an attack before it has a fundamental, singers slide into notes rather
 *   than arriving on them, and this app's own detector needs a window of audio before it will
 *   name a note at all. None of that is the singer being late, but all of it scored as silence —
 *   which is why a phrase's opening note appeared to register halfway through.
 *
 *   The beats are credited only when the note is *actually landed*, and only when it is landed
 *   inside this window: sing it late enough and the early beats stay lost, sing nothing and
 *   nothing is credited. So it forgives the run-up without paying anyone for silence.
 *
 *   **65 ms is the measured debt and not a round number.** It is the worst case, on this
 *   hardware, of the lag that the 127 ms calibration does *not* already take off — the detector
 *   needing a window of audio before it will name a pitch, plus a phrase's opening note having
 *   to clear the loudness gate from cold. Set beyond that and it stops repaying an error and
 *   starts being difficulty, which belongs in [toleranceSemitones] where it is *visible*: that
 *   dial makes the note bars taller, so the game is seen to be more forgiving rather than
 *   quietly being so.
 *
 *   It is additionally capped at half of each note by [maxGraceShare], so **you always have to
 *   sing at least half of a note to be given all of it**. Without that a short note is entirely
 *   inside the window — a third of the notes in a real library are one or two beats long — and
 *   catching only its last beat would hand over the whole thing.
 *
 *   It does not change what a song is worth. Every beat is still scored exactly once, and the
 *   maximum stays a property of the chart alone.
 * @param maxGraceShare the most of a note that [onsetGraceSeconds] may forgive, as a share of
 *   its length.
 */
data class ScoringConfig(
    val toleranceSemitones: Float = 1f,
    val maxHoldSeconds: Double = 0.1,
    val onsetGraceSeconds: Double = 0.065,
    val maxGraceShare: Double = 0.5,
)
