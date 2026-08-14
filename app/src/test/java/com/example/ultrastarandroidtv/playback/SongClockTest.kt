package com.example.ultrastarandroidtv.playback

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val SECOND = 1_000_000_000L
private fun nanos(seconds: Double) = (seconds * SECOND).toLong()

class SongClockTest {

    @Test
    fun `holds still until it is told the song is playing`() {
        val clock = SongClock()

        clock.sample(positionSeconds = 0.0, nowNanos = 0L, speed = 1.0, playing = false)

        assertFalse(clock.isRunning)
        assertEquals(0.0, clock.positionAt(nanos(5.0)), 1e-9)
    }

    @Test
    fun `extrapolates between samples`() {
        val clock = SongClock()
        clock.sample(0.0, 0L, 1.0, playing = true)

        // No further samples: the clock carries on by itself, which is the whole point — the
        // capture thread asks far more often than the player is read.
        assertEquals(0.25, clock.positionAt(nanos(0.25)), 1e-6)
        assertEquals(1.0, clock.positionAt(nanos(1.0)), 1e-6)
    }

    @Test
    fun `follows the playback speed`() {
        val clock = SongClock()
        clock.sample(10.0, 0L, speed = 2.0, playing = true)

        assertEquals(12.0, clock.positionAt(nanos(1.0)), 1e-6)
    }

    @Test
    fun `freezes where it stopped when playback pauses`() {
        val clock = SongClock()
        clock.sample(0.0, 0L, 1.0, playing = true)
        clock.sample(2.0, nanos(2.0), 1.0, playing = false)

        assertEquals(2.0, clock.positionAt(nanos(9.0)), 1e-9)
        assertFalse(clock.isRunning)
    }

    @Test
    fun `takes forward corrections from the player`() {
        val clock = SongClock()
        clock.sample(0.0, 0L, 1.0, playing = true)

        // The player is 20 ms ahead of where the clock had got to.
        clock.sample(1.02, nanos(1.0), 1.0, playing = true)

        assertEquals(1.02, clock.positionAt(nanos(1.0)), 1e-6)
        assertEquals(0.02, clock.lastCorrectionSeconds, 1e-6)
    }

    @Test
    fun `ignores backwards jitter but still reports it`() {
        val clock = SongClock()
        clock.sample(0.0, 0L, 1.0, playing = true)

        // The player reports 8 ms behind the extrapolation. Stepping back would rewind the
        // pitch bar and confuse a scorer that cannot look backwards.
        clock.sample(0.992, nanos(1.0), 1.0, playing = true)

        assertEquals(1.0, clock.positionAt(nanos(1.0)), 1e-6)
        assertEquals(-0.008, clock.lastCorrectionSeconds, 1e-6)
    }

    @Test
    fun `honours a jump big enough to be a seek`() {
        val clock = SongClock()
        clock.sample(0.0, 0L, 1.0, playing = true)

        clock.sample(30.0, nanos(1.0), 1.0, playing = true)
        assertEquals(30.0, clock.positionAt(nanos(1.0)), 1e-6)

        // Backwards too — a seek to the start of the chorus is not jitter.
        clock.sample(5.0, nanos(2.0), 1.0, playing = true)
        assertEquals(5.0, clock.positionAt(nanos(2.0)), 1e-6)
    }

    @Test
    fun `reset moves the clock anywhere`() {
        val clock = SongClock()
        clock.sample(20.0, 0L, 1.0, playing = true)

        clock.reset(3.0, nanos(1.0), playing = true)

        assertEquals(3.0, clock.positionAt(nanos(1.0)), 1e-9)
        assertEquals(3.5, clock.positionAt(nanos(1.5)), 1e-6)
    }

    @Test
    fun `never runs backwards under a stream of jittery samples`() {
        val clock = SongClock()
        clock.sample(0.0, 0L, 1.0, playing = true)

        // A player whose reported position wobbles +-10 ms around the truth, sampled every
        // frame for ten seconds, and read between every pair of samples.
        val jitter = listOf(0.004, -0.009, 0.001, -0.006, 0.010, -0.002, -0.010, 0.007)
        var previous = 0.0
        var frame = 1

        while (frame <= 600) {
            val at = frame * 1.0 / 60.0
            clock.sample(at + jitter[frame % jitter.size], nanos(at), 1.0, playing = true)

            val midFrame = clock.positionAt(nanos(at + 0.008))
            assertTrue("went backwards at frame $frame: $previous -> $midFrame", midFrame >= previous)
            previous = midFrame
            frame++
        }

        // And it stayed honest: within the jitter band of the truth after ten seconds, rather
        // than drifting away from it.
        assertEquals(10.0, previous, 0.05)
    }

    @Test
    fun `stays monotonic when read from another thread while being sampled`() {
        // The real arrangement: the player thread samples, the capture thread reads. The
        // monotonicity guarantee is worth nothing if it only holds single-threaded.
        val clock = SongClock()
        val stop = AtomicBoolean(false)
        val failure = AtomicReference<String?>(null)

        clock.sample(0.0, System.nanoTime(), 1.0, playing = true)

        // A nanosecond of slack: re-anchoring the time base recomputes the same instant from a
        // different origin, so the last bit of the result can move. The guarantee is that the
        // song never steps backwards, not that the arithmetic is bit-identical.
        val tolerance = 1e-9

        val reader = Thread {
            var previous = -1.0
            while (!stop.get()) {
                val now = clock.positionAt(System.nanoTime())
                if (now < previous - tolerance) {
                    failure.compareAndSet(null, "read $now after $previous")
                    return@Thread
                }
                previous = maxOf(previous, now)
            }
        }
        reader.start()

        val startNanos = System.nanoTime()
        val jitter = listOf(0.004, -0.009, 0.001, -0.006, 0.010, -0.002)
        repeat(3_000) { i ->
            val elapsed = (System.nanoTime() - startNanos) / 1e9
            clock.sample(elapsed + jitter[i % jitter.size], System.nanoTime(), 1.0, true)
        }

        stop.set(true)
        reader.join(5_000)
        assertNull(failure.get(), failure.get())
    }
}
