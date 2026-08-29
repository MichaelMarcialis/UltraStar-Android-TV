package com.example.ultrastarandroidtv.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Turning a measurement into a playback gain.
 *
 * The arithmetic is separated from the decoding on purpose: the decoding needs a device and a
 * codec, and this is the half that can actually be wrong in a way nobody would notice — a sign
 * error here makes quiet songs quieter and loud ones louder, which is the fault it exists to fix,
 * only worse.
 */
class LoudnessTest {

    private fun atDbfs(db: Double, peak: Float = 0.5f) =
        Loudness(dbToAmplitude(db), peak)

    @Test
    fun `a song already at the target is left alone`() {
        assertEquals(1f, gainFor(atDbfs(TARGET_RMS_DBFS)), 0.01f)
    }

    @Test
    fun `a quiet song is turned up`() {
        assertTrue(gainFor(atDbfs(TARGET_RMS_DBFS - 6.0)) > 1f)
    }

    @Test
    fun `a loud song is turned down`() {
        assertTrue(gainFor(atDbfs(TARGET_RMS_DBFS + 6.0)) < 1f)
    }

    /** Six dB is a factor of two, which is the one conversion worth pinning by hand. */
    @Test
    fun `six dB really is twice`() {
        assertEquals(2f, gainFor(atDbfs(TARGET_RMS_DBFS - 6.0206), peakCeiling = 1f), 0.01f)
    }

    @Test
    fun `a badly quiet recording is only lifted so far`() {
        // Past this it is a bad rip and the boost brings the tape hiss with it.
        // A tiny peak, so that the headroom limit is not the thing being measured here.
        val gain = gainFor(atDbfs(-40.0, peak = 0.02f), peakCeiling = 1f, peakOvershootDb = 0.0)
        assertEquals(dbToAmplitude(MAX_BOOST_DB), gain, 0.01f)
    }

    @Test
    fun `a brickwalled song is not turned down past the cut limit`() {
        assertEquals(dbToAmplitude(-MAX_CUT_DB), gainFor(atDbfs(-1.0, peak = 1f)), 0.01f)
    }

    /**
     * The interesting case, and the reason [Loudness] carries a peak at all: quiet on average with
     * one loud hit is exactly the shape of an old rip, and the boost has to be pulled back or the
     * hit clips.
     */
    @Test
    fun `a quiet song with a loud transient is not boosted into the ceiling`() {
        val headroomLimited = gainFor(atDbfs(TARGET_RMS_DBFS - 9.0, peak = 1f))
        val roomToSpare = gainFor(atDbfs(TARGET_RMS_DBFS - 9.0, peak = 0.3f))
        assertTrue("$headroomLimited should be held back below $roomToSpare", headroomLimited < roomToSpare)
        assertTrue("but still a boost", headroomLimited > 1f)
    }

    /** A file that would not decode gets no opinion imposed on it. */
    @Test
    fun `an unmeasurable file plays exactly as recorded`() {
        assertEquals(1f, gainFor(Loudness.UNKNOWN), 1e-6f)
    }

    @Test
    fun `the peak limit can never turn a boost into a cut`() {
        // A song that is already loud *and* already clipping wants turning down for loudness, not
        // turning down twice.
        val gain = gainFor(Loudness(dbToAmplitude(TARGET_RMS_DBFS), 1f))
        assertEquals(1f, gain, 0.01f)
    }

    @Test
    fun `soft clipping leaves ordinary levels untouched`() {
        for (sample in floatArrayOf(-0.5f, -0.1f, 0f, 0.3f, 0.8f)) {
            assertEquals(sample, softClip(sample), 1e-6f)
        }
    }

    @Test
    fun `soft clipping bends the top instead of chopping it`() {
        // The knee is asymptotic to full scale, so far-out samples land on it rather than past it.
        assertTrue("must stay in range", abs(softClip(4f)) <= 1f)
        assertTrue("must stay in range", abs(softClip(-4f)) <= 1f)
        assertTrue("and must still be most of the way up", abs(softClip(4f)) > 0.95f)
        assertTrue("must still be monotonic", softClip(1.2f) > softClip(1.0f))
        assertEquals("and symmetric", -softClip(1.5f), softClip(-1.5f), 1e-6f)
    }

    @Test
    fun `dB and amplitude are inverses`() {
        for (db in doubleArrayOf(-30.0, -12.0, -3.0, 0.0, 6.0)) {
            assertEquals(db, amplitudeToDb(dbToAmplitude(db)), 1e-4)
        }
    }
}
