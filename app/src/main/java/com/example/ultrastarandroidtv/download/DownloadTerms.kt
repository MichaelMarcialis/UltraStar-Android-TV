package com.example.ultrastarandroidtv.download

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

private const val PREFS = "download_terms"
private const val KEY_ACCEPTED = "accepted_version"

/**
 * The version of the notice that has been read.
 *
 * A number rather than a boolean so the notice can be shown again if what it says ever materially
 * changes. Bumping it asks everybody once more; leaving it alone never asks anyone twice.
 */
private const val CURRENT_VERSION = 1

/**
 * Whether the person using this has been told what downloading a song does, and said they
 * understood.
 *
 * ## Why there is a gate at all
 *
 * The Add songs screen fetches a chart from USDB and the matching audio and video from YouTube,
 * and writes all of it to the card. That is a copy of a commercial recording, and whether making
 * it is lawful depends entirely on something this app cannot see: whether the person already owns
 * the record, and what their country says about format shifting. The app is not in a position to
 * judge it, and the honest thing is to say so once, plainly, to the person who *is*.
 *
 * ## Why here and not at launch
 *
 * Most of a karaoke night never touches this screen. A notice at first launch would interrupt
 * everybody — including a household that copied its own songs onto a card and will never press
 * Add songs — to talk about a feature they are not using, which is how a notice becomes something
 * people learn to dismiss without reading. It is shown at the one moment it describes what is
 * about to happen, and then never again.
 *
 * ## Why it is remembered rather than asked each time
 *
 * A question asked every time is not a question, it is a door handle. Asking once and recording
 * the answer is what makes it mean something, and the Add songs screen carries a permanent
 * one-line reminder afterwards so the point stays visible without being in the way.
 */
class DownloadTerms(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Compose state, so accepting moves the screen on without anything having to be told to
     * recompose.
     */
    var accepted: Boolean by mutableStateOf(
        prefs.getInt(KEY_ACCEPTED, 0) >= CURRENT_VERSION
    )
        private set

    /** Records that the notice has been read. Written through at once — there is no later save. */
    fun accept() {
        prefs.edit().putInt(KEY_ACCEPTED, CURRENT_VERSION).apply()
        accepted = true
    }
}
