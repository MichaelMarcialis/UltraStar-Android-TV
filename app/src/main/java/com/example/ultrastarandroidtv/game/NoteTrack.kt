package com.example.ultrastarandroidtv.game

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import com.example.ultrastarandroidtv.score.NoteScore
import com.example.ultrastarandroidtv.score.isGolden
import com.example.ultrastarandroidtv.score.pitchClassDistance
import com.example.ultrastarandroidtv.score.ultraStarPitchToMidi
import com.example.ultrastarandroidtv.song.NoteType
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sin

/**
 * One singer on a track: what they have scored, what they are singing now, and their colour.
 *
 * @param currentMidi the singer's live pitch, read fresh every frame — this drives the arrow,
 *   and unlike [noteScores] it has something to say between notes and during rests.
 */
class Trace(
    val noteScores: List<NoteScore>,
    val color: Color,
    val currentMidi: () -> Float,
)

/**
 * The scrolling pitch bar: notes, the lyrics that belong to them, and each singer's arrow.
 *
 * Notes approach from the right and pass a fixed sing line, carrying their syllables with them.
 * Welding the lyrics to the same axis as the notes is the whole idea — a syllable sits under
 * its own note, and a rest is a real gap on screen, so the rhythm is read as spacing rather
 * than inferred from a static line of text.
 *
 * **The vertical scale is completely fixed.** It covers the song's whole range and never zooms
 * or pans. Earlier versions adapted it — first per song, then per passage — and both were worse
 * than the problem they solved: notes that stretch, squash and slide while you are trying to
 * read them are distracting in a way that no gain in resolution pays for. A fixed scale also
 * keeps the track shallow, which is what leaves the rest of the screen for the song video.
 *
 * A note is drawn **exactly as tall as the pitch window that scores it**, so "the arrow is
 * inside the bar" and "this beat counts" are the same statement rather than two things that
 * happen to correlate.
 *
 * @param now song time to draw at. Must be the time the singer *hears*, not the raw player
 *   position — see `SyncCalibration.heardSongTimeFor`. Read inside the draw pass, so updating
 *   it repaints without recomposing.
 * @param arrowNow song time the arrows' pitches actually describe, which is a little behind
 *   [now] — see `GameSession.arrowLagSeconds`. Read per frame rather than baked in, because it
 *   depends on the display lead, which is a live setting.
 * @param toleranceSemitones the scoring window either side of a note, which is also its height.
 */
@Composable
fun NoteTrack(
    geometry: TrackGeometry,
    traces: List<Trace>,
    now: () -> Double,
    arrowNow: () -> Double,
    toleranceSemitones: Float,
    accuracyColored: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val lyricStyle = remember {
        TextStyle(fontSize = GameTheme.lyricSize, fontWeight = FontWeight.SemiBold)
    }

    // Measured once per song rather than per frame: the syllables never change, only where they
    // sit. Laying out a screenful of text every frame is the obvious way to make this stutter.
    val syllables = remember(geometry, lyricStyle) {
        geometry.placements.map {
            measurer.measure(AnnotatedString(it.displayText), lyricStyle)
        }
    }

    // Per-singer arrow smoothing and one reusable Path, both kept out of the draw loop so a
    // frame allocates nothing.
    val motions = remember(geometry, traces.size) { List(traces.size) { ArrowMotion() } }
    val arrowPath = remember { Path() }
    val ribbon = remember { RibbonScratch() }

    BoxWithConstraints(modifier) {
        val widthPx = constraints.maxWidth.toFloat()

        val lyrics = remember(geometry, syllables, widthPx) {
            LyricLayout(
                placements = geometry.placements,
                widths = FloatArray(syllables.size) { syllables[it].size.width.toFloat() },
                pixelsPerSecond = (widthPx / geometry.windowSeconds).toFloat(),
                minGapPixels = with(density) { GameTheme.lyricMinGap.toPx() },
            )
        }

        // Two layers, and the split is the whole point. The track is a rounded panel and its
        // notes and lyrics must not spill out of it as they scroll in and out, so it is clipped.
        // The arrows must not be clipped: a singer outside the song's own range is exactly when
        // the arrow has something urgent to say, and a clipped arrow says it by disappearing.
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(GameTheme.trackCorner)),
        ) {
            drawTrack(
                geometry, traces, syllables, lyrics, ribbon,
                toleranceSemitones, now(), arrowNow(),
            )
        }

        Canvas(modifier = Modifier.fillMaxSize()) {
            drawArrowLayer(
                geometry, traces, motions, arrowPath,
                toleranceSemitones, accuracyColored, now(), arrowNow(),
            )
        }
    }
}

/**
 * Height available to the notes: everything the lyrics underneath them do not need.
 *
 * Shared by both layers rather than passed between them, because the arrows are drawn on their
 * own unclipped canvas and must land on exactly the same pitch scale as the notes they point at.
 */
private fun DrawScope.noteAreaHeight(): Float {
    // Capped as a share of the track as well as in absolute terms: a duet splits the screen in
    // two, and a lane sized for a full-height track would eat a third of each half.
    val lyricLane = minOf(
        GameTheme.lyricLaneHeight.toPx(),
        size.height * GameTheme.lyricLaneMaxShare,
    )
    return (size.height - lyricLane).coerceAtLeast(1f)
}

private fun DrawScope.drawTrack(
    geometry: TrackGeometry,
    traces: List<Trace>,
    syllables: List<TextLayoutResult>,
    lyrics: LyricLayout,
    ribbon: RibbonScratch,
    toleranceSemitones: Float,
    nowSeconds: Double,
    arrowNowSeconds: Double,
) {
    val width = size.width
    val lyricLane = size.height - noteAreaHeight()
    val noteArea = noteAreaHeight()

    val low = geometry.lowMidi.toFloat()
    val high = geometry.highMidi.toFloat()

    // A note is as tall as the window that scores it, with a floor so that a wide-ranging song
    // squeezed into a shallow track still leaves something you can aim at.
    val noteHeight = ((2f * toleranceSemitones / (high - low)) * noteArea)
        .coerceAtLeast(GameTheme.minNoteHeight.toPx())

    drawRect(GameTheme.trackBackground)

    // Behind everything else: the span between the arrows and the sing line, which is the song
    // currently being judged. Drawn first so the notes and lyrics keep their contrast.
    val arrowX = geometry.xFor(arrowNowSeconds, nowSeconds, width)
    val bandX = width * geometry.playheadFraction
    if (bandX > arrowX) {
        drawRect(
            color = GameTheme.judgedBand,
            topLeft = Offset(arrowX, 0f),
            size = Size(bandX - arrowX, size.height),
        )
    }

    drawOctaveLines(geometry, width, noteArea, low, high)

    val visible = geometry.visibleIndices(nowSeconds)
    val active = geometry.activeIndex(nowSeconds)

    if (!visible.isEmpty()) {
        drawNotes(
            geometry, ribbon, visible, active, nowSeconds,
            width, noteArea, noteHeight, low, high,
        )
        drawHits(
            geometry, traces, ribbon, visible, nowSeconds, arrowNowSeconds,
            width, noteArea, noteHeight, low, high,
        )
        drawLyrics(geometry, syllables, lyrics, visible, active, nowSeconds, width, lyricLane)
    }

    val singLineX = width * geometry.playheadFraction
    drawLine(
        color = GameTheme.playhead,
        start = Offset(singLineX, 0f),
        end = Offset(singLineX, size.height),
        strokeWidth = GameTheme.playheadWidth.toPx(),
    )

}

/**
 * The arrows and their sparks, on their own canvas so that nothing clips them.
 *
 * Drawn after the track and over it. The pitch scale is recomputed here rather than handed
 * across, which is safe because it is a pure function of the canvas size and the song's range —
 * both layers fill the same box, so both arrive at the same numbers.
 *
 * The arrow is judged against the note under *it* rather than the one under the sing line: the
 * note under the arrow is the one the reading it is showing was actually scored against.
 */
private fun DrawScope.drawArrowLayer(
    geometry: TrackGeometry,
    traces: List<Trace>,
    motions: List<ArrowMotion>,
    arrowPath: Path,
    toleranceSemitones: Float,
    accuracyColored: Boolean,
    nowSeconds: Double,
    arrowNowSeconds: Double,
) {
    val noteArea = noteAreaHeight()
    drawArrows(
        geometry, traces, motions,
        geometry.visibleIndices(nowSeconds), geometry.activeIndex(arrowNowSeconds),
        nowSeconds, arrowNowSeconds,
        size.width, noteArea,
        geometry.lowMidi.toFloat(), geometry.highMidi.toFloat(),
        toleranceSemitones, accuracyColored, arrowPath,
    )
}

/** A rule every octave, so the size of a leap has something to be judged against. */
private fun DrawScope.drawOctaveLines(
    geometry: TrackGeometry,
    width: Float,
    noteArea: Float,
    low: Float,
    high: Float,
) {
    var midi = ceil(low / 12f).toInt() * 12
    while (midi <= high) {
        val y = geometry.yFor(midi.toFloat(), noteArea, low, high)
        drawLine(GameTheme.octaveLine, Offset(0f, y), Offset(width, y), strokeWidth = 1f)
        midi += 12
    }
}

/**
 * Everything a held run needs to be drawn as one shape, kept for the life of the track.
 *
 * A run's geometry is worked out once as a **spine** — two points per note, at that note's own
 * pitch — and every path is derived from it: the outline the colours are painted inside, and a
 * lane within that outline for each singer's fill. Reused rather than rebuilt, so a frame
 * allocates nothing; the same reason the arrow keeps one [Path].
 */
internal class RibbonScratch {
    private var xs = FloatArray(32)
    private var ys = FloatArray(32)

    /** How many spine points the current run has: two per note, in singing order. */
    var pointCount = 0
        private set

    fun pointX(i: Int) = xs[i]

    fun pointY(i: Int) = ys[i]

    /** How far past the first and last spine points the run's own end caps reach. */
    private var capLeft = 0f
    private var capRight = 0f

    /** The run's vertical extent, so a fill can be drawn tall enough to cover every ramp. */
    var minY = 0f
        private set
    var maxY = 0f
        private set

    // Built on first use rather than in the constructor, so the spine arithmetic below — which
    // is the part that can quietly be wrong — can be exercised by a plain JVM test. A Compose
    // `Path` is an Android object and throws outside an instrumented run.
    val outline: Path by lazy(LazyThreadSafetyMode.NONE) { Path() }
    val lane: Path by lazy(LazyThreadSafetyMode.NONE) { Path() }

    /**
     * The merged x spans a singer has been credited across the current run, as from/to pairs.
     *
     * Held here rather than handed back through a callback for the reason everything else here
     * is: a lambda per lane per run per frame is an allocation on the drawing thread, and a
     * callback cannot be inlined out of a loop that has to merge as it goes.
     */
    private var spans = FloatArray(64)
    var spanCount = 0
        private set

    fun spanFrom(i: Int) = spans[2 * i]

    fun spanTo(i: Int) = spans[2 * i + 1]

    fun clearSpans() {
        spanCount = 0
    }

    /**
     * Adds a span, merging it into the one before it when they touch.
     *
     * This is what keeps a translucent fill honest. The spans a note's beats produce and the span
     * a ramp produces genuinely overlap — a ramp starts half a note height back inside the bar it
     * leaves — so drawing them one after another blends the fill with itself and leaves a bright
     * patch at every join, which is exactly where the eye already is. They arrive in x order, so
     * comparing against the last one merges them all.
     */
    fun addSpan(from: Float, to: Float) {
        if (spanCount > 0 && from <= spans[2 * spanCount - 1]) {
            spans[2 * spanCount - 1] = maxOf(spans[2 * spanCount - 1], to)
            return
        }
        if (spans.size < 2 * (spanCount + 1)) spans = spans.copyOf(spans.size * 2)
        spans[2 * spanCount] = from
        spans[2 * spanCount + 1] = to
        spanCount++
    }

    /**
     * Works out the spine of the run [first]..[last].
     *
     * The points sit at the *centres of the notes' end caps*, half a note height in from each
     * edge, which is where a ramp has to start and finish for the join to be seamless: at that x
     * the bar is still at full height, so a band of the same vertical thickness meets its top and
     * bottom edges exactly.
     */
    fun spine(
        geometry: TrackGeometry,
        first: Int,
        last: Int,
        nowSeconds: Double,
        width: Float,
        noteArea: Float,
        noteHeight: Float,
        low: Float,
        high: Float,
    ) {
        val points = 2 * (last - first + 1)
        if (xs.size < points) {
            xs = FloatArray(points)
            ys = FloatArray(points)
        }
        pointCount = 0

        val radius = noteHeight / 2f
        for (k in first..last) {
            val placed = geometry.placements[k]
            val left = geometry.xFor(placed.startSeconds, nowSeconds, width)
            // Trimmed at the end so two notes on the same pitch back to back read as two notes to
            // sing rather than one long one to hold.
            val right =
                geometry.xFor(placed.endSeconds - GameTheme.noteGapSeconds, nowSeconds, width)
            val y = geometry.yFor(placed.midi.toFloat(), noteArea, low, high)

            // A note drawn narrower than it is tall cannot carry a full round cap at both ends,
            // and `drawRoundRect` would have squashed its corner radius to fit. Squash it the
            // same way here, or a short note at the end of a run bulges past where its bar stops.
            // Not a corner case: a third of this library's notes are one or two beats long.
            val half = ((right - left) / 2f).coerceAtLeast(0f)
            val leftInset = if (k == first) minOf(radius, half) else radius
            val rightInset = if (k == last) minOf(radius, half) else radius

            add(left + leftInset, y)
            add(right - rightInset, y)

            if (k == first) capLeft = leftInset
            if (k == last) capRight = (right - xs[pointCount - 1]).coerceAtLeast(0f)
        }
    }

    /**
     * Adds a spine point, holding x non-decreasing.
     *
     * A note narrower than the corner radius would otherwise put its right point left of its left
     * one and turn the ribbon inside out.
     */
    private fun add(x: Float, y: Float) {
        xs[pointCount] = if (pointCount == 0) x else maxOf(x, xs[pointCount - 1])
        ys[pointCount] = y
        if (pointCount == 0) {
            minY = y
            maxY = y
        } else {
            minY = minOf(minY, y)
            maxY = maxOf(maxY, y)
        }
        pointCount++
    }

    /**
     * Writes the ribbon into [path] as one closed shape, [top] and [bottom] being offsets from
     * the spine.
     *
     * **The band's thickness is vertical, not perpendicular to the slope**, and that is the whole
     * of it. A stroked line is measured across its own direction, so a ramp drawn that way stands
     * proud of the flat bar it joins by however much it is tilted, and its round cap is tilted
     * with it — which is the notch this replaces. Offsetting the spine instead makes a ramp
     * exactly as tall as the notes at the two points where it meets them, so the pieces share an
     * edge rather than overlapping near one.
     *
     * [rounded] caps the two ends of the run the way a lone note's bar is capped. A lane inside
     * the run passes false and runs square out to the same extent, leaving the outline to trim it
     * back to the cap.
     */
    fun writePath(path: Path, top: Float, bottom: Float, rounded: Boolean) {
        path.reset()
        if (pointCount == 0) return
        val last = pointCount - 1

        if (rounded) {
            path.moveTo(xs[0], ys[0] + top)
        } else {
            path.moveTo(xs[0] - capLeft, ys[0] + top)
            path.lineTo(xs[0], ys[0] + top)
        }
        for (j in 1..last) path.lineTo(xs[j], ys[j] + top)

        if (rounded) {
            path.arcTo(
                Rect(xs[last] - capRight, ys[last] + top, xs[last] + capRight, ys[last] + bottom),
                -90f,
                180f,
                false,
            )
        } else {
            path.lineTo(xs[last] + capRight, ys[last] + top)
            path.lineTo(xs[last] + capRight, ys[last] + bottom)
        }
        for (j in last downTo 0) path.lineTo(xs[j], ys[j] + bottom)

        if (rounded) {
            path.arcTo(
                Rect(xs[0] - capLeft, ys[0] + top, xs[0] + capLeft, ys[0] + bottom),
                90f,
                180f,
                false,
            )
        } else {
            path.lineTo(xs[0] - capLeft, ys[0] + bottom)
        }
        path.close()
    }
}

/**
 * Walks the visible notes as **held runs** — a note on its own, or several joined by ramps.
 *
 * A run is one sustained sound and is therefore drawn as one shape. The runs hanging off either
 * end of the window are completed rather than cut, so a ramp arriving from the left is not
 * missing until the note it comes from has scrolled in.
 */
internal inline fun forEachHeldRun(
    geometry: TrackGeometry,
    visible: IntRange,
    action: (first: Int, last: Int) -> Unit,
) {
    val placements = geometry.placements
    var first = visible.first
    while (first > 0 && placements[first].heldFromPrevious) first--
    while (first <= visible.last) {
        var last = first
        while (last + 1 < placements.size && placements[last + 1].heldFromPrevious) last++
        action(first, last)
        first = last + 1
    }
}

/** The colour a bar is drawn in, before any singer's fill goes over it. */
private fun noteColor(placed: PlacedNote, isActive: Boolean): Color = when {
    placed.note.type == NoteType.FREESTYLE -> GameTheme.noteFreestyle
    placed.note.type.isGolden && isActive -> GameTheme.noteActiveGolden
    placed.note.type.isGolden -> GameTheme.noteGolden
    isActive -> GameTheme.noteActive
    else -> GameTheme.noteIdle
}

private fun DrawScope.drawNotes(
    geometry: TrackGeometry,
    scratch: RibbonScratch,
    visible: IntRange,
    active: Int?,
    nowSeconds: Double,
    width: Float,
    noteArea: Float,
    noteHeight: Float,
    low: Float,
    high: Float,
) {
    val radius = noteHeight / 2f

    forEachHeldRun(geometry, visible) { first, last ->
        // The overwhelmingly common case is a note nobody holds through, and it costs nothing
        // beyond a rounded rectangle. Only a run with a ramp in it pays for the ribbon.
        if (first == last) {
            val placed = geometry.placements[first]
            val left = geometry.xFor(placed.startSeconds, nowSeconds, width)
            val right =
                geometry.xFor(placed.endSeconds - GameTheme.noteGapSeconds, nowSeconds, width)
            val top = geometry.yFor(placed.midi.toFloat(), noteArea, low, high) - radius
            drawRoundRect(
                color = noteColor(placed, first == active),
                topLeft = Offset(left, top),
                size = Size((right - left).coerceAtLeast(3f), noteHeight),
                cornerRadius = CornerRadius(radius),
            )
            return@forEachHeldRun
        }

        // Karaoke Revolution's angled connector, and the UltraStar format carries the same
        // information: a syllable of `~` means the vowel is held while the pitch moves. Two
        // thousand of them on this card. Without it, two bars a tone apart look like two attacks
        // and get sung as two; with it, the eye reads one long note that bends.
        //
        // **A run is one shape, not three stacked ones**, and getting there took three goes. A
        // thin dim line read as two notes with a wire between them. A stroked bar of the same
        // height read as one note and left a notch at every join, because a stroke is measured
        // across its own slope and stands taller than the flat bar it meets. Painting the colours
        // *inside* one outline fixes the notch and the second half of the same problem: pieces
        // that overlap blend twice wherever they meet, which a translucent fill shows as a bright
        // patch and an opaque one shows as a fringe around each cap.
        scratch.spine(geometry, first, last, nowSeconds, width, noteArea, noteHeight, low, high)
        scratch.writePath(scratch.outline, -radius, radius, rounded = true)
        val bandTop = scratch.minY - noteHeight
        val bandHeight = scratch.maxY - scratch.minY + 2f * noteHeight

        clipPath(scratch.outline) {
            // The ramps first and the bars over their own ends of one, so a ramp carries a colour
            // of its own only where neither bar reaches.
            for (k in first until last) {
                val before = geometry.placements[k]
                val after = geometry.placements[k + 1]
                // Golden only when *both* ends are, so a golden run stays golden the whole way
                // through and a ramp into gold does not put the colour change anywhere but the
                // note that earns it.
                val golden = before.note.type.isGolden && after.note.type.isGolden
                val isActive = k == active || k + 1 == active
                val from =
                    geometry.xFor(before.endSeconds - GameTheme.noteGapSeconds, nowSeconds, width) -
                        radius
                val to = geometry.xFor(after.startSeconds, nowSeconds, width) + radius
                drawRect(
                    color = when {
                        golden && isActive -> GameTheme.noteActiveGolden
                        golden -> GameTheme.noteGolden
                        isActive -> GameTheme.noteActive
                        else -> GameTheme.noteIdle
                    },
                    topLeft = Offset(from, bandTop),
                    size = Size((to - from).coerceAtLeast(1f), bandHeight),
                )
            }

            for (k in first..last) {
                val placed = geometry.placements[k]
                // A pixel proud at each end, so the outline decides where the shape stops rather
                // than two edges landing on the same coordinate and softening each other.
                val from = geometry.xFor(placed.startSeconds, nowSeconds, width) - 1f
                val to =
                    geometry.xFor(placed.endSeconds - GameTheme.noteGapSeconds, nowSeconds, width) +
                        1f
                drawRect(
                    color = noteColor(placed, k == active),
                    topLeft = Offset(from, bandTop),
                    size = Size((to - from).coerceAtLeast(1f), bandHeight),
                )
            }
        }
    }
}

/**
 * Collects the x spans of everything [trace] has been credited across the run [first]..[last].
 *
 * They land in this scratch's own span list, already merged — see [RibbonScratch.addSpan] for why
 * merging them is the difference between a solid fill and a row of bright patches.
 */
private fun RibbonScratch.hitSpans(
    geometry: TrackGeometry,
    trace: Trace,
    first: Int,
    last: Int,
    nowSeconds: Double,
    arrowNowSeconds: Double,
    width: Float,
    radius: Float,
) {
    clearSpans()
    for (k in first..last) {
        val placed = geometry.placements[k]
        val score = trace.noteScores.getOrNull(k) ?: continue
        val beats = placed.beatMidSeconds.size
        if (beats == 0) continue
        val beatSeconds = (placed.endSeconds - placed.startSeconds) / beats

        // A beat is judged at its midpoint but drawn as a whole bar, so the moment it is
        // credited its trailing half is still ahead of the arrow that earned it — the note
        // appears to light up before the singer gets there. Hold each beat until it has passed
        // the arrow entirely. Costs half a beat of delay and buys the guarantee that nothing is
        // ever seen to be paid for before the arrow reaches it.
        val passed = ((arrowNowSeconds - placed.startSeconds) / beatSeconds)
            .toInt()
            .coerceIn(0, beats)

        // Runs of consecutive hits rather than one span per beat, so a note sung all the way
        // through is a single clean bar with no seams in it.
        var beat = 0
        while (beat < passed) {
            if (!score.wasHit(beat)) {
                beat++
                continue
            }
            var end = beat
            while (end + 1 < passed && score.wasHit(end + 1)) end++
            addSpan(
                geometry.xFor(placed.startSeconds + beat * beatSeconds, nowSeconds, width),
                geometry.xFor(
                    placed.startSeconds + (end + 1) * beatSeconds - GameTheme.noteGapSeconds,
                    nowSeconds,
                    width,
                ),
            )
            beat = end + 1
        }

        // The singer's colour carries across the ramp too, so a held note is one unbroken stretch
        // of their colour rather than two bars with a gap between them. Nothing is *scored* on a
        // ramp — the gap belongs to no note — but a sustained vowel is one thing the singer did,
        // and drawing it as two says otherwise. Filled only once the note it continues has been
        // credited and has passed the arrow entirely, the same rule the beats above follow.
        if (k < last && passed >= beats && score.wasHit(beats - 1)) {
            addSpan(
                geometry.xFor(placed.endSeconds - GameTheme.noteGapSeconds, nowSeconds, width) -
                    radius,
                geometry.xFor(geometry.placements[k + 1].startSeconds, nowSeconds, width) + radius,
            )
        }
    }
}

/**
 * Fills the beats each singer actually landed.
 *
 * With two singers on one set of notes the fill is split into a lane each, stacked inside the
 * note. Blending two translucent colours instead would produce a third colour that means
 * neither of them; a lane each stays legible when both hit, when one does, and when neither.
 */
private fun DrawScope.drawHits(
    geometry: TrackGeometry,
    traces: List<Trace>,
    scratch: RibbonScratch,
    visible: IntRange,
    nowSeconds: Double,
    arrowNowSeconds: Double,
    width: Float,
    noteArea: Float,
    noteHeight: Float,
    low: Float,
    high: Float,
) {
    if (traces.isEmpty()) return
    val laneHeight = noteHeight / traces.size
    val radius = noteHeight / 2f

    forEachHeldRun(geometry, visible) { first, last ->
        if (first == last) {
            val placed = geometry.placements[first]
            // Nothing the arrow has not reached can have been paid for, and the playhead sits at
            // 30% of the width — so most of what is on screen is skipped here without building
            // anything. That is what keeps the clip below off the common path.
            if (arrowNowSeconds <= placed.startSeconds) return@forEachHeldRun

            val top = geometry.yFor(placed.midi.toFloat(), noteArea, low, high) - radius

            // Painted inside the bar's own outline, so a fill that reaches the end of a note is
            // cut to the rounded cap instead of stopping square a pixel past it. A lone note is a
            // run of one, so the same spine builds it — and its outline is a convex pill, which
            // is a far cheaper clip than a run's zigzag.
            scratch.spine(geometry, first, last, nowSeconds, width, noteArea, noteHeight, low, high)
            scratch.writePath(scratch.outline, -radius, radius, rounded = true)
            clipPath(scratch.outline) {
                traces.forEachIndexed { lane, trace ->
                    scratch.hitSpans(
                        geometry, trace, first, last, nowSeconds, arrowNowSeconds, width, radius,
                    )
                    val fill = GameTheme.hitFill(trace.color)
                    for (i in 0 until scratch.spanCount) {
                        val from = scratch.spanFrom(i)
                        drawRect(
                            color = fill,
                            topLeft = Offset(from, top + lane * laneHeight),
                            size = Size((scratch.spanTo(i) - from).coerceAtLeast(3f), laneHeight),
                        )
                    }
                }
            }
            return@forEachHeldRun
        }

        scratch.spine(geometry, first, last, nowSeconds, width, noteArea, noteHeight, low, high)
        scratch.writePath(scratch.outline, -radius, radius, rounded = true)
        val bandTop = scratch.minY - noteHeight
        val bandHeight = scratch.maxY - scratch.minY + 2f * noteHeight

        // Painted inside the run's own outline and then inside the singer's lane within it, so a
        // plain upright rectangle comes out following the ramp — and stops exactly where the bars
        // do. Stroking the ramp instead is what used to leave the fill standing proud of them.
        clipPath(scratch.outline) {
            traces.forEachIndexed { lane, trace ->
                val fill = GameTheme.hitFill(trace.color)
                scratch.writePath(
                    scratch.lane,
                    -radius + lane * laneHeight,
                    -radius + (lane + 1) * laneHeight,
                    rounded = false,
                )
                scratch.hitSpans(
                    geometry, trace, first, last, nowSeconds, arrowNowSeconds, width, radius,
                )
                clipPath(scratch.lane) {
                    for (i in 0 until scratch.spanCount) {
                        val from = scratch.spanFrom(i)
                        drawRect(
                            color = fill,
                            topLeft = Offset(from, bandTop),
                            size = Size((scratch.spanTo(i) - from).coerceAtLeast(3f), bandHeight),
                        )
                    }
                }
            }
        }
    }
}

/**
 * One arrow per singer, at the sing line, showing where their voice is this instant.
 *
 * It sits left of the line and points at it, so pitch is read as a vertical gap between arrow
 * and note: level means right, and which way to move is immediately obvious. Deliberately
 * narrow, because in two-player mode the two arrows share the line and spend a lot of the song
 * near each other.
 *
 * **How far left is not a clearance, it is a measurement.** The pitch an arrow shows was taken
 * from audio that is already old, so drawing it at the line points it at a note the singer has
 * moved past — and during a fast passage that is a different note, which quietly breaks the one
 * thing this drawing promises: that an arrow inside a bar means that beat was paid for. The
 * arrow is therefore drawn at the moment in the song its reading actually came from.
 *
 * When a singer is inside the note's scoring window, sparks fire where the arrow meets the bar.
 * They are struck from the *same* comparison the scorer uses, so they cannot disagree with the
 * points being awarded.
 */
private fun DrawScope.drawArrows(
    geometry: TrackGeometry,
    traces: List<Trace>,
    motions: List<ArrowMotion>,
    visible: IntRange,
    active: Int?,
    nowSeconds: Double,
    arrowNowSeconds: Double,
    width: Float,
    noteArea: Float,
    low: Float,
    high: Float,
    toleranceSemitones: Float,
    accuracyColored: Boolean,
    path: Path,
) {
    // Fold against whatever the singer is nearest to being asked for: the note under the arrow
    // if there is one, otherwise the closest one on screen, so the arrow keeps its bearings
    // through rests instead of jumping an octave the moment a note ends.
    val reference = active ?: nearestIndex(geometry, visible, arrowNowSeconds)
    val referenceMidi = reference?.let { geometry.placements[it].midi }

    // Not at the sing line, but at the moment in the song the pitch it is showing came from —
    // which is a little to the left, because the voice had to be captured and analysed to get
    // here. Drawn at the line it would point at a note that has already gone past.
    val tipX = geometry.xFor(arrowNowSeconds, nowSeconds, width) - GameTheme.arrowGap.toPx()
    val arrowWidth = GameTheme.arrowWidth.toPx()
    val halfHeight = GameTheme.arrowHeight.toPx() / 2f

    traces.forEachIndexed { index, trace ->
        val motion = motions.getOrNull(index) ?: return@forEachIndexed
        val raw = trace.currentMidi()
        // Folded with hysteresis, against where this arrow already is. Both the reference note
        // and the voice cross the tritone boundary constantly, and each crossing flips the fold
        // by a whole octave — a jump in the *target*, which no amount of easing can smooth,
        // only draw more slowly.
        val folded = if (raw.isNaN() || referenceMidi == null) {
            raw
        } else {
            foldToOctaveNear(raw, referenceMidi, motion.shownMidi)
        }

        val midi = motion.update(folded, nowSeconds)
        if (!motion.isVisible) return@forEachIndexed

        val y = geometry.yFor(midi, noteArea, low, high)
        val alpha = motion.alpha

        // Only the note actually under the line can be being sung, and only the untouched raw
        // pitch can be compared with it — the same call the scorer makes.
        val onNote = active != null && !raw.isNaN() && pitchClassDistance(
            raw,
            ultraStarPitchToMidi(geometry.placements[active].note.pitch).toFloat(),
        ) <= toleranceSemitones

        if (onNote) {
            drawSparks(tipX, y, nowSeconds, index, alpha)
        }

        // One solid shape and nothing behind it. There was a soft halo here to help the arrow
        // stand out against the notes; two arrows of different weights read as two things, and
        // the sparks now do the standing-out.
        //
        // Tilted about the tip, so the point stays where the pitch is and only the tail swings.
        // Sing under the note and it angles up, over it and it angles down — the same
        // information as the vertical gap, in a form that can be read without first finding
        // which bar it belongs to. Level means right.
        buildArrowHead(path, tipX, y, arrowWidth, halfHeight)

        val targetMidi = active?.let { geometry.placements[it].midi }
        val tilt = motion.tiltTowards(tiltDegrees(midi, targetMidi))

        // On your own the arrow is free to say how well it is going, because nobody else's arrow
        // needs telling apart from it. With two singers the colour is the only thing that does.
        //
        // **A solo arrow is never the singer's own colour.** It falls back to the *start* of the
        // accuracy scale rather than to `trace.color`, which is what the notes, the name and the
        // score carry. Sharing them makes the arrow purple between notes and green on one, which
        // reads as two ideas fighting rather than as one scale: green when it is right, red when
        // it is not, and nothing else.
        val color = when {
            !accuracyColored -> trace.color
            targetMidi == null || midi.isNaN() -> GameTheme.arrowOnPitch
            else -> GameTheme.arrowAccuracyColor(midi - targetMidi, toleranceSemitones)
        }

        rotate(degrees = tilt, pivot = Offset(tipX, y)) {
            drawPath(path, color.copy(alpha = alpha))
        }
    }
}

/**
 * Sparks thrown off where the arrow meets the note bar.
 *
 * They trail **leftwards**, the direction the notes are travelling, which is what makes the
 * arrow read as scraping along the bar rather than as a firework going off beside it. Each cools
 * from white-hot through gold to orange as it flies, and the vertical scatter widens with
 * distance the way struck sparks actually spread.
 *
 * **Every spark differs from every other in three ways** — how fast it flies, how far it gets
 * and which way it fans — all fixed for the spark's whole life so nothing wanders between
 * frames. A shower where each particle takes the same trajectory at the same speed reads as a
 * rotating pattern rather than as sparks; the variation is what makes it look struck.
 *
 * Struck procedurally from the clock rather than simulated, so there is no particle state to
 * keep, nothing to allocate per frame, and nothing that can be left behind when a note ends.
 * [seed] separates the two singers so their sparks do not fire in lockstep.
 */
private fun DrawScope.drawSparks(
    x: Float,
    y: Float,
    nowSeconds: Double,
    seed: Int,
    alpha: Float,
) {
    val reach = GameTheme.sparkReach.toPx()
    val spread = GameTheme.sparkSpread.toPx()
    val dotRadius = GameTheme.sparkRadius.toPx()

    for (i in 0 until GameTheme.sparkCount) {
        // Each spark runs its own 0..1 life at its own rate, so the stream is continuous rather
        // than pulsing all together, and short-lived sparks sit among long-lived ones.
        val speed = GameTheme.sparkSpeed * (0.65f + 0.7f * hashFor(i, seed, 0f))
        val phase = ((nowSeconds * speed + i * 0.61 + seed * 0.5) % 1.0).toFloat()

        val scatter = hashFor(i, seed, 5.3f) * 2f - 1f
        val length = 0.35f + 0.65f * hashFor(i, seed, 11.7f)

        // Mostly linear rather than the square it used to be: squaring keeps the strike point
        // bright but kills the tail within a few pixels, which is the whole thing being asked
        // for here. A little curve is kept so the strike still reads as the hottest point.
        val remaining = 1f - phase
        val fade = remaining * 0.7f + remaining * remaining * 0.3f

        drawCircle(
            color = GameTheme.sparkColor(phase).copy(alpha = fade * alpha),
            radius = dotRadius * (1f - phase * 0.5f),
            center = Offset(x - phase * reach * length, y + scatter * phase * spread),
        )
    }
}

/**
 * A stable pseudo-random 0..1 for spark [i] of singer [seed]. No allocation, no state.
 *
 * [salt] draws an independent value from the same spark, so speed, spread and length can vary
 * without correlating with each other — which they would if one number drove all three.
 */
private fun hashFor(i: Int, seed: Int, salt: Float): Float {
    val n = sin(i * 12.9898f + seed * 78.233f + salt) * 43758.547f
    return n - floor(n)
}

/** Rebuilds [path] in place as a right-pointing head. Reused every frame rather than allocated. */
private fun buildArrowHead(path: Path, tipX: Float, y: Float, width: Float, halfHeight: Float) {
    path.reset()
    path.moveTo(tipX, y)
    path.lineTo(tipX - width, y - halfHeight)
    path.lineTo(tipX - width * 0.62f, y)
    path.lineTo(tipX - width, y + halfHeight)
    path.close()
}

/**
 * How far to tilt the arrow, in degrees, given where the singer is and where they should be.
 *
 * Positive turns the arrow clockwise on screen, which points it **down** — the answer to singing
 * sharp. Flat gets a negative angle and points up. Proportional to the error rather than a
 * three-way flat/right/sharp indicator, because the useful question during a note is not whether
 * you are off but whether you are getting closer.
 *
 * Level during a rest: with no note under the arrow there is nothing to be off *from*, and a
 * tilt held over from the last note would be advice about a note that has gone.
 */
internal fun tiltDegrees(sungMidi: Float, targetMidi: Int?): Float {
    if (sungMidi.isNaN() || targetMidi == null) return 0f
    val error = sungMidi - targetMidi.toFloat()
    val full = GameTheme.arrowFullTiltSemitones
    val max = GameTheme.arrowMaxTiltDegrees
    return ((error / full) * max).coerceIn(-max, max)
}

/** The visible note closest in time to [nowSeconds], for keeping the arrow's octave sensible. */
private fun nearestIndex(geometry: TrackGeometry, visible: IntRange, nowSeconds: Double): Int? {
    var best: Int? = null
    var bestDistance = Double.MAX_VALUE
    for (i in visible) {
        val placed = geometry.placements[i]
        val distance = when {
            nowSeconds < placed.startSeconds -> placed.startSeconds - nowSeconds
            nowSeconds > placed.endSeconds -> nowSeconds - placed.endSeconds
            else -> 0.0
        }
        if (distance < bestDistance) {
            bestDistance = distance
            best = i
        }
    }
    return best
}

private fun DrawScope.drawLyrics(
    geometry: TrackGeometry,
    syllables: List<TextLayoutResult>,
    lyrics: LyricLayout,
    visible: IntRange,
    active: Int?,
    nowSeconds: Double,
    width: Float,
    lyricLane: Float,
) {
    val top = size.height - lyricLane

    for (i in visible) {
        val layout = syllables.getOrNull(i) ?: continue
        if (layout.size.width == 0) continue

        // Left-aligned on the note's start, because that is the moment the syllable is sung,
        // plus whatever nudge it took to stop it colliding with the syllable before it.
        val x = geometry.xFor(geometry.placements[i].startSeconds, nowSeconds, width) +
            lyrics.offsets[i]
        if (x > width || x + layout.size.width < 0f) continue

        drawText(
            textLayoutResult = layout,
            color = if (i == active) GameTheme.lyricActive else GameTheme.lyricIdle,
            topLeft = Offset(x, top + (lyricLane - layout.size.height) / 2f),
        )
    }
}
