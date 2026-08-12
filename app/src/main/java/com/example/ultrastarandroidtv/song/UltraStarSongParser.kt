package com.example.ultrastarandroidtv.song

/**
 * Parses the UltraStar song `.txt` format: a block of `#TAG:VALUE` header lines followed by
 * note data — note lines (`: * F R G`), line breaks (`-`), optional duet markers (`P1`/`P2`),
 * and an optional trailing `E` end marker.
 *
 * Takes raw text/lines rather than a file so it has no Android or I/O dependency; reading the
 * file (and picking an encoding) is the caller's job.
 */
object UltraStarSongParser {

    private const val BYTE_ORDER_MARK = "﻿"

    private val noteTypesByChar = mapOf(
        ':' to NoteType.NORMAL,
        '*' to NoteType.GOLDEN,
        'F' to NoteType.FREESTYLE,
        'R' to NoteType.RAP,
        'G' to NoteType.GOLDEN_RAP,
    )

    fun parse(text: String): UltraStarSong = parse(text.lineSequence().toList())

    fun parse(lines: List<String>): UltraStarSong {
        val rawTags = LinkedHashMap<String, String>()
        val linesByVoice = LinkedHashMap<String?, MutableList<LyricLine>>()
        var currentVoiceLabel: String? = null
        var currentNotes = mutableListOf<Note>()

        fun currentLineList(): MutableList<LyricLine> =
            linesByVoice.getOrPut(currentVoiceLabel) { mutableListOf() }

        fun finishLine(lineBreakBeat: Int?) {
            if (currentNotes.isEmpty()) return
            currentLineList().add(LyricLine(currentNotes, lineBreakBeat))
            currentNotes = mutableListOf()
        }

        for (rawLine in lines) {
            val line = rawLine.removePrefix(BYTE_ORDER_MARK).trim()
            if (line.isEmpty()) continue

            when {
                line.startsWith("#") -> parseHeaderTag(line)?.let { (key, value) ->
                    rawTags[key] = value
                }
                line == "E" -> break
                line == "P1" || line == "P2" -> {
                    finishLine(null)
                    currentVoiceLabel = line
                    currentLineList()
                }
                line.startsWith("-") -> finishLine(parseLineBreak(line))
                else -> currentNotes.add(parseNoteLine(line))
            }
        }
        finishLine(null)

        if (linesByVoice.isEmpty()) {
            throw SongParseException("Song has no note data")
        }

        val voiceParts = linesByVoice.map { (label, lyricLines) -> VoicePart(label, lyricLines) }
        return UltraStarSong(buildMetadata(rawTags), voiceParts)
    }

    private fun parseHeaderTag(line: String): Pair<String, String>? {
        val body = line.removePrefix("#")
        val separatorIndex = body.indexOf(':')
        if (separatorIndex < 0) return null
        val key = body.substring(0, separatorIndex).trim().uppercase()
        if (key.isEmpty()) return null
        return key to body.substring(separatorIndex + 1).trim()
    }

    private fun parseLineBreak(line: String): Int {
        val tokens = line.split(Regex("\\s+"))
        return tokens.getOrNull(1)?.toIntOrNull()
            ?: throw SongParseException("Invalid line break: \"$line\"")
    }

    private fun parseNoteLine(line: String): Note {
        val tokens = line.split(Regex("\\s+"), limit = 5)
        val typeChar = tokens.getOrNull(0)?.singleOrNull()
            ?: throw SongParseException("Malformed line: \"$line\"")
        val type = noteTypesByChar[typeChar]
            ?: throw SongParseException("Unknown line type '$typeChar' in: \"$line\"")
        val startBeat = tokens.getOrNull(1)?.toIntOrNull()
            ?: throw SongParseException("Invalid note start beat in: \"$line\"")
        val duration = tokens.getOrNull(2)?.toIntOrNull()
            ?: throw SongParseException("Invalid note duration in: \"$line\"")
        val pitch = tokens.getOrNull(3)?.toIntOrNull()
            ?: throw SongParseException("Invalid note pitch in: \"$line\"")
        val text = tokens.getOrElse(4) { "" }
        return Note(type, startBeat, duration, pitch, text)
    }

    private fun buildMetadata(rawTags: Map<String, String>): SongMetadata {
        fun required(key: String): String =
            rawTags[key] ?: throw SongParseException("Missing required tag #$key")

        val bpmText = required("BPM")
        val bpm = parseLocaleDouble(bpmText)
            ?: throw SongParseException("Invalid #BPM value: \"$bpmText\"")

        return SongMetadata(
            title = required("TITLE"),
            artist = required("ARTIST"),
            mp3 = required("MP3"),
            bpm = bpm,
            gapMs = rawTags["GAP"]?.let { parseLocaleDouble(it) } ?: 0.0,
            videoGapMs = rawTags["VIDEOGAP"]?.let { parseLocaleDouble(it) } ?: 0.0,
            cover = rawTags["COVER"],
            background = rawTags["BACKGROUND"],
            video = rawTags["VIDEO"],
            genre = rawTags["GENRE"],
            year = rawTags["YEAR"]?.toIntOrNull(),
            language = rawTags["LANGUAGE"],
            edition = rawTags["EDITION"],
            creator = rawTags["CREATOR"],
            startSeconds = rawTags["START"]?.let { parseLocaleDouble(it) },
            endMs = rawTags["END"]?.let { parseLocaleDouble(it) },
            previewStartSeconds = rawTags["PREVIEWSTART"]?.let { parseLocaleDouble(it) },
            relative = rawTags["RELATIVE"]?.equals("YES", ignoreCase = true) ?: false,
            duetSingerP1 = rawTags["DUETSINGERP1"] ?: rawTags["P1"],
            duetSingerP2 = rawTags["DUETSINGERP2"] ?: rawTags["P2"],
            rawTags = rawTags,
        )
    }

    /** Many UltraStar files use a comma decimal separator (e.g. `123,45`) for numeric tags. */
    private fun parseLocaleDouble(value: String): Double? =
        value.trim().replace(',', '.').toDoubleOrNull()
}
