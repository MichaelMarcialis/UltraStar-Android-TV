package com.example.ultrastarandroidtv.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * How one stored record is read, and why a name has exactly one spelling.
 *
 * A record is matched against a *profile* name — that is what makes deleting somebody clear their
 * records and renaming somebody move them. The two stores wrote names differently for a while:
 * a profile was saved trimmed and a record was saved exactly as typed. One stray space was
 * therefore enough to make a record outlive the profile it named, unbeatable by the person it
 * belonged to and removable from nowhere in the app.
 */
class HighScoreRecordTest {

    @Test
    fun `a record splits into its points and its holder`() {
        assertEquals(8_000, pointsOf("8000:Mia"))
        assertEquals("Mia", holderOf("8000:Mia"))
    }

    @Test
    fun `a holder is trimmed on the way out, so a padded record can still be found`() {
        // The bug this pins: written by an older build, this record was invisible to both
        // forget() and rename(), which compare against a trimmed profile name.
        assertEquals("Mia", holderOf("8000: Mia "))
    }

    @Test
    fun `a name containing a colon keeps all of it`() {
        // Points are split at the *first* colon, so a name is never truncated at one of its own.
        assertEquals(8_000, pointsOf("8000:Mia:B"))
        assertEquals("Mia:B", holderOf("8000:Mia:B"))
    }

    @Test
    fun `nonsense reads as nothing rather than as a record`() {
        assertNull(pointsOf("Mia"))
        assertNull(holderOf("Mia"))
        assertNull(pointsOf(""))
        assertNull(holderOf(""))
        // A leading colon has no points before it, so there is nothing to compare or beat.
        assertNull(pointsOf(":Mia"))
        assertNull(holderOf(":Mia"))
        assertNull(pointsOf("lots:Mia"))
    }

    @Test
    fun `cleaning a name is what both stores agree on`() {
        assertEquals("Mia", cleanName("  Mia  "))
        assertEquals("Mia", cleanName("Mia"))
        assertEquals("", cleanName("   "))
    }

    @Test
    fun `a name longer than the limit is cut to it, the same way a profile is`() {
        // Both stores have to cut at the same place, or a long name would file a record under one
        // spelling and a profile under another.
        val long = "a".repeat(MAX_NAME_LENGTH + 5)
        assertEquals(MAX_NAME_LENGTH, cleanName(long).length)
        assertEquals(cleanName(long), cleanName(cleanName(long)))
    }

    @Test
    fun `a cleaned name is one a written record can be found by`() {
        // The two halves meeting: what record() writes is what forget() and rename() look for.
        val written = "8000:${cleanName("  Mia  ")}"
        assertEquals("Mia", holderOf(written))
        assertEquals(8_000, pointsOf(written))
    }
}
