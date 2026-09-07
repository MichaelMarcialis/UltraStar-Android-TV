package com.example.ultrastarandroidtv.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the library's search will and will not forgive.
 *
 * Every "does match" here is a query somebody actually types on a directional pad, and every
 * "does not" is the failure the looseness has to be bought back from: a search that answers with
 * most of the library is worse than one that answers with nothing, because the count above the
 * keyboard stops meaning anything and there is nothing left to narrow.
 */
class FuzzyMatchTest {

    @Test
    fun `the key is lower case, unaccented, and punctuation is a word break`() {
        assertEquals("guns n roses", searchKey("Guns N' Roses"))
        assertEquals("a ha", searchKey("a-ha"))
        assertEquals("bjork", searchKey("Björk"))
        assertEquals("dont stop me now", searchKey("Don't Stop Me Now"))
        assertEquals("blue monday", searchKey("  Blue   Monday  "))
        assertEquals("", searchKey("!!!"))
    }

    @Test
    fun `plain containment still matches, which is all a short query gets`() {
        assertTrue(fuzzyMatches("Dancing Queen", "queen"))
        assertTrue(fuzzyMatches("Dancing Queen", "ing que"))
        assertTrue(fuzzyMatches("Dancing Queen", ""))
    }

    @Test
    fun `punctuation typed or left out reaches the same song`() {
        assertTrue(fuzzyMatches("Guns N' Roses", "guns n roses"))
        assertTrue(fuzzyMatches("Guns N' Roses", "gunsnroses"))
        assertTrue(fuzzyMatches("a-ha", "aha"))
        assertTrue(fuzzyMatches("a-ha", "a ha"))
        assertTrue(fuzzyMatches("Don't Stop Me Now", "dont stop"))
    }

    @Test
    fun `the words may arrive in any order`() {
        assertTrue(fuzzyMatches("The Beatles Yesterday", "beatles yesterday"))
        assertTrue(fuzzyMatches("The Beatles Yesterday", "yesterday beatles"))
    }

    @Test
    fun `a swapped pair of letters is one mistake, not two`() {
        // The commonest typo there is on a directional pad, and plain Levenshtein calls it two
        // edits — which a one-typo budget would refuse.
        assertTrue(editsWithin("beatles", "beatels", 1))
        assertTrue(fuzzyMatches("The Beatles", "beatels"))
    }

    @Test
    fun `a dropped or wrong letter is forgiven in a long enough word`() {
        assertTrue(fuzzyMatches("Yesterday", "yesterdy"))
        assertTrue(fuzzyMatches("Bohemian Rhapsody", "bohemain"))
    }

    @Test
    fun `every word of the query has to answer to something`() {
        // One word matching perfectly does not carry a word that matches nothing at all — which is
        // what stops "beatles <anything>" returning the whole Beatles catalogue.
        assertFalse(fuzzyMatches("The Beatles Yesterday", "beatles zzzzz"))
    }

    @Test
    fun `a two-letter query is never forgiven, only contained`() {
        assertTrue(fuzzyMatches("The Cure", "th"))
        // One edit from "the", and deliberately not a match: at this length an edit budget matches
        // most of a library.
        assertFalse(fuzzyMatches("The Cure", "qh"))
    }

    @Test
    fun `a different song is still a different song`() {
        assertFalse(fuzzyMatches("Dancing Queen", "waterloo"))
        assertFalse(fuzzyMatches("Yesterday", "yesterdays work"))
    }

    @Test
    fun `the edit budget is a budget`() {
        assertTrue(editsWithin("abcd", "abcd", 0))
        assertFalse(editsWithin("abcd", "abce", 0))
        assertTrue(editsWithin("abcd", "abce", 1))
        assertFalse(editsWithin("abcd", "azce", 1))
        assertTrue(editsWithin("abcd", "azce", 2))
        // A length difference bigger than the budget can never be closed.
        assertFalse(editsWithin("abcd", "abcdef", 1))
    }
}
