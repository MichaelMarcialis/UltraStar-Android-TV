package com.example.ultrastarandroidtv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules the drawn keyboard's cursor obeys.
 *
 * Worth pinning rather than trusting to two composables: the same six edits are driven from the
 * song picker and from the Add-songs screen, and the interesting cases are all at the ends, where
 * a wrong sign is an index out of bounds in front of somebody holding a remote.
 */
class TypedQueryTest {

    @Test
    fun `a letter lands where the cursor is`() {
        assertEquals(TypedQuery("ab", 2), TypedQuery().insert('a').insert('b'))
        val middle = TypedQuery("ac", 1).insert('b')
        assertEquals("abc", middle.text)
        assertEquals(2, middle.cursor)
    }

    @Test
    fun `backspace takes the character before the cursor, and nothing at the start`() {
        assertEquals(TypedQuery("ac", 1), TypedQuery("abc", 2).backspace())
        assertEquals(TypedQuery("abc", 0), TypedQuery("abc", 0).backspace())
    }

    @Test
    fun `delete takes the character after the cursor, and nothing at the end`() {
        // The cursor stays put, which is the whole difference from backspace.
        assertEquals(TypedQuery("ac", 1), TypedQuery("abc", 1).forwardDelete())
        assertEquals(TypedQuery("abc", 3), TypedQuery("abc", 3).forwardDelete())
    }

    @Test
    fun `the cursor stops at both ends rather than wrapping`() {
        assertEquals(0, TypedQuery("abc", 0).left().cursor)
        assertEquals(3, TypedQuery("abc", 3).right().cursor)
        assertEquals(1, TypedQuery("abc", 0).right().cursor)
        assertEquals(2, TypedQuery("abc", 3).left().cursor)
    }

    @Test
    fun `the two halves are what the caret is drawn between`() {
        val query = TypedQuery("abcd", 2)
        assertEquals("ab", query.before)
        assertEquals("cd", query.after)
        assertEquals(query.text, query.before + query.after)
    }

    @Test
    fun `a query built from words alone puts the cursor after them`() {
        assertEquals(4, TypedQuery("abba").cursor)
        assertTrue(TypedQuery().isEmpty)
        assertTrue(TypedQuery("abba").cleared().isEmpty)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a cursor outside the text is refused rather than silently moved`() {
        TypedQuery("ab", 5)
    }
}
