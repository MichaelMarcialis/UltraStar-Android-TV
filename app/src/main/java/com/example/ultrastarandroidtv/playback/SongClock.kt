package com.example.ultrastarandroidtv.playback

private const val NANOS_PER_SECOND = 1_000_000_000.0

/**
 * Where the song is right now, readable from any thread at any instant.
 *
 * `ExoPlayer.getCurrentPosition()` can only be called from the thread that owns the player, and
 * updates in coarse steps. Pitch readings arrive ~47 times a second on a capture thread that
 * must not touch the player at all, and each one needs a song position to be scored against.
 *
 * So the owning thread [sample]s the player once a frame, and everyone else [positionAt]s,
 * extrapolating from the last sample with `System.nanoTime()`. Reads are lock-free: the whole
 * time base is one immutable object behind a single volatile field.
 *
 * **The clock never runs backwards while playing — at all, by any amount.** The scorer walks
 * its notes with a forward-only cursor, and anything drawn from a clock that steps back flashes.
 * An earlier version allowed backwards moves larger than a quarter of a second through, assuming
 * only a real seek could produce one; that assumption was wrong and cost a visible glitch. See
 * [sample]. Genuine seeks come through [reset] instead.
 *
 * The cost is that the clock tracks the upper edge of the player's jitter rather than its
 * middle — a bias of about the jitter amplitude, far below the latency being calibrated out
 * anyway. Corrections are published as [lastCorrectionSeconds] so the size of that jitter stays
 * a measurement rather than an assumption.
 */
class SongClock {

    private class Base(
        val positionSeconds: Double,
        val atNanos: Long,
        val speed: Double,
        val playing: Boolean,
    )

    @Volatile
    private var base = Base(0.0, 0L, 1.0, false)

    /**
     * How far the last [sample] found the clock from the player, in seconds. Positive means the
     * clock was running behind. Diagnostic only — nothing depends on it.
     */
    @Volatile
    var lastCorrectionSeconds: Double = 0.0
        private set

    /** Song position at [nowNanos], a `System.nanoTime()` reading. Safe from any thread. */
    fun positionAt(nowNanos: Long): Double {
        val b = base
        if (!b.playing) return b.positionSeconds
        val elapsed = ((nowNanos - b.atNanos) / NANOS_PER_SECOND).coerceAtLeast(0.0)
        return b.positionSeconds + elapsed * b.speed
    }

    /** True while the clock is advancing. */
    val isRunning: Boolean get() = base.playing

    /**
     * Feeds the player's own position in, from the thread that owns the player. [nowNanos] must
     * be read as close to [positionSeconds] as possible — the pair is what anchors the clock.
     */
    fun sample(positionSeconds: Double, nowNanos: Long, speed: Double, playing: Boolean) {
        val previous = base
        val error = positionSeconds - positionAt(nowNanos)
        lastCorrectionSeconds = error

        // Playing without interruption: the same song running at the same speed as last time.
        val continuous = playing && previous.playing && speed == previous.speed

        if (continuous && error < 0.0) {
            // **Never move backwards while playing, however far back the player claims to be.**
            //
            // This used to hold only for corrections smaller than a quarter of a second, on the
            // theory that a big backwards report had to be a real seek. It is not: ExoPlayer
            // occasionally reports a position a few hundred milliseconds behind — after a
            // decoder hiccup, at a buffer boundary — and adopting it rewound the drawn song for
            // exactly one frame. On a 60 Hz display that reads as the notes and lyrics flashing
            // or briefly doubling, which is precisely how it was reported from the sofa and
            // what a framebuffer recording confirmed.
            //
            // Real backwards movement only ever comes from a seek, and a seek arrives through
            // `onPositionDiscontinuity` → [reset], which is unconditional. So there is nothing
            // legitimate left for this path to serve.
            //
            // Holding the time base rather than rewriting it with the value it already yields
            // is also what keeps readers exactly monotonic: recomputing one instant from a new
            // anchor can land a bit lower in the last place, and a reader sees that as the song
            // stepping backwards.
            return
        }

        base = Base(positionSeconds, nowNanos, speed, playing)
    }

    /**
     * Forces the clock to [positionSeconds], for a seek or a fresh song — the one way to make
     * it jump backwards. Anything scoring against it has to be reset too.
     */
    fun reset(positionSeconds: Double, nowNanos: Long, playing: Boolean = false) {
        lastCorrectionSeconds = 0.0
        base = Base(positionSeconds, nowNanos, base.speed, playing)
    }
}
