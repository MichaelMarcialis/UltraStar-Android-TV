package com.example.ultrastarandroidtv.score

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Turning a score into stars, and two scores into one.
 *
 * The star bands are a judgement rather than a measurement, so what is pinned here is the shape:
 * that the bands are equal fifths, that nothing but zero scores zero, and that nothing can score
 * more than five.
 */
class StarsTest {

    @Test
    fun `an untouched song earns nothing`() {
        assertEquals(0, starsFor(0.0))
    }

    @Test
    fun `anything at all earns the first star`() {
        assertEquals(1, starsFor(0.001))
        assertEquals(1, starsFor(0.20))
    }

    @Test
    fun `the bands are equal fifths`() {
        assertEquals(2, starsFor(0.21))
        assertEquals(2, starsFor(0.40))
        assertEquals(3, starsFor(0.41))
        assertEquals(3, starsFor(0.60))
        assertEquals(4, starsFor(0.61))
        assertEquals(4, starsFor(0.80))
        assertEquals(5, starsFor(0.81))
    }

    @Test
    fun `a perfect song earns five and no more`() {
        assertEquals(MAX_STARS, starsFor(1.0))
        assertEquals(MAX_STARS, starsFor(1.5))
    }

    @Test
    fun `a good real performance lands on four`() {
        // The measured figure for somebody singing well is around 70 %, which should read as
        // "you did that well" rather than as "you nearly failed".
        assertEquals(4, starsFor(0.71))
    }

    @Test
    fun `the stars a snapshot reports come from its points`() {
        val snapshot = ScoreSnapshot(
            notePoints = 7_000, goldenPoints = 500, lineBonus = 700,
            beatsHit = 0, beatsScored = 100,
        )
        // 8200 / 10000 -> the top band, even though the beat counts say nothing was hit.
        assertEquals(5, snapshot.stars)
    }

    @Test
    fun `a duet is marked out of the same ten thousand a solo is`() {
        val one = ScoreSnapshot(6_000, 0, 600, 90, 100)
        val two = ScoreSnapshot(8_000, 200, 800, 70, 80)

        val pair = combined(listOf(one, two))

        assertEquals("points are averaged", 7_000, pair.notePoints)
        assertEquals(100, pair.goldenPoints)
        assertEquals(700, pair.lineBonus)
        assertEquals("so the pair can never exceed a perfect solo", 7_800, pair.total)
    }

    @Test
    fun `but the beats are counted, because they happened`() {
        val pair = combined(listOf(ScoreSnapshot(0, 0, 0, 90, 100), ScoreSnapshot(0, 0, 0, 70, 80)))
        assertEquals(160, pair.beatsHit)
        assertEquals(180, pair.beatsScored)
    }

    @Test
    fun `nobody singing combines to nothing rather than crashing`() {
        assertEquals(ScoreSnapshot.EMPTY, combined(emptyList()))
    }
}
