package com.example.ultrastarandroidtv.library

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Filing a name past its leading article.
 *
 * Reported from the sofa: "The Beatles", "The Cure", "The Monkees" and "The Weeknd" all queued up
 * under T, which tells you nothing about any of them. Every record shop and library catalogue files
 * past the article for exactly that reason.
 *
 * The risk is over-reach — a rule that eats the first word of names that only *look* like they
 * start with an article — so most of what is here is about what must be left alone.
 */
class FilingKeyTest {

    @Test
    fun `files past a leading article`() {
        assertEquals("Beatles", filingKey("The Beatles"))
        assertEquals("Sky Full Of Stars", filingKey("A Sky Full Of Stars"))
        assertEquals("Innocent Man", filingKey("An Innocent Man"))
    }

    @Test
    fun `the article may be written in any case`() {
        assertEquals("Monkees", filingKey("THE Monkees"))
        assertEquals("Weeknd", filingKey("the Weeknd"))
    }

    // -------------------------------------------------------------------------------------
    // What must be left alone
    // -------------------------------------------------------------------------------------

    /**
     * The case this would obviously get wrong. "a-ha" begins with an "a", and a rule that split on
     * anything but a space would file the band under H.
     */
    @Test
    fun `a-ha keeps its a`() {
        assertEquals("a-ha", filingKey("a-ha"))
    }

    @Test
    fun `a word that merely starts with an article is untouched`() {
        assertEquals("Theatre Of Tragedy", filingKey("Theatre Of Tragedy"))
        assertEquals("Anna", filingKey("Anna"))
        assertEquals("Another Brick In The Wall", filingKey("Another Brick In The Wall"))
    }

    /** An article anywhere but the front is part of the name. */
    @Test
    fun `only the leading word counts`() {
        assertEquals("Rage Against The Machine", filingKey("Rage Against The Machine"))
        assertEquals("Wall", filingKey("The Wall"))
    }

    /** Filing something under nothing is not filing. */
    @Test
    fun `a name that is only an article keeps it`() {
        assertEquals("The", filingKey("The"))
        assertEquals("A", filingKey("A"))
        assertEquals("The", filingKey("The   "))
    }

    @Test
    fun `blank stays blank`() {
        assertEquals("", filingKey(""))
        assertEquals("", filingKey("   "))
    }

    // -------------------------------------------------------------------------------------
    // What it does to a library
    // -------------------------------------------------------------------------------------

    /** The complaint, as a test: four bands under T becomes four bands under four letters. */
    @Test
    fun `the T pile is broken up`() {
        val bands = listOf("The Weeknd", "The Beatles", "The Cure", "Adele")

        assertEquals(
            listOf("Adele", "The Beatles", "The Cure", "The Weeknd"),
            bands.sortedBy { filingKey(it).lowercase() },
        )
        assertEquals(
            listOf('A', 'B', 'C', 'W'),
            bands.map { indexLetterOf(filingKey(it)) }.sorted(),
        )
    }
}
