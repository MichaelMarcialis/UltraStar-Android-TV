package com.example.ultrastarandroidtv.ui

/**
 * Text being typed on the drawn keyboard, and where in it the next letter goes.
 *
 * It exists because the keyboard grew a cursor. Before that the query was a plain `String` and
 * every edit was `+ it` or `dropLast(1)`, which two screens could each get right on their own.
 * With a cursor there are six edits, four of them care about position, and *both* screens have to
 * agree on what pressing left at the start of the text does — so the rules live here once, pure
 * and unit-tested, rather than twice in two composables.
 *
 * [cursor] is always a valid insertion point: 0 is before the first character and [text].length is
 * after the last. Every operation returns a value that still satisfies that, so nothing downstream
 * has to guard a substring.
 */
data class TypedQuery(val text: String = "", val cursor: Int = text.length) {
    init {
        require(cursor in 0..text.length) { "cursor $cursor is outside \"$text\"" }
    }

    /** What is left of the cursor — the caret is drawn between these two. */
    val before: String get() = text.substring(0, cursor)

    /** What is right of the cursor. */
    val after: String get() = text.substring(cursor)

    val isEmpty: Boolean get() = text.isEmpty()

    fun insert(character: Char): TypedQuery =
        TypedQuery(before + character + after, cursor + 1)

    /** Removes the character *before* the cursor. Does nothing at the start of the text. */
    fun backspace(): TypedQuery =
        if (cursor == 0) this else TypedQuery(text.removeRange(cursor - 1, cursor), cursor - 1)

    /**
     * Removes the character *after* the cursor, leaving the cursor where it is.
     *
     * The other half of backspace, and the reason both exist: with a cursor, fixing a letter in
     * the middle of a word otherwise means deleting everything after it and typing it again.
     */
    fun forwardDelete(): TypedQuery =
        if (cursor >= text.length) this else TypedQuery(text.removeRange(cursor, cursor + 1), cursor)

    /** Stops at the ends rather than wrapping: a cursor that reappears at the far end is lost. */
    fun left(): TypedQuery = if (cursor == 0) this else TypedQuery(text, cursor - 1)

    fun right(): TypedQuery = if (cursor >= text.length) this else TypedQuery(text, cursor + 1)

    fun cleared(): TypedQuery = TypedQuery()
}
