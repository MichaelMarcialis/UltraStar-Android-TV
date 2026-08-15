package com.example.ultrastarandroidtv.game

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Every colour and measurement the gameplay screen draws with, in one place.
 *
 * Deliberately a flat list of constants rather than a Material theme: this screen is a
 * scoreboard and an instrument, not a set of Material surfaces, and almost none of it maps onto
 * a `ColorScheme` slot. Keeping it here means the look can be tuned without reading any drawing
 * code — which is the point, since the drawing code is arithmetic and the look is a judgement.
 */
object GameTheme {

    // ---- Background -------------------------------------------------------------------------

    /** Near-black with a slight blue cast; a true black smears on OLED as notes scroll across. */
    val background = Color(0xFF0E0E13)

    /** The track's own panel, lifted just enough to separate it from the page. */
    val trackBackground = Color(0xFF16171F)

    /**
     * Laid over the song video so the game stays readable on top of it.
     *
     * Heavy, and deliberately so: this has to work over the brightest, busiest video in the
     * library, not the average one, and the notes and lyrics are thin bright shapes that a
     * light background destroys. Turn it down only after checking the worst song, not the
     * prettiest.
     */
    val videoScrim = Color(0xCC07070B)

    /** Faint horizontal rule every octave, so leaps have something to be measured against. */
    val octaveLine = Color(0x14FFFFFF)

    // ---- Notes ------------------------------------------------------------------------------

    /** An ordinary note, not yet sung. */
    val noteIdle = Color(0xFF39404F)

    /** A golden note is worth double, so it announces itself before it arrives. */
    val noteGolden = Color(0xFF6B5A22)

    /** Freestyle notes score nothing; they are shown as ghosts so the singer knows to relax. */
    val noteFreestyle = Color(0xFF23262F)

    /** The note currently under the playhead, lifted so the eye can find its place instantly. */
    val noteActive = Color(0xFF4A5468)
    val noteActiveGolden = Color(0xFF917826)

    // ---- Players ----------------------------------------------------------------------------

    /**
     * One colour per singer, in player order.
     *
     * Chosen to stay distinct for the commonest form of colour blindness — the pair differs in
     * lightness as well as hue, so they are still tellable apart in greyscale.
     */
    val playerColors = listOf(
        Color(0xFF4FC3F7), // Player 1: bright cyan
        Color(0xFFFF7BA6), // Player 2: warm pink
    )

    /** The filled part of a note, showing which beats were actually paid for. */
    fun hitFill(player: Color): Color = player.copy(alpha = 0.55f)

    // ---- Playhead ---------------------------------------------------------------------------

    val playhead = Color(0x66FFFFFF)
    val playheadWidth = 2.dp

    // ---- Pitch arrow ------------------------------------------------------------------------

    /**
     * Height of the arrow, tip to tail.
     *
     * Kept narrow on purpose: in two-player mode both arrows share the sing line and spend much
     * of a song within a few semitones of each other, so a tall arrow means constant overlap.
     */
    val arrowHeight = 17.dp

    /** How far it reaches toward the sing line. */
    val arrowWidth = 22.dp

    /** Clearance between the arrow's tip and the sing line, so the two never merge. */
    val arrowGap = 7.dp

    /** A soft halo behind the arrow, so it stays findable against a busy note field. */
    const val arrowGlowScale = 1.75f

    fun arrowGlow(player: Color): Color = player.copy(alpha = 0.22f)

    // ---- Sparks -----------------------------------------------------------------------------

    /** Struck where the arrow meets the bar, while the singer is inside the scoring window. */
    const val sparkCount = 16

    /**
     * How far a spark trails **backwards** — leftwards, the way the notes are travelling — so
     * the arrow reads as scraping along the bar rather than as a firework going off next to it.
     */
    val sparkReach = 46.dp

    /** Vertical scatter at the end of that trail. Small: this is a scrape, not an explosion. */
    val sparkSpread = 11.dp

    /** Small on purpose — a spark is a point of light, and a fat dot reads as a bubble. */
    val sparkRadius = 2.dp

    /** Bursts per second. Fast enough to read as sparks rather than as orbiting dots. */
    const val sparkSpeed = 3.0

    /**
     * Deliberately *not* the singer's colour: this is meant to look like metal on metal, and
     * real sparks go white-hot at the strike and cool through amber as they fly. Position tells
     * you whose spark it is — it is at their arrow — so the colour is free to say what it is.
     */
    val sparkHot = Color(0xFFFFFDF0)
    val sparkCool = Color(0xFFFF9A2E)

    fun sparkColor(phase: Float): Color = lerp(sparkHot, sparkCool, phase)

    // ---- Dimensions -------------------------------------------------------------------------

    /**
     * A note is normally drawn exactly as tall as the window that scores it, so that being on
     * the bar and scoring are the same thing. This is the floor for that: a wide-ranging song
     * in a shallow track would otherwise produce bars too thin to aim at.
     */
    val minNoteHeight = 7.dp

    val noteGapSeconds = 0.012 // Trimmed off each note's end so neighbours do not fuse into one bar.
    val trackPadding = 20.dp

    /**
     * Share of the screen the track occupies, leaving the rest for the song video.
     *
     * A shallow track is only possible because the vertical scale is fixed — nothing has to
     * make room for the view zooming or panning.
     */
    const val trackScreenShare = 0.26f

    /** Each half of a duet, so two tracks still leave the top half of the screen free. */
    const val duetTrackScreenShare = 0.22f

    /** Reserved under the notes for the syllables, which scroll on the same axis. */
    val lyricLaneHeight = 68.dp

    /** Never more of a track than this, however short the track is. */
    const val lyricLaneMaxShare = 0.28f
    val lyricSize = 26.sp

    /** Clear space kept between neighbouring syllables. See [LyricLayout]. */
    val lyricMinGap = 14.dp

    /** The syllable being sung right now. */
    val lyricActive = Color(0xFFFFFFFF)

    /** Syllables still to come, and ones just gone. Readable, but not competing with the active one. */
    val lyricIdle = Color(0xFF8A93A6)

    // ---- Score header -----------------------------------------------------------------------

    val scoreSize = 44.sp
    val nameSize = 20.sp
    val headerHeight = 76.dp
}
