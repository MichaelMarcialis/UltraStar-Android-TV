package com.example.ultrastarandroidtv.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncCalibrationTest {

    /** What the Shield actually measured: ~130 ms round trip through HDMI and back. */
    private val measured = SyncCalibration(totalLatencySeconds = 0.130)

    @Test
    fun `both offsets describe the past`() {
        // The commonest way to get this wrong is to add instead of subtract, which doubles the
        // error and makes every singer look impossibly early.
        assertTrue(measured.songTimeFor(10.0) < 10.0)
        assertTrue(measured.heardSongTimeFor(10.0) < 10.0)
    }

    @Test
    fun `scoring takes the whole round trip off`() {
        assertEquals(9.870, measured.songTimeFor(10.0), 1e-9)
    }

    @Test
    fun `the display only takes the output half off`() {
        // A reading has been through capture and analysis; what the singer hears has not.
        assertEquals(0.130 - SyncCalibration.CAPTURE_LATENCY_SECONDS, measured.outputLatencySeconds, 1e-9)
        assertEquals(
            10.0 - measured.outputLatencySeconds + measured.displayLeadSeconds,
            measured.heardSongTimeFor(10.0),
            1e-9,
        )
    }

    @Test
    fun `the display runs ahead of scoring, never behind`() {
        // Lyrics track the sound in the room; readings describe a voice that has also been
        // through the analysis window, so they are always the older of the two.
        assertTrue(measured.heardSongTimeFor(10.0) > measured.songTimeFor(10.0))
    }

    @Test
    fun `the display lead pushes what is drawn forward, not back`() {
        // The two audio latencies describe sound that has already happened, so they come off.
        // This one describes a frame that has not been seen yet, so it goes on. Getting the
        // sign wrong here would double the very lateness it exists to cancel.
        measured.displayLeadSeconds = 0.0
        val uncorrected = measured.heardSongTimeFor(10.0)

        measured.displayLeadSeconds = 0.05
        assertEquals(uncorrected + 0.05, measured.heardSongTimeFor(10.0), 1e-9)
    }

    @Test
    fun `the display lead does not touch scoring`() {
        // It corrects where a picture lands on a screen. A pitch reading never went near the
        // screen, so applying it there would score singers against a delay they never heard.
        val before = measured.songTimeFor(10.0)
        measured.displayLeadSeconds = 0.08

        assertEquals(before, measured.songTimeFor(10.0), 1e-9)
        measured.displayLeadSeconds = 0.0
    }

    @Test
    fun `the display lead defaults to what was dialled in on the TV`() {
        // Judged by eye and ear rather than measured by the app, so less precise than the round
        // trip — but a real observation on the real TV, which is what a default is for.
        assertEquals(0.040, SyncCalibration().displayLeadSeconds, 1e-12)
    }

    @Test
    fun `a measurement below this app's own share claims no output latency, not a negative one`() {
        assertEquals(0.0, SyncCalibration(totalLatencySeconds = 0.001).outputLatencySeconds, 1e-9)
    }

    @Test
    fun `the default is the measured round trip, not the calculable part of it`() {
        // Shipping the half-window as the default would start every song a tenth of a second
        // out on the only hardware this app runs on.
        val default = SyncCalibration()

        assertEquals(0.127, default.totalLatencySeconds, 1e-9)
        assertTrue(default.outputLatencySeconds > 0.1)
        assertTrue(
            "the measured total must exceed the part we calculate",
            SyncCalibration.DEFAULT_LATENCY_SECONDS > SyncCalibration.CAPTURE_LATENCY_SECONDS,
        )
    }
}
