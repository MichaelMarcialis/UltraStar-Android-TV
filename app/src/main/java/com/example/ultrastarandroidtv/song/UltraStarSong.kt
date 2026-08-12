package com.example.ultrastarandroidtv.song

/** How a note counts toward scoring. */
enum class NoteType {
    /** Regular sung note, scored normally. */
    NORMAL,
    /** Bonus note, scored normally plus a golden-note bonus. */
    GOLDEN,
    /** Sung freely, not scored against a reference pitch. */
    FREESTYLE,
    /** Rap note: hit/rhythm scored, pitch is not. */
    RAP,
    /** Rap note with a golden-note bonus. */
    GOLDEN_RAP,
}

/**
 * A single note. [startBeat] and [durationBeats] are in the song's beat units — see
 * [BeatTimeConverter] to turn them into playback seconds. [pitch] is the UltraStar relative
 * pitch value (semitones from the format's reference tone), not yet mapped to a concrete
 * frequency or MIDI note.
 */
data class Note(
    val type: NoteType,
    val startBeat: Int,
    val durationBeats: Int,
    val pitch: Int,
    val text: String,
)

/** One line of lyrics: the notes sung on it, and the beat of the line break that follows it. */
data class LyricLine(
    val notes: List<Note>,
    /** Beat of the `-` line-break marker after this line, or null for the song's last line. */
    val lineBreakBeat: Int?,
)

/** One singer's part. [label] is `"P1"`/`"P2"` for a duet, or null for a solo song. */
data class VoicePart(
    val label: String?,
    val lines: List<LyricLine>,
)

/** A fully parsed UltraStar song. [voiceParts] has one entry for solo songs, two for duets. */
data class UltraStarSong(
    val metadata: SongMetadata,
    val voiceParts: List<VoicePart>,
)
