package com.example.ultrastarandroidtv.pitch

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val RATE = 48_000
private const val WINDOW = 2048
private const val HOP = 1024

/** Comfortably over the 0.06 gate: RMS 0.21. */
private const val LOUD = 0.30

/**
 * Between the two gates on purpose: RMS 0.042, which is under the 0.06 needed to *start* singing
 * and over the 0.03 needed to carry on. Every hysteresis test turns on this one number.
 */
private const val QUIET = 0.06

/**
 * The loudness gate, which is the difference between scoring a note and scoring nothing.
 *
 * These are separate from `PitchTrackerTest` because they are not about finding a pitch — every
 * signal here is a clean sine that YIN reads perfectly. They are about *when* the tracker is
 * willing to look, which is what decides whether the beats at the start of a note are paid for.
 */
class PitchTrackerGateTest {

    // ---------------------------------------------------------------------------------------
    // Measuring loudness over the newest audio rather than the whole window
    // ---------------------------------------------------------------------------------------

    /**
     * The bug this fixes, in one test.
     *
     * Averaging loudness across the whole 2048-sample window means a window that is half silence
     * reads as half as loud, so a note is still "too quiet" well after the singer started it.
     * Measured against the real song library, that cost a soft-attack singer a quarter of every
     * beat — the beats at each note's start, which is exactly where a singer feels robbed.
     */
    @Test
    fun `a note's attack is not averaged away with the silence before it`() {
        val note = swell(hz = 220.0, leadSeconds = 0.05, attackSeconds = 0.06, peak = 0.12)

        val wholeWindow = firstVoiced(note, levelWindowSize = WINDOW)
        val newestOnly = firstVoiced(note, levelWindowSize = HOP)

        assertTrue("the old gate should hear it eventually", wholeWindow != null)
        assertTrue("the new gate should hear it", newestOnly != null)
        assertTrue(
            "newest-only should hear the attack sooner: was $newestOnly, whole window $wholeWindow",
            newestOnly!! < wholeWindow!!,
        )
    }

    /** A sustained note is the same loudness either way, so steady-state behaviour is untouched. */
    @Test
    fun `a note already under way reads the same whichever slice is measured`() {
        val steady = swell(hz = 220.0, leadSeconds = 0.0, attackSeconds = 0.0, peak = LOUD)

        val wholeWindow = readings(steady, levelWindowSize = WINDOW)
        val newestOnly = readings(steady, levelWindowSize = HOP)

        assertTrue(wholeWindow.all { it.voiced })
        assertTrue(newestOnly.all { it.voiced })
        assertEquals(
            wholeWindow.last().level.toDouble(),
            newestOnly.last().level.toDouble(),
            0.005,
        )
    }

    // ---------------------------------------------------------------------------------------
    // Two heights: one to start singing, a lower one to carry on
    // ---------------------------------------------------------------------------------------

    /**
     * The reason there are two gates at all. One threshold cannot both keep the other singer out
     * and decide whether this one is making a sound, and a real voice does not hold one loudness
     * for a whole phrase — it swells and falls away between syllables.
     */
    @Test
    fun `a voice already singing may drop below the starting gate and still count`() {
        val phrase = concat(
            tone(220.0, seconds = 0.30, amplitude = LOUD),
            tone(220.0, seconds = 0.30, amplitude = QUIET),
        )

        val heard = readings(phrase, minLevel = 0.06f, holdLevel = 0.03f)

        // The tail rather than "the second half": the one window straddling the change in
        // loudness sees a step no voice makes, and YIN is right to be unsure about it.
        val quietTail = heard.takeLast(8)

        assertTrue("expected readings in the quiet tail", quietTail.isNotEmpty())
        assertTrue(
            "a voice that has already started should keep counting when it softens",
            quietTail.all { it.voiced },
        )
    }

    /** The control: with one gate doing both jobs, the same quiet half scores nothing. */
    @Test
    fun `without the lower gate the same quiet singing is thrown away`() {
        val phrase = concat(
            tone(220.0, seconds = 0.30, amplitude = LOUD),
            tone(220.0, seconds = 0.30, amplitude = QUIET),
        )

        val heard = readings(phrase, minLevel = 0.06f, holdLevel = 0.06f)

        assertTrue(heard.takeLast(8).none { it.voiced })
    }

    /**
     * The half that must not regress. The lower gate is only ever *held open* by this singer, so
     * a voice heard faintly across the room — which is what the higher gate is for — still never
     * opens it in the first place.
     */
    @Test
    fun `a voice that never reaches the starting gate never starts`() {
        val faint = tone(220.0, seconds = 0.6, amplitude = QUIET)

        val heard = readings(faint, minLevel = 0.06f, holdLevel = 0.03f)

        assertTrue(heard.isNotEmpty())
        assertTrue("crosstalk must not be able to open the gate", heard.none { it.voiced })
    }

    /** A rest ends the phrase, so the next voice has to earn the higher gate again. */
    @Test
    fun `the lower gate expires after a rest`() {
        val phrase = concat(
            tone(220.0, seconds = 0.30, amplitude = LOUD),
            ShortArray((RATE * 0.4).toInt()),
            tone(220.0, seconds = 0.30, amplitude = QUIET),
        )

        val heard = readings(phrase, minLevel = 0.06f, holdLevel = 0.03f, holdSeconds = 0.15)

        assertTrue("the loud opening should be heard", heard.take(8).any { it.voiced })
        assertTrue(
            "quiet singing after a rest is a new phrase, and has to clear the higher gate",
            heard.takeLast(8).none { it.voiced },
        )
    }

    /** A new singer must not inherit the last one's open gate. */
    @Test
    fun `reset forgets that anyone was singing`() {
        val tracker = PitchTracker(
            sampleRate = RATE,
            minLevel = 0.06f,
            holdLevel = 0.03f,
            levelWindowSize = HOP,
        )
        push(tone(220.0, seconds = 0.30, amplitude = LOUD), tracker)
        tracker.reset()

        val after = push(tone(220.0, seconds = 0.30, amplitude = QUIET), tracker)

        assertTrue(after.isNotEmpty())
        assertTrue(after.none { it.voiced })
    }

    /**
     * Loudness alone is not singing. A gate held open by noise would let a hand over the
     * microphone lower the bar for whatever came next.
     */
    @Test
    fun `only a window that produced a pitch holds the gate open`() {
        val phrase = concat(
            hiss(seconds = 0.2, amplitude = 0.4),
            tone(220.0, seconds = 0.30, amplitude = QUIET),
        )

        val heard = readings(phrase, minLevel = 0.06f, holdLevel = 0.03f)

        assertTrue(heard.none { it.voiced })
    }

    // ---------------------------------------------------------------------------------------

    private fun readings(
        samples: ShortArray,
        minLevel: Float = 0.06f,
        holdLevel: Float = minLevel,
        levelWindowSize: Int = HOP,
        holdSeconds: Double = 0.15,
    ): List<PitchReading> = push(
        samples,
        PitchTracker(
            sampleRate = RATE,
            minLevel = minLevel,
            holdLevel = holdLevel,
            levelWindowSize = levelWindowSize,
            holdSeconds = holdSeconds,
        ),
    )

    /** Index of the first voiced reading, or null if the signal was never heard. */
    private fun firstVoiced(samples: ShortArray, levelWindowSize: Int): Int? =
        readings(samples, levelWindowSize = levelWindowSize)
            .indexOfFirst { it.voiced }
            .takeIf { it >= 0 }
}

/** Silence, then a linear swell to [peak], then steady — the shape of a sung note's attack. */
private fun swell(
    hz: Double,
    leadSeconds: Double,
    attackSeconds: Double,
    peak: Double,
    totalSeconds: Double = 0.6,
): ShortArray {
    val lead = (RATE * leadSeconds).toInt()
    val attack = (RATE * attackSeconds).toInt()
    return ShortArray((RATE * totalSeconds).toInt()) { n ->
        if (n < lead) {
            0
        } else {
            val i = n - lead
            val envelope = if (attack == 0) 1.0 else min(1.0, i.toDouble() / attack)
            (sin(2.0 * PI * hz * i / RATE) * peak * envelope * Short.MAX_VALUE).toInt().toShort()
        }
    }
}

/**
 * Loud, and genuinely aperiodic.
 *
 * It has to be real noise: a cheap stand-in built from a short repeating pattern is a *tone* with
 * a very high fundamental, and YIN finds one of its sub-multiples sitting inside the sung range
 * and reports it, confidently. Which is the opposite of what this fixture is for.
 */
private fun hiss(seconds: Double, amplitude: Double): ShortArray {
    val random = Random(4)
    return ShortArray((RATE * seconds).toInt()) {
        ((random.nextDouble() * 2.0 - 1.0) * amplitude * Short.MAX_VALUE).toInt().toShort()
    }
}

private fun tone(hz: Double, seconds: Double, amplitude: Double): ShortArray =
    ShortArray((RATE * seconds).toInt()) { n ->
        (sin(2.0 * PI * hz * n / RATE) * amplitude * Short.MAX_VALUE).toInt().toShort()
    }

private fun concat(vararg parts: ShortArray): ShortArray {
    val out = ShortArray(parts.sumOf { it.size })
    var at = 0
    parts.forEach { it.copyInto(out, at); at += it.size }
    return out
}

private fun push(samples: ShortArray, tracker: PitchTracker): List<PitchReading> {
    val buffer = ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
    samples.forEach { buffer.putShort(it) }
    buffer.position(0)
    val heard = mutableListOf<PitchReading>()
    tracker.process(buffer, samples.size * 2) { heard += it }
    return heard
}
