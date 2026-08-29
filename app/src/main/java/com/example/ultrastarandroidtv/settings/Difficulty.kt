package com.example.ultrastarandroidtv.settings

/**
 * How forgiving the judging is, as one choice rather than a number.
 *
 * There is exactly one lever behind this — `ScoringConfig.toleranceSemitones`, how far off the
 * target pitch class still counts as a hit — and it is deliberately not exposed as a slider.
 * "1.4 semitones" is not a thing anybody wants to reason about from a sofa, and the three names
 * are what the choice actually is.
 *
 * **It moves the drawing as well as the score, and that is the property to protect.** A note is
 * drawn exactly as tall as the window that scores it, so an easier setting has visibly taller
 * bars and "the arrow is inside the bar" stays the same statement as "this beat counts". Any
 * future difficulty knob has to keep both reading the same constant, or the game starts showing
 * one thing and paying for another.
 *
 * **Nothing else moves with it.** In particular `ScoringConfig.onsetGraceSeconds` stays where it
 * is on every setting: that 65 ms is a measured debt this app owes the singer for its own
 * detection lag, not generosity, and rolling a correction and a handicap into one control makes
 * it impossible to say afterwards which of them a score came from.
 */
enum class Difficulty(
    /** What the setting screen calls it. */
    val label: String,
    /** Semitones either side of the note that still count — and the note's drawn height. */
    val toleranceSemitones: Float,
) {
    /**
     * Twice the window [HARD] gives, which is about as wide as this can honestly go: the
     * furthest two pitch classes can ever be apart is six semitones, so a third of every pitch
     * in the octave scores. Past here it stops being forgiving and starts crediting wrong notes.
     */
    EASY("Easy", 2.0f),

    /**
     * The default, and half a semitone more room than the game shipped with.
     *
     * The diagnostics say where that half goes: of the beats a real performance misses, about
     * 11 % are genuinely off pitch at a median of 1.75 semitones out, so this reaches a good
     * share of the near misses without touching the ones that were never close.
     */
    NORMAL("Normal", 1.5f),

    /**
     * What the game was before there was a setting at all.
     *
     * A semitone is the smallest interval in the music, so this is the strictest reading that
     * still makes sense: sing a note flat by more than the distance to the next note down and
     * it does not count.
     */
    HARD("Hard", 1.0f),
    ;

    companion object {
        val DEFAULT = NORMAL

        /** Tolerant of a stored name from a build that spelled them differently. */
        fun byName(name: String?): Difficulty =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
