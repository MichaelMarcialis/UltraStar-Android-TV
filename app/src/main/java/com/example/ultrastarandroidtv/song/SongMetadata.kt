package com.example.ultrastarandroidtv.song

/**
 * Header (`#TAG:VALUE`) data from an UltraStar song's `.txt` file.
 *
 * File paths (mp3/cover/background/video) are stored exactly as written in the file and are
 * relative to the song's own folder, per the UltraStar format convention.
 */
data class SongMetadata(
    val title: String,
    val artist: String,
    /**
     * The audio file named by `#MP3`, or null when the chart does not name one.
     *
     * **Optional on purpose.** A chart with no `#MP3` is not malformed — it is the normal
     * product of a download whose media step failed, and twenty-two of the seventy-one folders
     * on this card look exactly like that. Treating it as a parse error made those songs
     * invisible: they never became songs at all, so nothing could list them, explain them or
     * remove them, and [com.example.ultrastarandroidtv.library.ScannedSong.isPlayable] could
     * never actually be false. A missing audio file is a fact about a song, not a reason to
     * refuse to read it.
     */
    val mp3: String?,
    val bpm: Double,
    val gapMs: Double = 0.0,
    /**
     * How long after the audio the video should start, **in seconds**.
     *
     * Not a typo and not consistent with [gapMs]: the UltraStar format really does measure
     * `#GAP` in milliseconds and `#VIDEOGAP` in seconds. This field was called `videoGapMs`
     * until video was wired up, which would have put every video out by a factor of a thousand
     * the first time anyone used it.
     */
    val videoGapSeconds: Double = 0.0,
    val cover: String? = null,
    val background: String? = null,
    val video: String? = null,
    val genre: String? = null,
    val year: Int? = null,
    val language: String? = null,
    val edition: String? = null,
    val creator: String? = null,
    val startSeconds: Double? = null,
    val endMs: Double? = null,
    val previewStartSeconds: Double? = null,
    val relative: Boolean = false,
    val duetSingerP1: String? = null,
    val duetSingerP2: String? = null,
    /** Every tag as read from the file, key uppercased. Includes the fields above verbatim. */
    val rawTags: Map<String, String> = emptyMap(),
)
