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
    val mp3: String,
    val bpm: Double,
    val gapMs: Double = 0.0,
    val videoGapMs: Double = 0.0,
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
