package com.example.ultrastarandroidtv.settings

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

private const val PREFS = "profiles"
private const val KEY_NAMES = "names"
private const val SEPARATOR = "\n"

/** Long enough for any name anyone will actually use, short enough to fit a card. */
const val MAX_NAME_LENGTH = 16

/**
 * The names of everyone who sings, most recently used first.
 *
 * Deliberately not accounts: no scores, no history, no settings of their own. A name exists so
 * that a score can be attributed to a person instead of to a number, and so that picking that
 * person again is one press.
 *
 * **Order is the whole feature.** Names have to be chosen at the start of every game, because
 * with four children rotating there is no such thing as "the same lineup as last time" — the
 * one thing that reliably predicts who is about to sing is who sang most recently. Sorting by
 * that turns a list into two presses rather than a hunt.
 */
class Profiles(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Every known name, most recently used first. */
    var names: List<String> by mutableStateOf(
        prefs.getString(KEY_NAMES, "")
            ?.split(SEPARATOR)
            ?.filter { it.isNotBlank() }
            ?: emptyList(),
    )
        private set

    /**
     * Records that [name] is singing, adding it if it is new, and moves it to the front.
     *
     * Matching ignores case, so "mia" typed in a hurry selects the existing "Mia" rather than
     * quietly creating a second profile that looks identical on screen.
     */
    fun use(name: String) {
        val trimmed = name.trim().take(MAX_NAME_LENGTH)
        if (trimmed.isEmpty()) return

        val existing = names.firstOrNull { it.equals(trimmed, ignoreCase = true) }
        val kept = existing ?: trimmed

        names = listOf(kept) + names.filterNot { it.equals(kept, ignoreCase = true) }
        prefs.edit().putString(KEY_NAMES, names.joinToString(SEPARATOR)).apply()
    }

    /** True when [name] would land on an existing profile rather than create one. */
    fun exists(name: String): Boolean =
        names.any { it.equals(name.trim(), ignoreCase = true) }
}

/**
 * True when [name] belongs to somebody else already singing this game.
 *
 * Case-insensitive, matching [Profiles.use]: the two have to agree, or a name typed in a
 * different case would be refused by one and folded onto the existing profile by the other.
 *
 * Free functions rather than methods because [Profiles] needs a `Context` and this rule does
 * not — which is what lets it be tested rather than only read.
 */
fun isNameTaken(name: String, taken: Set<String>): Boolean =
    taken.any { it.equals(name.trim(), ignoreCase = true) }

/**
 * [all] minus anyone already singing this game.
 *
 * One person cannot hold both microphones, so the second singer is never offered the first
 * singer's name at all — an option that cannot be chosen is only there to be pressed by mistake.
 */
fun namesAvailable(all: List<String>, taken: Set<String>): List<String> =
    all.filterNot { isNameTaken(it, taken) }
