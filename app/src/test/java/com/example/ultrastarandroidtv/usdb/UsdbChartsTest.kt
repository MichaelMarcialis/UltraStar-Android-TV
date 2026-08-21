package com.example.ultrastarandroidtv.usdb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsdbChartsTest {

    // -----------------------------------------------------------------------------------------
    // The wait
    // -----------------------------------------------------------------------------------------

    /** Shaped exactly like the countdown USDB serves; the value was 24 seconds on 2026-08-20. */
    private val waitPage = """
        <html><body>
        Please wait <span id="timeleft">x</span> seconds. After this period you can download
        the TXT file.
        <form id="timeform" method="post" action="?link=gettxt&id=17720">
        <input type="hidden" name="wd" value="1"></form>
        <script type="text/javascript">
        time = 24;
        function wait()
        {
        if (time==0)
        document.getElementById('timeform').submit();
        else
        {
        document.getElementById('timeleft').innerHTML = time;
        time= time - 1;
        setTimeout("wait()", 1000);
        }
        }
        wait();
        </script>
        </body></html>
    """.trimIndent()

    @Test
    fun `reads the wait off usdb's own countdown`() {
        assertEquals(24, waitSecondsFrom(waitPage))
    }

    /**
     * The fallback must never be zero. If this regex stops matching, the app should wait too long
     * rather than start hammering a free community database on every single download -- which is
     * how the *user's* account gets blocked for something the app did.
     */
    @Test
    fun `an unreadable page waits the safe default, not zero`() {
        assertEquals(DEFAULT_WAIT_SECONDS, waitSecondsFrom("<html>no countdown here</html>"))
        assertTrue(DEFAULT_WAIT_SECONDS >= 24)
    }

    @Test
    fun `still finds the wait if the page is rearranged`() {
        assertEquals(12, waitSecondsFrom("<script>var x=1; time = 12; doSomethingElse();</script>"))
    }

    // -----------------------------------------------------------------------------------------
    // The chart
    // -----------------------------------------------------------------------------------------

    private val chartPage = """
        <html><body><textarea name="txt" rows="20">#ARTIST:David Bowie
        #TITLE:China Girl
        #MP3:David Bowie - China Girl.mp3
        #BPM:269.14
        #GAP:11030
        #VIDEO:v=_YC3sTbAPcU,co=china-girl-56996b9d96f22.jpg,bg=bowie-51214e77eed12.jpg
        : 0 3 31 Oh,
        - 40
        E</textarea></body></html>
    """.trimIndent()

    @Test
    fun `pulls the chart out of the page`() {
        val chart = chartFrom(chartPage)
        assertTrue(chart!!.startsWith("#ARTIST:David Bowie"))
        assertTrue(chart.contains("#VIDEO:v=_YC3sTbAPcU"))
        assertTrue(chart.trimEnd().endsWith("E"))
    }

    /** "Still waiting" and "here it is" are the same page but for the textarea. */
    @Test
    fun `the waiting page holds no chart`() {
        assertNull(chartFrom(waitPage))
    }

    @Test
    fun `a textarea that is not a chart is not a chart`() {
        assertNull(chartFrom("""<html><textarea>Write your comment...</textarea></html>"""))
        assertNull(chartFrom("""<html><textarea></textarea></html>"""))
    }

    @Test
    fun `decodes entities in the chart text`() {
        val page = """<html><textarea>#ARTIST:AC&amp;DC
        #TITLE:T.N.T.</textarea></html>"""
        assertTrue(chartFrom(page)!!.contains("AC&DC"))
    }

    // -----------------------------------------------------------------------------------------
    // Where the media came from
    // -----------------------------------------------------------------------------------------

    @Test
    fun `reads the meta tags out of a video header`() {
        val tags = metaTagsFrom("v=_YC3sTbAPcU,co=china-girl-569.jpg,bg=bowie-512.jpg")
        assertEquals("_YC3sTbAPcU", tags.videoId)
        assertEquals("china-girl-569.jpg", tags.coverFile)
        assertEquals("bowie-512.jpg", tags.backgroundFile)
    }

    /**
     * `a=` exists precisely because a chart's sound sometimes comes from a different upload than
     * its picture. Taking `v=` when `a=` is present downloads the wrong audio for the notes.
     */
    @Test
    fun `audio comes from a= when the chart names one`() {
        val tags = metaTagsFrom("a=AUDIOwins123,v=VIDEOonly456")
        assertEquals("AUDIOwins123", tags.audioSource)
    }

    @Test
    fun `audio falls back to the video when there is only one`() {
        assertEquals("VIDEOonly456", metaTagsFrom("v=VIDEOonly456").audioSource)
    }

    @Test
    fun `finds the video header inside a whole chart`() {
        val tags = metaTagsOf(chartFrom(chartPage)!!)
        assertEquals("_YC3sTbAPcU", tags.videoId)
        assertEquals("_YC3sTbAPcU", tags.audioSource)
    }

    @Test
    fun `a chart with no video header has no meta tags`() {
        val tags = metaTagsOf("#ARTIST:Someone\n#TITLE:Something\n: 0 1 2 la\nE")
        assertNull(tags.videoId)
        assertNull(tags.audioSource)
    }

    @Test
    fun `ignores junk among the meta tags`() {
        val tags = metaTagsFrom("v=abc,,broken,=novalue,co=x.jpg")
        assertEquals("abc", tags.videoId)
        assertEquals("x.jpg", tags.coverFile)
    }

    @Test
    fun `the medley tag is not mistaken for media`() {
        val tags = metaTagsFrom("v=abc,co=x.jpg,bg=y.jpg,medley=504-1253")
        assertEquals("abc", tags.videoId)
        assertEquals("x.jpg", tags.coverFile)
        assertEquals("y.jpg", tags.backgroundFile)
    }
}
