package com.example.ultrastarandroidtv.library

/**
 * How long a query has to be before a typo is forgiven.
 *
 * Below this, only plain containment counts. That is not caution for its own sake: on two letters
 * an edit-distance rule matches most of a library, and a search that answers "everything" the
 * moment you start typing is worse than one that answers nothing — the count above the keyboard
 * stops meaning anything.
 */
const val FUZZY_MIN_TOKEN = 3

/** One typo forgiven up to here, two beyond it. Longer words earn more room because they hold more. */
private const val TWO_EDIT_LENGTH = 7

/**
 * The form everything is compared in: lower case, accents folded, punctuation gone.
 *
 * Punctuation is the half of this that earns its place on a real library. "Guns N' Roses" is typed
 * "guns n roses"; "a-ha" is typed "aha" or "a ha"; "Don't Stop Me Now" is typed without the
 * apostrophe every time, because the apostrophe is three presses away on a drawn keyboard and
 * nobody is going to bother. Turning every run of non-letters into one space makes all of those the
 * same string, which is what somebody typing them plainly meant.
 */
fun searchKey(text: String): String {
    val out = StringBuilder(text.length)
    var space = false
    for (character in text) {
        // An apostrophe is not a word break, it is a letter that was left out: "don't" is one
        // word and has to normalise to "dont", because that is what gets typed. Every other mark
        // separates -- which is what turns "a-ha" into two words that also close up into one.
        if (character in APOSTROPHES) continue
        val folded = fold(character)
        if (folded == null) {
            space = out.isNotEmpty()
        } else {
            if (space) out.append(' ')
            space = false
            out.append(folded)
        }
    }
    return out.toString()
}

/** Marks that sit *inside* a word rather than between two. */
private const val APOSTROPHES = "'’‘`´"

/** Accents off, case down. Anything that is not a letter or a digit is not part of a word. */
private fun fold(character: Char): Char? {
    val lower = character.lowercaseChar()
    ACCENTS[lower]?.let { return it }
    return if (lower.isLetterOrDigit()) lower else null
}

/**
 * The accents this library actually contains, rather than a full Unicode normaliser.
 *
 * `java.text.Normalizer` would do the whole job in one line and is deliberately not used: it drags
 * in a decomposition table for a handful of European vowels, and this list is checkable by eye
 * against the song titles on the card.
 */
private val ACCENTS: Map<Char, Char> = buildMap {
    "àáâãäåāă".forEach { put(it, 'a') }
    "èéêëēĕėęě".forEach { put(it, 'e') }
    "ìíîïĩīĭ".forEach { put(it, 'i') }
    "òóôõöøōŏ".forEach { put(it, 'o') }
    "ùúûüũūŭ".forEach { put(it, 'u') }
    "ýÿ".forEach { put(it, 'y') }
    put('ñ', 'n')
    put('ç', 'c')
    put('ß', 's')
}

/**
 * Whether some text answers to what was typed, forgivingly.
 *
 * Four rules, tried in order, each one looser than the last:
 *
 *  1. **Containment**, on the normalised text. The ordinary case, and the only one that applies to
 *     a query of one or two letters.
 *  2. **Containment with the spaces closed up**, which is what makes "aha" find "a-ha" and
 *     "gunsnroses" find "Guns N' Roses".
 *  3. **Every word of the query appears somewhere**, in any order — so "beatles yesterday" finds
 *     the song whether it is filed by artist or by title, and "yesterday beatles" finds it too.
 *  4. **Every word of the query is within a typo or two of some word in the text.** Transpositions
 *     count as one edit rather than two, because swapping two letters is far and away the commonest
 *     mistake made on a directional pad.
 *
 * A word only ever earns forgiveness for itself: a two-word query where one word matches exactly
 * and the other matches nothing at all is not a match, which is what stops rule 4 quietly widening
 * into "any song containing any of these letters".
 */
fun fuzzyMatches(text: String, query: String): Boolean {
    val needle = searchKey(query)
    if (needle.isEmpty()) return true
    val haystack = searchKey(text)
    if (haystack.isEmpty()) return false

    if (haystack.contains(needle)) return true
    if (haystack.replace(" ", "").contains(needle.replace(" ", ""))) return true

    val tokens = needle.split(' ').filter { it.isNotEmpty() }
    val words = haystack.split(' ').filter { it.isNotEmpty() }
    if (tokens.isEmpty() || words.isEmpty()) return false

    return tokens.all { token -> words.any { word -> wordAnswers(word, token) } }
}

/** Whether one word of the text answers one word of the query. */
private fun wordAnswers(word: String, token: String): Boolean {
    if (word.contains(token)) return true
    if (token.length < FUZZY_MIN_TOKEN) return false
    val allowed = if (token.length >= TWO_EDIT_LENGTH) 2 else 1
    // A length difference bigger than the budget cannot be closed, and checking it first is what
    // keeps this from running the whole matrix against every word of every title.
    if (kotlin.math.abs(word.length - token.length) > allowed) return false
    return editsWithin(word, token, allowed)
}

/**
 * Whether [a] and [b] are within [allowed] edits of each other — insert, delete, substitute, or
 * **swap two neighbours**, which is what an optimal string alignment distance adds over a plain
 * Levenshtein one.
 *
 * That last case is the whole reason it is not plain Levenshtein: "beatels" for "beatles" is one
 * slip of a thumb and two Levenshtein edits, so a one-typo budget would refuse the commonest typo
 * there is.
 *
 * Returns as soon as the budget is certainly spent, so the cost is bounded by the words rather
 * than by the library.
 */
fun editsWithin(a: String, b: String, allowed: Int): Boolean {
    if (a == b) return true
    if (allowed <= 0) return false
    if (kotlin.math.abs(a.length - b.length) > allowed) return false

    var twoBack = IntArray(b.length + 1)
    var previous = IntArray(b.length + 1) { it }
    var current = IntArray(b.length + 1)

    for (i in 1..a.length) {
        current[0] = i
        var best = current[0]
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            var value = minOf(
                current[j - 1] + 1,
                previous[j] + 1,
                previous[j - 1] + cost,
            )
            // The transposition: the two previous characters swapped.
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                value = minOf(value, twoBack[j - 2] + 1)
            }
            current[j] = value
            if (value < best) best = value
        }
        // Nothing later in the word can bring a whole row back under budget.
        if (best > allowed) return false

        val spare = twoBack
        twoBack = previous
        previous = current
        current = spare
    }
    return previous[b.length] <= allowed
}
