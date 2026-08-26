package com.example.ultrastarandroidtv.library

import com.example.ultrastarandroidtv.song.SongMetadata
import com.example.ultrastarandroidtv.song.UltraStarSong
import com.example.ultrastarandroidtv.song.VoicePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scanned library, and the two numbers screens depend on.
 *
 * Both exist because a plain flag is invisible to a composition already on the television:
 * downloads outlive the screen that starts them, so a song can land while the Songs grid is open.
 */
class SongLibraryCacheTest {

    @Test
    fun `holds a scan only for the folder it scanned`() {
        val cache = SongLibraryCache()
        assertFalse(cache.holds(null))

        cache.put(null, listOf(song("Anything")))
        assertFalse("a null folder is not a folder", cache.holds(null))
    }

    /**
     * The bug the revision exists for: the notice said a song had been added and the grid went on
     * not showing it, because `loaded` is a plain flag and nothing recomposed.
     */
    @Test
    fun `a change anybody is looking at is published`() {
        val cache = SongLibraryCache()
        val before = cache.revision

        cache.markChanged()

        assertEquals(before + 1, cache.revision)
    }

    /**
     * A run of work invalidates *quietly*, which is what stops a twenty-three song repair batch
     * starting a five-second scan of the whole card after each one of them.
     */
    @Test
    fun `going stale does not wake a screen on its own`() {
        val cache = SongLibraryCache()
        cache.put(null, listOf(song("Anything")))
        val before = cache.revision

        cache.markStale()

        assertEquals("a quiet invalidation is the point of having two", before, cache.revision)
        assertFalse("but the next visit still rescans", cache.holds(null))
    }

    /** What was already found stays readable, which is the whole reason for stale over clear. */
    @Test
    fun `marking stale keeps the songs it already had`() {
        val cache = SongLibraryCache()
        cache.put(null, listOf(song("Golden Years"), song("Starman")))

        cache.markStale()

        assertEquals(
            "the Add-songs screen reads these to say what is already yours",
            listOf("Golden Years", "Starman"),
            cache.songs.map { it.song.metadata.title },
        )
    }

    @Test
    fun `publishing a change also keeps them`() {
        val cache = SongLibraryCache()
        cache.put(null, listOf(song("Golden Years")))

        cache.markChanged()

        assertEquals(listOf("Golden Years"), cache.songs.map { it.song.metadata.title })
    }

    /** Clearing is the other thing, and it is for a different card. */
    @Test
    fun `clearing really does forget`() {
        val cache = SongLibraryCache()
        cache.put(null, listOf(song("Golden Years")))

        cache.clear()

        assertTrue(cache.songs.isEmpty())
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

        cache.put(null, listOf(song("Anything")))

        assertEquals("scanning is not invalidating", revision, cache.revision)
        assertTrue("but it is a new reading of the card", cache.generation > 0)
    }

    private fun song(title: String) = ScannedSong(
        song = UltraStarSong(
            metadata = SongMetadata(title = title, artist = "An Artist", mp3 = "a.mp3", bpm = 120.0),
            voiceParts = listOf(VoicePart(label = null, lines = emptyList())),
        ),
        folderId = "folder-$title",
        textId = "text-$title",
        folderName = "An Artist - $title",
        audioId = "audio",
        videoId = null,
        coverId = null,
        backgroundId = null,
    )
}
