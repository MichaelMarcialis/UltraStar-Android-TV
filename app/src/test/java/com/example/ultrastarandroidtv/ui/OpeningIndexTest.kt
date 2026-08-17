package com.example.ultrastarandroidtv.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class OpeningIndexTest {

    private val library = listOf("doc-7years", "doc-9to5", "doc-bad", "doc-believer")

    @Test
    fun `opens on the song that was just sung`() {
        assertEquals(2, openingIndexFor(library, "doc-bad"))
    }

    @Test
    fun `opens at the beginning the first time, when nothing has been sung`() {
        assertEquals(0, openingIndexFor(library, null))
    }

    @Test
    fun `falls back to the beginning when the song is gone`() {
        // A rescan between one song and the next can remove the very song just sung. Opening on
        // nothing would leave the row with no focus at all, which on a remote is unnavigable.
        assertEquals(0, openingIndexFor(library, "doc-deleted"))
    }

    @Test
    fun `an empty library asks for the first card, which simply is not drawn`() {
        assertEquals(0, openingIndexFor(emptyList(), "doc-bad"))
    }
}
