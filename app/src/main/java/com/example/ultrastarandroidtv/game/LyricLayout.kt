package com.example.ultrastarandroidtv.game

/**
 * Nudges syllables apart so fast passages stay readable.
 *
 * Placing each syllable exactly under its note is the whole point of this layout, and it works
 * until the singing gets fast: at five seconds across the screen, syllables a fifth of a second
 * apart get about a quarter of the room their text needs, and real lyrics turn into
 * "Butsomething". Shrinking the font enough to fix that would make it unreadable across a
 * living room, and no setting of the visible window closes a threefold gap.
 *
 * So a syllable may be pushed **right** — later — but never left, and never past [maxLagSeconds]
 * behind its note. Sparse singing is unaffected and stays exactly aligned; dense singing spreads
 * out just enough to be read, in the right order. Rests still show as real gaps, because a gap
 * is where the pushing has room to unwind.
 *
 * Offsets are computed once for the whole song, in absolute coordinates, rather than per frame
 * over whatever is on screen. A per-frame layout would depend on which syllable happened to be
 * first visible, and the text would jitter as notes scrolled in.
 *
 * The chain deliberately runs straight through lyric lines rather than restarting at each one.
 * Restarting looks tidier and is worse: it lets the first syllable of a new line land on top of
 * the pushed tail of the last one, which is the most visible collision there is, right where a
 * new phrase starts. Nothing is lost by running through, because [maxLagSeconds] already bounds
 * every syllable against *its own* note — lateness cannot compound however long the song is.
 */
class LyricLayout(
    placements: List<PlacedNote>,
    /** Measured width of each syllable in pixels, parallel to [placements]. */
    widths: FloatArray,
    pixelsPerSecond: Float,
    minGapPixels: Float,
    /** How far behind its note a syllable may be pushed before it is allowed to collide instead. */
    maxLagSeconds: Float = 0.35f,
) {
    /** Pixels to shift syllable *i* right of its note's own position. Never negative. */
    val offsets: FloatArray = FloatArray(placements.size)

    init {
        val maxOffset = maxLagSeconds * pixelsPerSecond
        var previousRight = Float.NEGATIVE_INFINITY

        for (i in placements.indices) {
            val placed = placements[i]
            val natural = (placed.startSeconds * pixelsPerSecond).toFloat()
            // Past the cap, letting it overlap is the lesser evil: a syllable that arrives long
            // after the note it belongs to is worse than one that is slightly crowded.
            val x = maxOf(natural, previousRight + minGapPixels)
                .coerceAtMost(natural + maxOffset)

            offsets[i] = x - natural
            // A note with no text should not shove its neighbours around.
            if (widths[i] > 0f) previousRight = x + widths[i]
        }
    }
}
