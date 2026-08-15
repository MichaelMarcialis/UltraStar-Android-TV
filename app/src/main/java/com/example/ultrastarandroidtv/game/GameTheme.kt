package com.example.ultrastarandroidtv.game

import androidx.compose.ui.graphics.Color
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

    // ---- Dimensions -------------------------------------------------------------------------

    val noteHeight = 18.dp
    val noteGapSeconds = 0.012 // Trimmed off each note's end so neighbours do not fuse into one bar.
    val traceWidth = 5.dp
    val trackPadding = 20.dp

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
