package com.example.ultrastarandroidtv.song

/**
 * Converts an UltraStar song's beat numbers into absolute playback seconds.
 *
 * UltraStar's `#BPM` is four times the song's real musical BPM — each beat unit in the note
 * data is a quarter of a musical beat, for finer note-timing granularity. `#GAP` is the
 * silence, in milliseconds, before beat 0.
 */
class BeatTimeConverter(bpm: Double, private val gapMs: Double) {
    constructor(metadata: SongMetadata) : this(metadata.bpm, metadata.gapMs)

    private val secondsPerBeat = 60.0 / (bpm * 4.0)

    fun beatToSeconds(beat: Int): Double = gapMs / 1000.0 + beat * secondsPerBeat
}
