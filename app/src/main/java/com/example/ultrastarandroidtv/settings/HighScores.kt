package com.example.ultrastarandroidtv.settings

import android.content.Context

private const val PREFS = "high_scores"

/** The best anybody has done on one song, and who did it. */
data class HighScore(val points: Int, val name: String)

/**
 * The best score each song has ever seen, kept between launches.
 *
 * This is the only thing in the app that remembers a *result*, and it is what turns a song into
 * something worth singing twice. Everything else here is deliberately forgetful — names are asked
 * every game, the library forgets its place — because remembering the wrong thing is worse than
 * asking. A record is different: it belongs to the song rather than to whoever happens to be
 * holding a microphone.
 *
 * **Duets are recorded separately from individual scores**, because they are not the same
 * measurement. A duet is marked as one pair singing two parts and an individual score is one
 * person singing the whole thing, and a table that mixed them would let a duet quietly take the
 * record for a song nobody sang alone. Versus and solo scores share a table, since both are one
 * person against the whole part — which is exactly the comparison a record is for.
 */
class HighScores(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The record for [songId], or null if the song has never been finished. */
    fun best(songId: String, duet: Boolean): HighScore? {
        val stored = prefs.getString(key(songId, duet), null) ?: return null
        val split = stored.indexOf(':')
        if (split <= 0) return null
        val points = stored.substring(0, split).toIntOrNull() ?: return null
        return HighScore(points, stored.substring(split + 1))
    }

    /**
     * Records [points] by [name] if it beats what is there, and says whether it did.
     *
     * **Strictly greater**, so equalling a record does not steal it. Nobody has done better, and
     * telling somebody they beat a score they matched is a small lie the game does not need to
     * tell.
     */
    fun record(songId: String, duet: Boolean, points: Int, name: String): Boolean {
        val previous = best(songId, duet)
        if (previous != null && points <= previous.points) return false
        // A song nobody scored anything on has not set a record; it has been abandoned.
        if (points <= 0) return false
        prefs.edit().putString(key(songId, duet), "$points:$name").apply()
        return true
    }

    private fun key(songId: String, duet: Boolean): String =
        if (duet) "$songId|duet" else songId
}
