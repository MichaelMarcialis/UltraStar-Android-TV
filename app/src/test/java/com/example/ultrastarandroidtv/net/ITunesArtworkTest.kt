package com.example.ultrastarandroidtv.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ITunesArtworkTest {

    // -----------------------------------------------------------------------------------------
    // Asking for a bigger copy
    // -----------------------------------------------------------------------------------------

    /**
     * The whole reason this class exists: USDB's own cover is 200x200 and looks it on a 4K set,
     * while the same artwork is a path segment away from being 1000x1000.
     */
    @Test
    fun `asks iTunes for a large copy of the same artwork`() {
        assertEquals(
            "https://is1-ssl.mzstatic.com/image/thumb/a/b/1000x1000bb.jpg",
            enlargeArtwork("https://is1-ssl.mzstatic.com/image/thumb/a/b/100x100bb.jpg"),
        )
    }

    @Test
    fun `only the size segment is rewritten`() {
        val url = "https://is1-ssl.mzstatic.com/image/thumb/Music126/v4/60x60/x.jpg/100x100bb.jpg"
        assertEquals(
            "https://is1-ssl.mzstatic.com/image/thumb/Music126/v4/60x60/x.jpg/600x600bb.jpg",
            enlargeArtwork(url, 600),
        )
    }

    @Test
    fun `a url in an unexpected shape is left alone rather than mangled`() {
        val url = "https://example.test/cover.png"
        assertEquals(url, enlargeArtwork(url))
    }

    // -----------------------------------------------------------------------------------------
    // Deciding whether a result is the right song
    // -----------------------------------------------------------------------------------------

    @Test
    fun `titles match past case, spacing and punctuation`() {
        assertTrue(sameTitle("Since U Been Gone", "since u been gone"))
        assertTrue(sameTitle("Knowing Me, Knowing You", "Knowing Me Knowing You"))
        assertTrue(sameTitle("7 Years", "7 Years"))
    }

    /** A longer official title still describes the same song. */
    @Test
    fun `a title contained in a longer one still matches`() {
        assertTrue(sameTitle("Take On Me", "Take On Me (2017 Acoustic)"))
    }

    @Test
    fun `a different song does not match`() {
        assertFalse(sameTitle("Hello", "Goodbye"))
        assertFalse(sameTitle("Believer", "Thunder"))
    }

    /**
     * The case that ruled out plain containment for artists: this card's chart calls it "KPop
     * Demon Hunters (Huntr/x)" and iTunes bills the same recording as a five-way credit. Neither
     * string contains the other, and the cover is right.
     */
    @Test
    fun `an artist billed differently still matches on a shared word`() {
        assertTrue(
            sameArtist(
                "KPop Demon Hunters (Huntr/x)",
                "HUNTR/X, EJAE, AUDREY NUNA, REI AMI & KPop Demon Hunters Cast",
            ),
        )
    }

    /** The failure this guard exists for: the same title by somebody else entirely. */
    @Test
    fun `a different artist does not match`() {
        assertFalse(sameArtist("Adele", "Lionel Richie"))
        assertFalse(sameArtist("Kelly Clarkson", "Imagine Dragons"))
    }

    /**
     * A shared word is enough on purpose, and this is the measurement that settled it. Requiring
     * *every* word to match was tried against all 77 folders on the card and lost seven of them —
     * every soundtrack, where the chart names the film and iTunes names the performer.
     */
    @Test
    fun `a soundtrack credit matches the performer it names`() {
        assertTrue(sameArtist("Disney's Moana (Dwayne Johnson)", "Dwayne Johnson"))
        assertTrue(sameArtist("Trolls (Anna Kendrick)", "Anna Kendrick"))
        assertTrue(sameArtist("A Minecraft Movie (Jack Black)", "Jack Black"))
    }

    /** Short names carry no word long enough to be evidence, so they fall back to containment. */
    @Test
    fun `short artist names still match themselves`() {
        assertTrue(sameArtist("U2", "U2"))
        assertTrue(sameArtist("a-ha", "a-ha"))
        assertFalse(sameArtist("U2", "Blur"))
    }

    // -----------------------------------------------------------------------------------------
    // Reading a reply
    // -----------------------------------------------------------------------------------------

    @Test
    fun `takes the artwork of a result that matches both halves`() {
        val json = """
            {"results":[
              {"artistName":"Kelly Clarkson","trackName":"Since U Been Gone",
               "artworkUrl100":"https://is1-ssl.mzstatic.com/image/thumb/a/b/100x100bb.jpg"}]}
        """.trimIndent()
        assertEquals(
            "https://is1-ssl.mzstatic.com/image/thumb/a/b/1000x1000bb.jpg",
            artworkFrom(json, "Kelly Clarkson", "Since U Been Gone"),
        )
    }

    /** iTunes leads with tribute albums and karaoke versions often enough to be worth walking. */
    @Test
    fun `walks past a result that is not the song asked for`() {
        val json = """
            {"results":[
              {"artistName":"The Karaoke Crew","trackName":"Beat It (Karaoke Version)",
               "artworkUrl100":"https://is1-ssl.mzstatic.com/image/thumb/wrong/100x100bb.jpg"},
              {"artistName":"Michael Jackson","trackName":"Beat It",
               "artworkUrl100":"https://is1-ssl.mzstatic.com/image/thumb/right/100x100bb.jpg"}]}
        """.trimIndent()
        assertEquals(
            "https://is1-ssl.mzstatic.com/image/thumb/right/1000x1000bb.jpg",
            artworkFrom(json, "Michael Jackson", "Beat It"),
        )
    }

    /** A wrong cover is worse than a soft one, so nothing is better than the closest thing. */
    @Test
    fun `settles for nothing rather than the nearest result`() {
        val json = """
            {"results":[
              {"artistName":"Somebody Else","trackName":"A Different Song",
               "artworkUrl100":"https://is1-ssl.mzstatic.com/image/thumb/a/100x100bb.jpg"}]}
        """.trimIndent()
        assertNull(artworkFrom(json, "Kelly Clarkson", "Since U Been Gone"))
    }

    @Test
    fun `an empty or broken reply is simply no cover`() {
        assertNull(artworkFrom("""{"resultCount":0,"results":[]}""", "A", "B"))
        assertNull(artworkFrom("not json at all", "A", "B"))
        assertNull(artworkFrom("{}", "A", "B"))
    }

    @Test
    fun `a result with no artwork is skipped rather than taken`() {
        val json = """
            {"results":[
              {"artistName":"a-ha","trackName":"Take On Me"},
              {"artistName":"a-ha","trackName":"Take On Me",
               "artworkUrl100":"https://is1-ssl.mzstatic.com/image/thumb/b/100x100bb.jpg"}]}
        """.trimIndent()
        assertEquals(
            "https://is1-ssl.mzstatic.com/image/thumb/b/1000x1000bb.jpg",
            artworkFrom(json, "a-ha", "Take On Me"),
        )
    }

    @Test
    fun `the search asks for one song rather than a whole album`() {
        val url = artworkSearchUrl("David Bowie", "China Girl")
        assertTrue(url.startsWith("https://itunes.apple.com/search?"))
        assertTrue(url.contains("entity=song"))
        assertTrue(url.contains("David+Bowie+China+Girl"))
    }

    // -----------------------------------------------------------------------------------------
    // USDB's own annotations
    // -----------------------------------------------------------------------------------------

    /**
     * Found on the real screen, not in a test. USDB marks a duet chart "[DUET]", and asking Apple
     * for "ABBA Gimme! Gimme! Gimme! (A Man After Midnight) [DUET]" returns Olivia Newton-John
     * and a punk covers band, with ABBA nowhere in the results. The song downloaded with USDB's
     * 200x200 thumbnail because of five characters that describe the chart rather than the song.
     */
    @Test
    fun `a chart's own tag is not part of the song's name`() {
        assertEquals(
            "Gimme! Gimme! Gimme! (A Man After Midnight)",
            withoutTags("Gimme! Gimme! Gimme! (A Man After Midnight) [DUET]"),
        )
        assertEquals("Under Pressure", withoutTags("Under Pressure [DUET]"))
    }

    /** Round brackets usually are the song, and dropping them throws away the identifying half. */
    @Test
    fun `round brackets are left alone`() {
        assertEquals(
            "You're Welcome (From Moana)",
            withoutTags("You're Welcome (From Moana)"),
        )
    }

    @Test
    fun `a name with no tag is unchanged`() {
        assertEquals("Since U Been Gone", withoutTags("Since U Been Gone"))
        assertEquals("ABBA", withoutTags("ABBA"))
    }

    @Test
    fun `the tag is gone from the query, not just from the comparison`() {
        val url = artworkSearchUrl("ABBA", "Gimme! Gimme! Gimme! (A Man After Midnight) [DUET]")
        assertFalse("the tag must never reach Apple", url.contains("DUET"))
        assertTrue(url.contains("ABBA"))
        assertTrue(url.contains("Midnight"))
    }

}
