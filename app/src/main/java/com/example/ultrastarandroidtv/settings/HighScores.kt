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

    /**
     * Forgets every record held by [name], for when that profile is deleted.
     *
     * **A record outliving the person it names is the failure this prevents.** The library shows
     * who holds each song, and a name nobody can pick any more is worse than no name at all: it
     * cannot be beaten by the person it belongs to, cannot be explained to a child asking who
     * that is, and cannot be got rid of from anywhere in the app.
     *
     * The whole store is walked rather than an index kept per person. There are as many entries
     * as there are songs somebody has finished, this happens once when a profile is deleted, and
     * an index would be a second thing to keep true.
     */
    fun forget(name: String) {
        val editor = prefs.edit()
        var changed = false
        for ((key, value) in prefs.all) {
            val stored = value as? String ?: continue
            if (holderOf(stored)?.equals(name.trim(), ignoreCase = true) == true) {
                editor.remove(key)
                changed = true
            }
        }
        if (changed) editor.apply()
    }

    /**
     * Moves every record held by [from] over to [to].
     *
     * Renaming a profile is a correction rather than a new person, so their records come with
     * them. Without this a typo fixed on the profile screen would leave the library crediting a
     * name that no longer exists — and, since [forget] runs on deletion, one that could then only
     * be cleared by deleting a profile that is not there.
     */
    fun rename(from: String, to: String) {
        val target = to.trim()
        if (target.isEmpty()) return
        val editor = prefs.edit()
        var changed = false
        for ((key, value) in prefs.all) {
            val stored = value as? String ?: continue
            val points = pointsOf(stored) ?: continue
            if (holderOf(stored)?.equals(from.trim(), ignoreCase = true) != true) continue
            editor.putString(key, "$points:$target")
            changed = true
        }
        if (changed) editor.apply()
    }

    private fun key(songId: String, duet: Boolean): String =
        if (duet) "$songId|duet" else songId

    private fun pointsOf(stored: String): Int? {
        val split = stored.indexOf(':')
        if (split <= 0) return null
        return stored.substring(0, split).toIntOrNull()
    }

    private fun holderOf(stored: String): String? {
        val split = stored.indexOf(':')
        if (split <= 0) return null
        return stored.substring(split + 1)
    }
}

/**
 * [score], but only when a profile of that name still exists.
 *
 * The library credits records by name, and the rule is that it only ever names somebody the app
 * still knows. [HighScores.forget] is what actually clears a deleted profile's records, and this
 * is the guard in front of the display: records written before that existed, or by a build that
 * spelled things differently, would otherwise go on naming a stranger for ever with nothing
 * anywhere able to remove them.
 *
 * A free function rather than a method because the rule needs no `Context` and this is what lets
 * it be tested — the same reason `isNameTaken` lives beside [Profiles] rather than inside it.
 */
fun scoreIfKnown(score: HighScore?, known: Collection<String>): HighScore? {
    if (score == null) return null
    return if (known.any { it.equals(score.name.trim(), ignoreCase = true) }) score else null
}
