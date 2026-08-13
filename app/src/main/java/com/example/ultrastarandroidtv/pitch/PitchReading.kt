package com.example.ultrastarandroidtv.pitch

/**
 * One pitch estimate, covering a single analysis window.
 *
 * [voiced] is false when the window held silence, noise, or anything without a fundamental
 * the detector would commit to — [frequencyHz], [midi] and [probability] are then all zero
 * and mean nothing. Scoring should treat an unvoiced reading as "sang nothing", which is a
 * different thing from "sang the wrong note".
 */
data class PitchReading(
    val voiced: Boolean,
    val frequencyHz: Float,
    /** Fractional MIDI note number — see [hzToMidi]. */
    val midi: Float,
    /** The detector's confidence, 0..1. */
    val probability: Float,
    /** Normalised RMS of the window, 0..1 — how loudly they sang it. */
    val level: Float,
) {
    companion object {
        /** Nothing usable in this window, but we still know how loud it was. */
        fun unvoiced(level: Float): PitchReading = PitchReading(false, 0f, 0f, 0f, level)
    }
}
