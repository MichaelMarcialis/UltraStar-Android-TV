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
import androidx.compose.ui.graphics.StrokeCap
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
import com.example.ultrastarandroidtv.song.NoteType
import kotlin.math.ceil

/** One singer's line on a track: what they scored, and the colour they are drawn in. */
class Trace(
    val noteScores: List<NoteScore>,
    val color: Color,
)

/**
 * The scrolling pitch bar: notes, the lyrics that belong to them, and each singer's trace.
 *
 * Notes approach from the right and pass a fixed playhead, carrying their syllables with them.
 * Welding the lyrics to the same axis as the notes is the whole idea — a syllable sits under
 * its own note, and a rest is a real gap on screen, so the rhythm is read as spacing rather
 * than inferred from a static line of text.
 *
 * The song's data is drawn from one timestamp with no state of its own, so a late frame simply
 * draws the song where it is now rather than falling behind. The singer's data is read straight
 * off the live [NoteScore]s while the capture thread writes them: those writes are single
 * 32-bit fields, so a frame can be one write behind but can never see half a value, and one
 * frame of lag on a trace is invisible at 60 Hz. That is worth more than a lock on the audio
 * path.
 *
 * @param now song time to draw at. Must be the time the singer *hears*, not the raw player
 *   position — see `SyncCalibration.heardSongTimeFor`. Read inside the draw pass, so updating
 *   it repaints without recomposing.
 */
@Composable
fun NoteTrack(
    geometry: TrackGeometry,
    traces: List<Trace>,
    now: () -> Double,
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

    // Follows the passage being sung; see PitchRange for why a fixed scale does not work.
    val pitchRange = remember(geometry) { PitchRange() }

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
            drawTrack(geometry, traces, syllables, lyrics, pitchRange, now())
        }
    }
}

private fun DrawScope.drawTrack(
    geometry: TrackGeometry,
    traces: List<Trace>,
    syllables: List<TextLayoutResult>,
    lyrics: LyricLayout,
    pitchRange: PitchRange,
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
    val noteHeight = GameTheme.noteHeight.toPx()

    drawRect(GameTheme.trackBackground)

    val visible = geometry.visibleIndices(nowSeconds)
    val active = geometry.activeIndex(nowSeconds)

    pitchRange.follow(geometry.rangeOver(visible), nowSeconds)
    val low = if (pitchRange.isReady) pitchRange.low else geometry.lowMidi.toFloat()
    val high = if (pitchRange.isReady) pitchRange.high else geometry.highMidi.toFloat()

    drawOctaveLines(geometry, width, noteArea, low, high)

    if (!visible.isEmpty()) {
        drawNotes(geometry, visible, active, nowSeconds, width, noteArea, noteHeight, low, high)
        drawHits(geometry, traces, visible, nowSeconds, width, noteArea, noteHeight, low, high)
        drawTraces(geometry, traces, visible, nowSeconds, width, noteArea, low, high)
        drawLyrics(geometry, syllables, lyrics, visible, active, nowSeconds, width, lyricLane)
    }

    val playheadX = width * geometry.playheadFraction
    drawLine(
        color = GameTheme.playhead,
        start = Offset(playheadX, 0f),
        end = Offset(playheadX, size.height),
        strokeWidth = GameTheme.playheadWidth.toPx(),
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
 * Each singer's pitch, plotted at the instants the scorer judged it.
 *
 * Drawn per note rather than as one continuous line: joining the last beat of one note to the
 * first of the next would draw a confident stroke straight across a rest nobody sang.
 */
private fun DrawScope.drawTraces(
    geometry: TrackGeometry,
    traces: List<Trace>,
    visible: IntRange,
    nowSeconds: Double,
    width: Float,
    noteArea: Float,
    low: Float,
    high: Float,
) {
    val stroke = GameTheme.traceWidth.toPx()

    for (trace in traces) {
        for (i in visible) {
            val placed = geometry.placements[i]
            val score = trace.noteScores.getOrNull(i) ?: continue

            var joined = false
            var previousX = 0f
            var previousY = 0f

            for (beat in placed.beatMidSeconds.indices) {
                val sung = score.sungMidi(beat)
                if (sung.isNaN()) {
                    // A beat with nothing on it breaks the line, which is how silence is told
                    // apart from a wrong note.
                    joined = false
                    continue
                }

                val x = geometry.xFor(placed.beatMidSeconds[beat], nowSeconds, width)
                val y = geometry.yFor(foldToOctaveNear(sung, placed.midi), noteArea, low, high)

                if (joined) {
                    drawLine(trace.color, Offset(previousX, previousY), Offset(x, y), stroke, StrokeCap.Round)
                } else {
                    // A single sung beat is a dot, not nothing.
                    drawLine(trace.color, Offset(x, y), Offset(x, y), stroke, StrokeCap.Round)
                }
                previousX = x
                previousY = y
                joined = true
            }
        }
    }
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
