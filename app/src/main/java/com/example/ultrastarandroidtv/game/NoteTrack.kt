package com.example.ultrastarandroidtv.game

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
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
import kotlin.math.cos
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
 * @param toleranceSemitones the scoring window either side of a note, which is also its height.
 */
@Composable
fun NoteTrack(
    geometry: TrackGeometry,
    traces: List<Trace>,
    now: () -> Double,
    toleranceSemitones: Float,
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
            measurer.measure(AnnotatedString(it.note.text.trimEnd()), lyricStyle)
        }
    }

    // Per-singer arrow smoothing and one reusable Path, both kept out of the draw loop so a
    // frame allocates nothing.
    val motions = remember(geometry, traces.size) { List(traces.size) { ArrowMotion() } }
    val arrowPath = remember { Path() }

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

        Canvas(modifier = Modifier.fillMaxSize().clipToBounds()) {
            drawTrack(
                geometry, traces, motions, syllables, lyrics, arrowPath,
                toleranceSemitones, now(),
            )
        }
    }
}

private fun DrawScope.drawTrack(
    geometry: TrackGeometry,
    traces: List<Trace>,
    motions: List<ArrowMotion>,
    syllables: List<TextLayoutResult>,
    lyrics: LyricLayout,
    arrowPath: Path,
    toleranceSemitones: Float,
    nowSeconds: Double,
) {
    val width = size.width
    // Capped as a share of the track as well as in absolute terms: a duet splits the screen in
    // two, and a lane sized for a full-height track would eat a third of each half.
    val lyricLane = minOf(
        GameTheme.lyricLaneHeight.toPx(),
        size.height * GameTheme.lyricLaneMaxShare,
    )
    val noteArea = (size.height - lyricLane).coerceAtLeast(1f)

    val low = geometry.lowMidi.toFloat()
    val high = geometry.highMidi.toFloat()

    // A note is as tall as the window that scores it, with a floor so that a wide-ranging song
    // squeezed into a shallow track still leaves something you can aim at.
    val noteHeight = ((2f * toleranceSemitones / (high - low)) * noteArea)
        .coerceAtLeast(GameTheme.minNoteHeight.toPx())

    drawRect(GameTheme.trackBackground)
    drawOctaveLines(geometry, width, noteArea, low, high)

    val visible = geometry.visibleIndices(nowSeconds)
    val active = geometry.activeIndex(nowSeconds)

    if (!visible.isEmpty()) {
        drawNotes(geometry, visible, active, nowSeconds, width, noteArea, noteHeight, low, high)
        drawHits(geometry, traces, visible, nowSeconds, width, noteArea, noteHeight, low, high)
        drawLyrics(geometry, syllables, lyrics, visible, active, nowSeconds, width, lyricLane)
    }

    val singLineX = width * geometry.playheadFraction
    drawLine(
        color = GameTheme.playhead,
        start = Offset(singLineX, 0f),
        end = Offset(singLineX, size.height),
        strokeWidth = GameTheme.playheadWidth.toPx(),
    )

    // Last, so the arrows and their sparks sit above everything they are pointing at.
    drawArrows(
        geometry, traces, motions, visible, active, nowSeconds,
        width, noteArea, low, high, toleranceSemitones, arrowPath,
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

private fun DrawScope.drawNotes(
    geometry: TrackGeometry,
    visible: IntRange,
    active: Int?,
    nowSeconds: Double,
    width: Float,
    noteArea: Float,
    noteHeight: Float,
    low: Float,
    high: Float,
) {
    for (i in visible) {
        val placed = geometry.placements[i]
        val left = geometry.xFor(placed.startSeconds, nowSeconds, width)
        // Trimmed at the end so two notes on the same pitch back to back read as two notes to
        // sing rather than one long one to hold.
        val right = geometry.xFor(placed.endSeconds - GameTheme.noteGapSeconds, nowSeconds, width)
        val top = geometry.yFor(placed.midi.toFloat(), noteArea, low, high) - noteHeight / 2f
        val isActive = i == active

        val color = when {
            placed.note.type == NoteType.FREESTYLE -> GameTheme.noteFreestyle
            placed.note.type.isGolden && isActive -> GameTheme.noteActiveGolden
            placed.note.type.isGolden -> GameTheme.noteGolden
            isActive -> GameTheme.noteActive
            else -> GameTheme.noteIdle
        }

        drawRoundRect(
            color = color,
            topLeft = Offset(left, top),
            size = Size((right - left).coerceAtLeast(3f), noteHeight),
            cornerRadius = CornerRadius(noteHeight / 2f),
        )
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
    visible: IntRange,
    nowSeconds: Double,
    width: Float,
    noteArea: Float,
    noteHeight: Float,
    low: Float,
    high: Float,
) {
    if (traces.isEmpty()) return
    val laneHeight = noteHeight / traces.size

    traces.forEachIndexed { lane, trace ->
        val fill = GameTheme.hitFill(trace.color)
        for (i in visible) {
            val placed = geometry.placements[i]
            val score = trace.noteScores.getOrNull(i) ?: continue
            val beats = placed.beatMidSeconds.size
            if (beats == 0) continue

            val beatSeconds = (placed.endSeconds - placed.startSeconds) / beats
            val top = geometry.yFor(placed.midi.toFloat(), noteArea, low, high) -
                noteHeight / 2f + lane * laneHeight

            // Drawn as runs of consecutive hits rather than one rectangle per beat, so a note
            // sung all the way through is a single clean bar with no seams in it.
            var beat = 0
            while (beat < beats) {
                if (!score.wasHit(beat)) {
                    beat++
                    continue
                }
                var end = beat
                while (end + 1 < beats && score.wasHit(end + 1)) end++

                val from = geometry.xFor(placed.startSeconds + beat * beatSeconds, nowSeconds, width)
                val to = geometry.xFor(
                    placed.startSeconds + (end + 1) * beatSeconds - GameTheme.noteGapSeconds,
                    nowSeconds,
                    width,
                )
                drawRect(
                    color = fill,
                    topLeft = Offset(from, top),
                    size = Size((to - from).coerceAtLeast(3f), laneHeight),
                )
                beat = end + 1
            }
        }
    }
}

/**
 * One arrow per singer, at the sing line, showing where their voice is this instant.
 *
 * It sits just left of the line and points at it, so pitch is read as a vertical gap between
 * arrow and note: level means right, and which way to move is immediately obvious. Deliberately
 * narrow, because in two-player mode the two arrows share the line and spend a lot of the song
 * near each other.
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
    width: Float,
    noteArea: Float,
    low: Float,
    high: Float,
    toleranceSemitones: Float,
    path: Path,
) {
    // Fold against whatever the singer is nearest to being asked for: the note under the line
    // if there is one, otherwise the closest one on screen, so the arrow keeps its bearings
    // through rests instead of jumping an octave the moment a note ends.
    val reference = active ?: nearestIndex(geometry, visible, nowSeconds)
    val referenceMidi = reference?.let { geometry.placements[it].midi }

    val tipX = width * geometry.playheadFraction - GameTheme.arrowGap.toPx()
    val arrowWidth = GameTheme.arrowWidth.toPx()
    val halfHeight = GameTheme.arrowHeight.toPx() / 2f

    traces.forEachIndexed { index, trace ->
        val motion = motions.getOrNull(index) ?: return@forEachIndexed
        val raw = trace.currentMidi()
        val folded =
            if (raw.isNaN() || referenceMidi == null) raw else foldToOctaveNear(raw, referenceMidi)

        val midi = motion.update(folded, nowSeconds)
        if (midi.isNaN()) return@forEachIndexed

        val y = geometry.yFor(midi, noteArea, low, high)

        // Only the note actually under the line can be being sung, and only the untouched raw
        // pitch can be compared with it — the same call the scorer makes.
        val onNote = active != null && !raw.isNaN() && pitchClassDistance(
            raw,
            ultraStarPitchToMidi(geometry.placements[active].note.pitch).toFloat(),
        ) <= toleranceSemitones

        if (onNote) {
            drawSparks(tipX, y, trace.color, nowSeconds, index)
        }

        buildArrowHead(path, tipX, y, arrowWidth * GameTheme.arrowGlowScale, halfHeight * GameTheme.arrowGlowScale)
        drawPath(path, GameTheme.arrowGlow(trace.color))

        buildArrowHead(path, tipX, y, arrowWidth, halfHeight)
        drawPath(path, trace.color)
    }
}

/**
 * A little burst where the arrow meets the note.
 *
 * Struck procedurally from the clock rather than simulated, so there is no particle state to
 * keep, nothing to allocate per frame, and nothing that can be left behind when a note ends.
 * [seed] separates the two singers so their bursts do not fire in lockstep.
 */
private fun DrawScope.drawSparks(
    x: Float,
    y: Float,
    color: Color,
    nowSeconds: Double,
    seed: Int,
) {
    val reach = GameTheme.sparkReach.toPx()
    val dotRadius = GameTheme.sparkRadius.toPx()

    for (i in 0 until GameTheme.sparkCount) {
        // Each spark runs its own 0..1 life on a staggered phase, so the burst is continuous
        // rather than pulsing all together.
        val phase = ((nowSeconds * GameTheme.sparkSpeed + i * 0.37 + seed * 0.5) % 1.0).toFloat()
        val angle = (i * 2.399f) + seed * 1.1f // Golden angle: an even spray without a pattern.
        val distance = phase * reach

        drawCircle(
            color = GameTheme.sparkColor(color).copy(alpha = (1f - phase) * 0.95f),
            radius = dotRadius * (1f - phase * 0.5f),
            center = Offset(x + cos(angle) * distance, y + sin(angle) * distance),
        )
    }
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
