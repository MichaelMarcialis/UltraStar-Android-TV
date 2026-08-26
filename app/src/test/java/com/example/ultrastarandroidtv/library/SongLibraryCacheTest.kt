package com.example.ultrastarandroidtv.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scanned library, and the two numbers screens depend on.
 *
 * Both exist because a plain flag is invisible to a composition that is already on the television:
 * downloads outlive the screen that starts them, so a song can land while the Songs grid is open.
 */
class SongLibraryCacheTest {

    @Test
    fun `holds a scan only for the folder it scanned`() {
        val cache = SongLibraryCache()
        assertFalse(cache.holds(null))

        cache.put(null, emptyList())
        assertFalse("a null folder is not a folder", cache.holds(null))
    }

    /**
     * The bug this counter exists for: the notice said a song had been added and the grid went on
     * not showing it, because `loaded` is a plain flag and nothing recomposed.
     */
    @Test
    fun `invalidating the card is something a screen can notice`() {
        val cache = SongLibraryCache()
        val before = cache.revision

        cache.markStale()

        assertEquals(before + 1, cache.revision)
    }

    /** And what was already found stays readable, which is the whole point of stale over clear. */
    @Test
    fun `marking stale keeps the songs it already had`() {
        val cache = SongLibraryCache()
        cache.put(null, emptyList())
        val had = cache.songs

        cache.markStale()

        assertEquals(had, cache.songs)
    }

    /**
     * The two counters must not chase each other: a scan bumps [SongLibraryCache.generation] and an
     * invalidation bumps [SongLibraryCache.revision]. If a scan bumped the one its own effect is
     * keyed on, the screen would rescan for ever.
     */
    @Test
    fun `a scan does not look like an invalidation`() {
        val cache = SongLibraryCache()
        val revision = cache.revision

        cache.put(null, emptyList())

        assertEquals("scanning is not invalidating", revision, cache.revision)
        assertTrue("but it is a new reading of the card", cache.generation > 0)
    }
}
