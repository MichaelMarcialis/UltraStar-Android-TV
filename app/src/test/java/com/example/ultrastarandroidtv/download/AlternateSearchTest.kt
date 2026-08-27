package com.example.ultrastarandroidtv.download

import com.example.ultrastarandroidtv.net.Http
import com.example.ultrastarandroidtv.net.HttpReply
import com.example.ultrastarandroidtv.net.HttpRequest
import com.example.ultrastarandroidtv.net.YouTubeAudio
import com.example.ultrastarandroidtv.usdb.UsdbDetails
import com.example.ultrastarandroidtv.usdb.UsdbSearch
import com.example.ultrastarandroidtv.usdb.UsdbSession
import java.net.URLDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What is actually asked of USDB when looking for another version of a song.
 *
 * Its own file because it pins the *request*, not the matching — and that distinction is the whole
 * reason it exists. The artist matcher was taught to accept "Disney's Moana (Auli'i Cravalho)" and
 * "Disney's Moana (Alessia Cara)" as one act, with tests proving it, and the feature still could not
 * find that chart: the artist was **also** being sent to USDB, which filtered the alternative out on
 * the server before the matcher ever saw it. Every test passed and the thing did not work.
 */
class AlternateSearchTest {

    /** The bug, as a test: the artist must not narrow the search it is meant to be judged against. */
    @Test
    fun `searches by title alone, never by artist`() {
        val net = RecordingUsdb()

        alternatesFor(net).findFor(
            artist = "Disney's Moana (Auli'i Cravalho)",
            title = "How Far I'll Go",
        )

        assertEquals("How Far I'll Go", net.fields["title"])
        assertTrue(
            "sending the artist filters out the very charts the matcher exists to accept",
            net.fields["interpret"].isNullOrBlank(),
        )
    }

    /** USDB's own arrangement tag is not part of the name, so it is not part of the query either. */
    @Test
    fun `asks without the arrangement tag`() {
        val net = RecordingUsdb()

        alternatesFor(net).findFor(artist = "ABBA", title = "Gimme! Gimme! Gimme! [DUET]")

        assertEquals("Gimme! Gimme! Gimme!", net.fields["title"])
    }

    /** Nothing to go on, nothing asked: a blank name would match most of the site. */
    @Test
    fun `asks nothing when there is nothing to ask`() {
        val net = RecordingUsdb()

        alternatesFor(net).findFor(artist = "", title = "How Far I'll Go")
        alternatesFor(net).findFor(artist = "ABBA", title = "")

        assertTrue(net.fields.isEmpty())
    }

    private fun alternatesFor(net: Http) = AlternateVersions(
        search = UsdbSearch(UsdbSession(net, BASE)),
        details = UsdbDetails(UsdbSession(net, BASE)),
        youTube = YouTubeAudio(net),
    )

    /** Remembers the search form USDB was posted, and answers with nothing found. */
    private class RecordingUsdb : Http {
        var fields: Map<String, String> = emptyMap()

        override fun send(request: HttpRequest): HttpReply {
            val body = request.body?.decodeToString().orEmpty()
            if (body.isNotEmpty()) {
                fields = body.split("&").mapNotNull { pair ->
                    val name = pair.substringBefore('=', "")
                    if (name.isEmpty()) null
                    else name to URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
                }.toMap()
            }
            return HttpReply(200, EMPTY_RESULTS, emptyMap())
        }
    }

    private companion object {
        const val BASE = "https://usdb.test/index.php"

        /** A results page with no rows, which is all these tests need back. */
        const val EMPTY_RESULTS = "<html><body><table></table></body></html>"
    }
}
