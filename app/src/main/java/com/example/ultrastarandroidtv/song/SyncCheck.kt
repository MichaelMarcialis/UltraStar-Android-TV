package com.example.ultrastarandroidtv.song

import com.example.ultrastarandroidtv.audio.Fft
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * The rate everything here works at, in hertz.
 *
 * A voice and the harmonics that identify it live well under 2 kHz, so eleven kilohertz is
 * generous and makes the transform a quarter of the work it would be at 44.1.
 */
const val CHROMA_RATE = 11_025

/** Analysis window, and how far it moves between frames. 46 ms a frame at [CHROMA_RATE]. */
const val CHROMA_WINDOW = 4_096
const val CHROMA_HOP = 512

/** Seconds per analysis frame — the resolution of every offset this file reports. */
const val CHROMA_HOP_SECONDS = CHROMA_HOP.toDouble() / CHROMA_RATE

/**
 * Where a voice and its first few harmonics live.
 *
 * Below this is bass guitar and kick drum, which follow the chords rather than the tune and would
 * drown the melody the chart is describing. Above it there is little left but cymbals.
 */
private const val LOW_HZ = 150.0
private const val HIGH_HZ = 2_000.0

/**
 * How far either way to look for the chart's true position, in seconds.
 *
 * Wide on purpose, and **not** to be narrowed to the range a correction is allowed to use. The
 * confidence number below is a peak measured against the *mean over everything searched*, so
 * shrinking the search changes the denominator and every threshold with it. The measured bands
 * — a genuine chart peaks at 1.5-2.4x, a mismatched one wanders around 1.1x — belong to this
 * span.
 */
private const val SEARCH_SECONDS = 45.0

/**
 * How far a peak may sit from zero and still count as aligned.
 *
 * A quarter of a second. It was 0.6 in the tool this grew from, which called Jimmy Cliff's "You
 * Can Get It If You Really Want" fine when it was half a second out — nine beats of a 257 BPM
 * song, and plainly wrong from the sofa. Two known-good songs land at -0.14 and -0.09 s and the
 * resolution here is one hop, 46 ms, so this is still several times the noise floor.
 */
const val ALIGNED_SECONDS = 0.25

/**
 * How strong a peak has to be before its position is believed.
 *
 * The whole test, and the reason this file can act rather than merely report. Measured across a
 * card of 123 songs: a chart written for its own recording peaks at **1.5-2.4x** the mean, and a
 * chart written for a *different* recording has no peak worth the name — around 1.1x, landing
 * somewhere arbitrary. Nothing sits in between by accident.
 *
 * Below this, an offset is meaningless and moving the chart by it would turn a song that is
 * merely wrong into a song that is wrong *and* edited.
 */
const val CONFIDENT_PEAK = 1.45

/**
 * The largest correction that will ever be applied, in seconds.
 *
 * Not tight: the worst genuine case measured on this card was The Monkees' "I'm A Believer" at
 * +3.44 s, which is a real `#GAP` error and worth fixing. But a forty-second "correction" is a
 * mismatch wearing a disguise, and a confident-looking peak that far out is far more likely to
 * be a coincidence of a repetitive song than a chart somebody wrote three quarters of a minute
 * early.
 */
private const val MAX_SHIFT_SECONDS = 8.0

/** Fewer pitched notes than this and there is not enough melody to be sure of anything. */
private const val MIN_NOTES = 20

/**
 * How far from zero a weak peak has to land before it is evidence of the wrong recording.
 *
 * Without this the check accuses songs it simply cannot read. A chart with little melodic signal
 * — quiet vocals, a dense mix, a lot of rap — scores low at *every* offset, so its best guess is
 * near enough noise; and if that guess happens to land a third of a second out, a rule that only
 * looked at confidence would announce "the notes do not match this recording" about a song that
 * is perfectly fine. Measured on this card: David Bowie's "Heroes" peaks at 1.20x at +0.28 s and
 * plays correctly.
 *
 * The distinction that does hold up: *no offset fits, and the best guess is not even near where
 * the chart claims to be* is real evidence of a different recording. Every genuine mismatch here
 * is at least a second out and most are tens of seconds. Anything closer than this is reported as
 * [SyncVerdict.Unscoreable] — "cannot tell", which is honest — rather than as an accusation.
 */
private const val MISMATCH_MIN_OFFSET = 1.0

/**
 * How many frames a window's own length puts between a moment and the frame that describes it.
 *
 * **This is a real correction, not a nicety, and the Python tool this grew from did not have it.**
 * Frame *f* is computed from the samples at `f * hop` up to `f * hop + window`, so the moment it
 * actually describes is its *centre*, half a window later. Mapping a note straight onto
 * `start / hop` therefore reads the audio a half-window late and the sweep quietly gives that
 * back as a negative offset.
 *
 * It is worth about 0.19 s here, and it is visible in the tool's own numbers: two songs known to
 * be in time landed at **-0.09 s and -0.14 s** rather than at zero, which was never a property of
 * those songs. Without this, "aligned" means "a fifth of a second early" and every correction
 * inherits the same error.
 */
private const val WINDOW_CENTRE_FRAMES = CHROMA_WINDOW / 2 / CHROMA_HOP

/** What the check found. */
sealed interface SyncVerdict {

    /** The chart is where it should be. Nothing to do. */
    data object Aligned : SyncVerdict

    /**
     * The chart describes this recording and sits [offsetSeconds] away from it.
     *
     * Add this to `#GAP` — in milliseconds — and the chart lands on the song.
     */
    data class Shifted(val offsetSeconds: Double, val confidence: Double) : SyncVerdict

    /**
     * There is no offset at which this chart fits this recording.
     *
     * Almost always a chart written against a *different* recording of the same song, which is
     * the failure `tools/check_song_sync.py` was built to name. No timing change can repair it;
     * the answer is a different chart or different audio.
     */
    data class Mismatch(val confidence: Double) : SyncVerdict

    /**
     * Not enough to judge by: too few pitched notes, too little audio, or a chart whose melody
     * is not carried by pitch at all — rap and spoken word both land here.
     *
     * Deliberately *not* a failure. Silence is the right answer when the instrument cannot see.
     */
    data object Unscoreable : SyncVerdict
}

/**
 * Whether a chart is in time with its own recording, and if not, by how much.
 *
 * ## What this is for
 *
 * A chart can be out of time for two entirely different reasons and they need opposite responses.
 * Either `#GAP` is wrong — the chart describes this recording and starts at the wrong moment,
 * which is one number and completely fixable — or the chart was written against a *different*
 * recording, which no offset repairs. Told apart by ear these look identical; told apart by
 * measurement they are not close.
 *
 * ## How, and why not by rhythm
 *
 * By pitch. Each analysis frame of the audio is reduced to how much energy sits in each of the
 * twelve pitch classes, the chart is turned into "which pitch class should be sounding at this
 * frame", and the two are correlated at every offset in a wide sweep. The sequence of pitch
 * classes a song moves through is close to unique.
 *
 * **Rhythm does not work for this**, which is worth knowing before anybody reaches for it again:
 * note onsets correlated against an energy envelope score nearly the same at every bar line of a
 * 4/4 song, and that approach confidently reported a +34 s offset for a song that was perfectly
 * in sync.
 *
 * ## Reading the result
 *
 * See [CONFIDENT_PEAK]. The confidence is a peak-to-mean ratio, and the gap between the two
 * measured bands is the whole test — which is also why anything built on this should be sanity
 * checked against a song known to be fine before a verdict about one that is not is believed.
 */
fun checkSync(song: UltraStarSong, profile: ChromaProfile): SyncVerdict {
    val notes = pitchedFrames(song)
    // Counted in *notes*, not in the frames they cover: one very long note is not a melody, and
    // a handful of notes cannot pin an offset however many frames they happen to fill.
    if (notes.count < MIN_NOTES || profile.frames < CHROMA_WINDOW / CHROMA_HOP) {
        return SyncVerdict.Unscoreable
    }

    val span = (SEARCH_SECONDS / CHROMA_HOP_SECONDS).roundToInt()
    var total = 0.0
    var scored = 0
    var bestScore = -1.0
    var bestShift = 0

    for (shift in -span..span) {
        var sum = 0.0
        var inside = 0
        for (i in notes.frames.indices) {
            val frame = notes.frames[i] + shift
            if (frame < 0 || frame >= profile.frames) continue
            sum += profile.energy(frame, notes.classes[i])
            inside++
        }
        // A shift that has moved most of the song off the end of the recording is not a candidate;
        // its average is taken over whatever few notes still overlap and means nothing.
        if (inside < notes.frames.size / 2) continue
        val score = sum / inside
        total += score
        scored++
        if (score > bestScore) {
            bestScore = score
            bestShift = shift
        }
    }

    if (scored == 0 || total <= 0.0) return SyncVerdict.Unscoreable
    val confidence = bestScore / (total / scored)
    val offset = bestShift * CHROMA_HOP_SECONDS

    val distance = kotlin.math.abs(offset)
    return when {
        // A confident peak is the only thing worth acting on, and only where a `#GAP` error
        // could plausibly have put it.
        confidence >= CONFIDENT_PEAK -> when {
            distance <= ALIGNED_SECONDS -> SyncVerdict.Aligned
            distance <= MAX_SHIFT_SECONDS -> SyncVerdict.Shifted(offset, confidence)
            else -> SyncVerdict.Mismatch(confidence)
        }
        // No confident peak anywhere. Whether that means the wrong recording or simply a song
        // this method cannot read is decided by where the best guess landed -- see
        // [MISMATCH_MIN_OFFSET].
        distance >= MISMATCH_MIN_OFFSET -> SyncVerdict.Mismatch(confidence)
        else -> SyncVerdict.Unscoreable
    }
}

/** Every pitched note, as the analysis frames it covers and the pitch class it asks for. */
private class NoteFrames(val frames: IntArray, val classes: IntArray, val count: Int)

/**
 * Turns a chart into "which pitch class should be sounding in this frame".
 *
 * **Freestyle notes are dropped.** They carry a pitch column and are unpitched by definition, so
 * including them adds noise to the one signal this depends on. Every other type is kept: a golden
 * note is an ordinary note worth more, and a rap note still has a written pitch that the singer
 * is at least near.
 */
private fun pitchedFrames(song: UltraStarSong): NoteFrames {
    val beats = BeatTimeConverter(song.metadata)
    val frames = ArrayList<Int>(512)
    val classes = ArrayList<Int>(512)
    var notes = 0

    for (part in song.voiceParts) {
        for (line in part.lines) {
            for (note in line.notes) {
                if (note.type == NoteType.FREESTYLE) continue
                val start = beats.beatToSeconds(note.startBeat)
                val end = beats.beatToSeconds(note.startBeat + note.durationBeats)
                // Rounded, not truncated. Truncating always moves a note *earlier*, by half a
                // frame on average, and half a frame is 23 ms of bias in the one number this
                // whole file exists to report. Then shifted back by half a window, so a frame
                // index means the moment that frame actually describes -- see
                // [WINDOW_CENTRE_FRAMES].
                val first = (start / CHROMA_HOP_SECONDS).roundToInt() - WINDOW_CENTRE_FRAMES
                val last = maxOf(
                    (end / CHROMA_HOP_SECONDS).roundToInt() - WINDOW_CENTRE_FRAMES,
                    first + 1,
                )
                val pitchClass = ((note.pitch % 12) + 12) % 12
                for (frame in first until last) {
                    frames.add(frame)
                    classes.add(pitchClass)
                }
                notes++
            }
        }
    }
    return NoteFrames(frames.toIntArray(), classes.toIntArray(), notes)
}

/**
 * How much energy sits in each of the twelve pitch classes, per analysis frame.
 *
 * Built a frame at a time by [ChromaBuilder] so that a whole song never has to be held as
 * samples: three and a half minutes at [CHROMA_RATE] is nine megabytes of float, where the
 * profile it reduces to is about two hundred kilobytes.
 */
class ChromaProfile(private val data: FloatArray, val frames: Int) {
    fun energy(frame: Int, pitchClass: Int): Float = data[frame * 12 + pitchClass]
}

/**
 * Reduces a stream of mono samples at [CHROMA_RATE] into a [ChromaProfile].
 *
 * Fed in whatever sized pieces a decoder produces; it keeps its own window and emits a frame
 * every [CHROMA_HOP] samples. Nothing here allocates per call in the steady state.
 */
class ChromaBuilder {

    private val fft = Fft(CHROMA_WINDOW)
    private val window = FloatArray(CHROMA_WINDOW)
    private val magnitudes = FloatArray(CHROMA_WINDOW / 2)
    private var filled = 0

    /** Which pitch class each usable bin belongs to, or -1 for bins outside the band. */
    private val binClass = IntArray(CHROMA_WINDOW / 2) { bin ->
        val hz = bin.toDouble() * CHROMA_RATE / CHROMA_WINDOW
        if (hz < LOW_HZ || hz > HIGH_HZ) {
            -1
        } else {
            // The MIDI number of that frequency, folded into an octave.
            val midi = (12.0 * ln(hz / 440.0) / ln(2.0) + 69.0).roundToInt()
            ((midi % 12) + 12) % 12
        }
    }

    private val out = ArrayList<FloatArray>(4_096)

    fun add(samples: FloatArray, count: Int = samples.size) {
        var read = 0
        while (read < count) {
            val room = CHROMA_WINDOW - filled
            val take = minOf(room, count - read)
            System.arraycopy(samples, read, window, filled, take)
            filled += take
            read += take
            if (filled == CHROMA_WINDOW) {
                emit()
                // Slide by one hop: keep the tail, and wait for the next hop's worth.
                System.arraycopy(window, CHROMA_HOP, window, 0, CHROMA_WINDOW - CHROMA_HOP)
                filled = CHROMA_WINDOW - CHROMA_HOP
            }
        }
    }

    private fun emit() {
        fft.magnitudes(window, magnitudes)
        val frame = FloatArray(12)
        var total = 0f
        for (bin in magnitudes.indices) {
            val pitchClass = binClass[bin]
            if (pitchClass < 0) continue
            frame[pitchClass] += magnitudes[bin]
            total += magnitudes[bin]
        }
        // Normalised per frame: *which* pitch class dominates is the signal; how loud the moment
        // was is not, or the sweep would simply find the loudest part of the song.
        if (total > 0f) for (i in frame.indices) frame[i] /= total
        out.add(frame)
    }

    fun build(): ChromaProfile {
        val data = FloatArray(out.size * 12)
        for (frame in out.indices) System.arraycopy(out[frame], 0, data, frame * 12, 12)
        return ChromaProfile(data, out.size)
    }
}

/**
 * Rewrites a chart's `#GAP` by [offsetSeconds], leaving everything else byte for byte.
 *
 * Header lines only, and line endings preserved — note lines carry meaning in their trailing
 * spaces, which is how every syllable in the library got hyphenated the one time a whole file was
 * trimmed. Returns null when there is no `#GAP` to move, which is a chart this cannot correct
 * rather than one to guess at.
 */
fun shiftGap(chart: String, offsetSeconds: Double): String? {
    val pattern = Regex("""^#GAP:[ \t]*(-?[\d]+(?:[.,]\d+)?)[ \t]*$""", RegexOption.MULTILINE)
    val match = pattern.find(chart) ?: return null
    val current = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
    val moved = (current + offsetSeconds * 1000.0).roundToInt()
    return chart.substring(0, match.range.first) + "#GAP:$moved" +
        chart.substring(match.range.last + 1)
}
