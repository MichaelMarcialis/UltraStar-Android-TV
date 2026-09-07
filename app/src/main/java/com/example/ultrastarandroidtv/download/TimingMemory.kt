package com.example.ultrastarandroidtv.download

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

private const val PREFS = "timing_checked"

/** What a song's timing turned out to be, once. */
enum class TimingResult(val label: String) {
    /** In time with its own recording, or corrected until it was. */
    Fine("In time with its music"),

    /** Was out of time, and this app moved `#GAP` to put it right. */
    Corrected("Timing corrected to match the music"),

    /**
     * No offset fits: the chart was written against a different recording.
     *
     * Nothing can repair it here — the answer is a different chart or different audio, which
     * means the Add songs screen.
     */
    Wrong("The notes do not match this recording"),

    /**
     * Too little melody to judge, which is not a fault.
     *
     * Rap, spoken word and anything with the vocal buried land here. Recorded so the answer is
     * not worked out again on every visit, and reported as "could not tell" rather than as a
     * problem with the song.
     */
    Unknown("Could not tell — not enough melody to measure"),
}

/**
 * What listening to a song found, remembered.
 *
 * ## Why this is per song and not a library sweep
 *
 * It was a sweep for an afternoon: a "Check timing" button on the Songs screen that walked the
 * whole card. It worked — 123 songs, 29 minutes, seven corrected and eight named as unfixable —
 * and it was the wrong shape, because **its cost grows with the library and its value does not**.
 * Eighty-eight per cent of that half hour found nothing, and the same button on a five-hundred
 * song card is a two-hour job nobody presses twice. The user's call, and the right one.
 *
 * Two things make the batch close to redundant anyway. Every song downloaded from now on is
 * checked as it lands, at a moment that costs nobody anything, so the backlog only ever shrinks.
 * And when a bulk answer really is wanted, `tools/check_song_sync.py --card` gives it from a
 * workstation, which is where a half-hour job belongs.
 *
 * What is left in the app is the case somebody actually has: *this song sounded wrong*. That is
 * one press, about fifteen seconds, on the page for the song in question.
 *
 * ## Why it is still remembered
 *
 * Because the answer does not change on its own — nothing but this app rewrites a `#GAP` — so a
 * song checked once can simply say what it found ever after, without listening again. A song that
 * is repaired or replaced becomes a new chart at a new document id and is asked afresh.
 */
class TimingMemory(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * The song being listened to right now, by document id, or null.
     *
     * One at a time: this is a decode and a transform, and two at once would compete for the card
     * and the decoder while making both slower.
     */
    var checking: String? by mutableStateOf(null)
        internal set

    fun resultFor(textId: String): TimingResult? =
        prefs.getString(textId, null)?.let { name -> TimingResult.entries.firstOrNull { it.name == name } }

    internal fun remember(textId: String, result: TimingResult) {
        prefs.edit().putString(textId, result.name).apply()
    }

    /** Forgets a song, so it is asked again. For when its chart or its music has changed. */
    fun forget(textId: String) {
        prefs.edit().remove(textId).apply()
    }
}
