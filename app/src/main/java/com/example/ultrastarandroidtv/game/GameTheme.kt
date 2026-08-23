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

    /**
     * The track's own panel.
     *
     * Translucent rather than opaque, and this is where the contrast against the video comes
     * from. Dimming the *whole* video to protect a strip of text at the bottom was the first
     * approach and it was backwards: it cost every pixel of the picture to fix a problem that
     * only exists where the text is. Contrast belongs behind the thing that needs it.
     */
    val trackBackground = Color(0xD90E0F16)

    /** Behind the title and the scores, for the same reason and at the same job. */
    val chipBackground = Color(0xB30A0B10)


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

    /**
     * The one singer's colour when nobody else is playing.
     *
     * Green because on your own the arrow is free to say how *well* you are singing rather than
     * *who* you are, and green is where that scale starts. With two people the colour is spoken
     * for — it is the only thing telling two arrows on one track apart — so this is solo only.
     */
    val soloPlayer = Color(0xFF5BE37A)

    /** Whose colour an arrow, score or name chip carries. Solo overrides the player order. */
    fun playerColor(index: Int, solo: Boolean): Color =
        if (solo) soloPlayer else playerColors[index % playerColors.size]

    /** The filled part of a note, showing which beats were actually paid for. */
    fun hitFill(player: Color): Color = player.copy(alpha = 0.55f)

    // ---- Solo arrow accuracy --------------------------------------------------------------

    val arrowOnPitch = Color(0xFF5BE37A)
    val arrowNear = Color(0xFFFFC24A)
    val arrowOff = Color(0xFFFF5A4A)

    /**
     * Green, amber, red by how far off the note a solo singer is.
     *
     * **Green holds for the whole scoring window** rather than starting to fade the moment the
     * pitch is not exact. Green therefore means "this is earning points", which is the same thing
     * the sparks say, and the two cannot contradict each other. Past the window it ramps to red
     * over the same span the tilt uses, so colour and angle tell one story.
     */
    fun arrowAccuracyColor(errorSemitones: Float, toleranceSemitones: Float): Color {
        val over = (kotlin.math.abs(errorSemitones) - toleranceSemitones).coerceAtLeast(0f)
        val span = (arrowFullTiltSemitones - toleranceSemitones).coerceAtLeast(0.01f)
        val t = (over / span).coerceIn(0f, 1f)
        return if (t < 0.5f) lerp(arrowOnPitch, arrowNear, t * 2f)
        else lerp(arrowNear, arrowOff, (t - 0.5f) * 2f)
    }

    // ---- Playhead ---------------------------------------------------------------------------

    val playhead = Color(0x66FFFFFF)
    val playheadWidth = 2.dp

    /**
     * The stretch between the arrows and the sing line: the song that is being judged *now*.
     *
     * It exists because the arrows sit a little left of the line — they show pitch measured from
     * audio that is already a moment old, so that is where that audio belongs. Correct, and
     * without this it read as the arrow having come loose from the line and drifted. Shading the
     * span turns a gap into a region with a meaning: everything inside it is in play.
     *
     * Very faint on purpose. It sits behind the notes and has to be legible as a change of
     * ground without competing with the one thing in the track that must be read.
     */
    val judgedBand = Color(0x12FFFFFF)

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

    /**
     * How far the arrow tilts when the singer is a long way off the note, in degrees.
     *
     * The arrow pivots about its **tip**, so the tail swings and the point stays put: it keeps
     * saying "your pitch is here" while starting to say "and it needs to go that way". Flat means
     * right, which is the same reading as the vertical gap and costs nothing to learn.
     *
     * Borrowed from Karaoke Revolution, and worth having because the vertical gap alone is
     * ambiguous at a glance — an arrow below a bar and an arrow below the bar *above* look
     * identical until you find the note it belongs to. A tilt is legible without reference to
     * anything else on screen.
     *
     * 26° is measured off the reference clip frame by frame rather than chosen: a badly-off
     * arrow there reads at about that, where this started at 34° and leaned further than the
     * thing it was copying.
     */
    val arrowMaxTiltDegrees = 26f

    /**
     * How far off the note the tilt reaches [arrowMaxTiltDegrees].
     *
     * Three semitones rather than the scoring tolerance, so the tilt is still growing across the
     * range a singer actually corrects over. Tied to the tolerance it would be at full deflection
     * the moment a note was missed, which says "wrong" and not "wrong by this much".
     */
    val arrowFullTiltSemitones = 3f

    // ---- Sparks -----------------------------------------------------------------------------

    /**
     * Struck where the arrow meets the bar, while the singer is inside the scoring window.
     *
     * Generous, because this is the game's one moment of reward and a thin dribble of dots reads
     * as a rendering artefact rather than as a prize. They cost nothing but a `drawCircle` each.
     */
    const val sparkCount = 34

    /**
     * How far the longest sparks trail **backwards** — leftwards, the way the notes are
     * travelling — so the arrow reads as scraping along the bar rather than as a firework going
     * off next to it. Each spark takes its own fraction of this, so the tail feathers out
     * instead of ending on a line.
     */
    val sparkReach = 120.dp

    /** Vertical scatter at the end of that trail: a fan, widening the further a spark gets. */
    val sparkSpread = 32.dp

    /** Small on purpose — a spark is a point of light, and a fat dot reads as a bubble. */
    val sparkRadius = 2.6.dp

    /** Bursts per second, before each spark's own variation on it. */
    const val sparkSpeed = 2.6

    /**
     * Deliberately *not* the singer's colour: this is meant to look like metal on metal, and
     * real sparks go white-hot at the strike and cool through gold to orange as they fly.
     * Position tells you whose spark it is — it is at their arrow — so the colour is free to say
     * what it is instead.
     *
     * Three stops rather than two: a straight white-to-orange ramp spends its whole middle in
     * washed-out cream, which is the least spark-like colour there is. Holding gold through the
     * middle keeps the trail hot the whole way down.
     */
    val sparkHot = Color(0xFFFFFFFF)
    val sparkWarm = Color(0xFFFFE27A)
    val sparkCool = Color(0xFFFF7A18)

    fun sparkColor(phase: Float): Color = when {
        phase < 0.5f -> lerp(sparkHot, sparkWarm, phase * 2f)
        else -> lerp(sparkWarm, sparkCool, (phase - 0.5f) * 2f)
    }

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

    // ---- Title card -------------------------------------------------------------------------

    /**
     * The song's name, shown alone in the middle of the screen before the music starts.
     *
     * It replaces the permanent corner label it grew out of. A title that sits there all song is
     * read once and then occupies a corner for three minutes; shown large, once, at the moment
     * everyone is waiting anyway, it is read by the whole room — and the corners are freed for
     * the two things that keep changing, which is the scores.
     */
    val titleSize = 68.sp
    val titleArtistSize = 32.sp

    /** How long the card holds at full strength before the music starts and it begins to go. */
    const val titleHoldMillis = 2200

    /** The crossfade from title card to game. Also how long the game takes to appear. */
    const val titleFadeMillis = 900
}
