package com.example.ultrastarandroidtv.game

import com.example.ultrastarandroidtv.score.ultraStarPitchToMidi
import com.example.ultrastarandroidtv.song.BeatTimeConverter
import com.example.ultrastarandroidtv.song.Note
import com.example.ultrastarandroidtv.song.VoicePart

/** Seconds of song visible across the full width of a track. */
const val DEFAULT_WINDOW_SECONDS: Double = 5.0

/**
 * Where "now" sits across the width, as a fraction from the left.
 *
 * Not centred: the note just sung is worth a glance, but the note about to be sung is worth
 * far more, so most of the width goes to what is coming.
 */
const val DEFAULT_PLAYHEAD_FRACTION: Float = 0.3f

/** A song with a narrow range would otherwise be stretched until a semitone looked like a leap. */
private const val MIN_SPAN_SEMITONES = 12

/** A note with its timing and pitch worked out once, in the units the track draws in. */
class PlacedNote(
    val note: Note,
    /** Which lyric line this note belongs to — where the syllable layout starts afresh. */
    val lineIndex: Int,
    val startSeconds: Double,
    val endSeconds: Double,
    /** The note's own pitch as MIDI, which is also what a sung pitch is folded towards. */
    val midi: Int,
    /**
     * When each beat of this note was judged. The scorer samples the singer at each beat's
     * midpoint, so these are exactly the times its recorded pitches describe — the trace is
     * plotted at them rather than at anything re-derived, which is what keeps the drawn line
     * and the awarded points telling the same story.
     */
    val beatMidSeconds: DoubleArray,
)

/**
 * Turns song time and sung pitch into positions on the scrolling track.
 *
 * Time runs left to right, so a note approaches the playhead from the right and leaves past it
 * on the left. Because the lyrics are drawn on this same x axis, a syllable always sits under
 * its own note and a rest in the melody is literally a gap on screen — the singer reads the
 * rhythm as spacing rather than having to infer it.
 *
 * Deliberately free of Compose and Android so the arithmetic can be tested on the JVM. Nothing
 * here holds mutable state: every method is a pure function of the song and the current time,
 * which is what lets the draw pass run from a frame callback without any synchronisation.
 */
class TrackGeometry(
    part: VoicePart,
    beats: BeatTimeConverter,
    val windowSeconds: Double = DEFAULT_WINDOW_SECONDS,
    val playheadFraction: Float = DEFAULT_PLAYHEAD_FRACTION,
    paddingSemitones: Int = 2,
) {
    /**
     * Every note of the part in singing order.
     *
     * Built by the same flattening [com.example.ultrastarandroidtv.score.PlayerScorer] uses, so
     * `placements[i]` and `noteScores[i]` are the same note and a trace can be looked up by
     * index. `TrackGeometryTest` pins that correspondence rather than trusting this comment.
     */
    val placements: List<PlacedNote> = part.lines.flatMapIndexed { lineIndex, line ->
        line.notes.map { note ->
            PlacedNote(
                note = note,
                lineIndex = lineIndex,
                startSeconds = beats.beatToSeconds(note.startBeat),
                endSeconds = beats.beatToSeconds(note.startBeat + note.durationBeats),
                midi = ultraStarPitchToMidi(note.pitch),
                beatMidSeconds = DoubleArray(note.durationBeats) { offset ->
                    val beat = note.startBeat + offset
                    (beats.beatToSeconds(beat) + beats.beatToSeconds(beat + 1)) / 2.0
                },
            )
        }
    }

    /** Lowest and highest MIDI note drawn, padded so the extremes are not flush against the edge. */
    val lowMidi: Int
    val highMidi: Int

    init {
        val pitches = placements.map { it.midi }
        val low = (pitches.minOrNull() ?: 60) - paddingSemitones
        val high = (pitches.maxOrNull() ?: 72) + paddingSemitones
        val shortfall = MIN_SPAN_SEMITONES - (high - low)
        if (shortfall > 0) {
            // Grow around the middle so a narrow song sits centred rather than pinned low.
            lowMidi = low - shortfall / 2
            highMidi = high + (shortfall - shortfall / 2)
        } else {
            lowMidi = low
            highMidi = high
        }
    }

    private val span: Float = (highMidi - lowMidi).toFloat()

    /** Horizontal position of song time [seconds] when the song has reached [nowSeconds]. */
    fun xFor(seconds: Double, nowSeconds: Double, width: Float): Float =
        width * (playheadFraction + ((seconds - nowSeconds) / windowSeconds).toFloat())

    /**
     * Vertical position of [midi] against the song's whole range.
     *
     * Only a fallback, for before anything is on screen. Real songs are wide — a two-octave
     * range is ordinary — so scaling to all of it leaves any one passage using a third of the
     * height and every interval too small to read. Prefer the overload that takes a range and
     * feed it from [PitchRange].
     */
    fun yFor(midi: Float, height: Float): Float =
        height * (1f - ((midi - lowMidi) / span)).coerceIn(0f, 1f)

    /** Vertical position of [midi] against an explicit range, which is what the track draws with. */
    fun yFor(midi: Float, height: Float, low: Float, high: Float): Float {
        val extent = (high - low).takeIf { it > 0.001f } ?: 1f
        return height * (1f - ((midi - low) / extent)).coerceIn(0f, 1f)
    }

    /** Lowest and highest note among [indices], or null if there are none. */
    fun rangeOver(indices: IntRange): IntRange? {
        if (indices.isEmpty()) return null
        var low = Int.MAX_VALUE
        var high = Int.MIN_VALUE
        for (i in indices) {
            val midi = placements[i].midi
            if (midi < low) low = midi
            if (midi > high) high = midi
        }
        return low..high
    }

    /** Song time at the left edge of the track — the oldest thing still on screen. */
    fun startOfWindow(nowSeconds: Double): Double =
        nowSeconds - windowSeconds * playheadFraction

    /** Song time at the right edge — the furthest ahead the singer can see. */
    fun endOfWindow(nowSeconds: Double): Double =
        nowSeconds + windowSeconds * (1.0 - playheadFraction)

    /**
     * Indices into [placements] of the notes touching the visible window, or an empty range.
     *
     * Found by binary search rather than a saved cursor: the draw pass then holds no state at
     * all, so it stays correct if the song is restarted or drawn twice in a frame.
     */
    fun visibleIndices(nowSeconds: Double): IntRange {
        if (placements.isEmpty()) return IntRange.EMPTY
        val from = startOfWindow(nowSeconds)
        val to = endOfWindow(nowSeconds)

        // First note that has not already finished before the window opens.
        var lo = 0
        var hi = placements.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (placements[mid].endSeconds < from) lo = mid + 1 else hi = mid
        }
        val first = lo

        // First note starting after the window closes.
        hi = placements.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (placements[mid].startSeconds <= to) lo = mid + 1 else hi = mid
        }
        return if (first >= lo) IntRange.EMPTY else first until lo
    }

    /** The note being sung at [nowSeconds], for highlighting its syllable, or null between notes. */
    fun activeIndex(nowSeconds: Double): Int? {
        for (i in visibleIndices(nowSeconds)) {
            val placed = placements[i]
            if (nowSeconds >= placed.startSeconds && nowSeconds < placed.endSeconds) return i
        }
        return null
    }
}

/**
 * Moves [midi] into the octave nearest [targetMidi], leaving it within a tritone.
 *
 * Scoring compares pitch classes, so singing an octave below the written note is a hit — and
 * drawing it where it was literally sung would put the trace off the bottom of the track while
 * the score went up, which reads as a bug. Folding shows what the scorer actually credited.
 * The remaining distance from the note is real and stays visible: this corrects the octave, not
 * the singing.
 */
fun foldToOctaveNear(midi: Float, targetMidi: Int): Float {
    if (midi.isNaN()) return Float.NaN
    var folded = midi
    while (folded - targetMidi > 6f) folded -= 12f
    while (targetMidi - folded > 6f) folded += 12f
    return folded
}
