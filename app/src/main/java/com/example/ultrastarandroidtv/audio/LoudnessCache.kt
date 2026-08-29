package com.example.ultrastarandroidtv.audio

import android.content.Context

private const val PREFS = "loudness"

/**
 * Remembers how loud each recording is, so it is measured once and not once per play.
 *
 * **On disk, unlike the song library's cache, and for the opposite reason.** That one is in
 * memory only because it would have to answer when it goes stale, and over SAF that costs most of
 * the price of rescanning. This one cannot go stale: it is keyed by the document URI, and a
 * document URI names one particular file. Editing a song's audio in place would fool it, which
 * nothing in this app or on this card ever does — songs arrive whole and are deleted whole.
 *
 * The saving is worth having. Measuring costs a couple of hundred milliseconds while a title card
 * is up, and the commonest thing anybody does with a song is sing it again.
 */
class LoudnessCache(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val memory = mutableMapOf<String, Loudness>()

    /** What was measured for [uri], or null if it has never been measured. */
    fun get(uri: String): Loudness? {
        memory[uri]?.let { return it }
        val stored = prefs.getString(uri, null) ?: return null
        val parts = stored.split(':')
        if (parts.size != 2) return null
        val rms = parts[0].toFloatOrNull() ?: return null
        val peak = parts[1].toFloatOrNull() ?: return null
        return Loudness(rms, peak).also { memory[uri] = it }
    }

    /**
     * Records a measurement.
     *
     * [Loudness.UNKNOWN] is stored like any other answer. A file that would not decode will not
     * decode next time either, and remembering that is what stops every play of a broken song
     * paying the full measurement budget again.
     */
    fun put(uri: String, loudness: Loudness) {
        memory[uri] = loudness
        prefs.edit().putString(uri, "${loudness.rms}:${loudness.peak}").apply()
    }

    /** Measures [uri] unless it is already known. Blocking; call it off the main thread. */
    fun measure(context: Context, uri: String): Loudness =
        get(uri) ?: LoudnessScanner.measure(context, uri).also { put(uri, it) }
}
