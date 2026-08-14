package com.example.ultrastarandroidtv.playback

import kotlin.math.abs

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
 * **The clock never runs backwards while playing.** A small backwards correction from the
 * player is treated as jitter and ignored, because the scorer walks its notes with a
 * forward-only cursor and a pitch bar that twitches backwards looks broken. The cost is that
 * the clock tracks the upper edge of the player's jitter rather than its middle — a bias of
 * about the jitter amplitude, far below the latency being calibrated out anyway. Corrections
 * are published as [lastCorrectionSeconds] so the size of that jitter is a measurement rather
 * than an assumption.
 *
 * @param snapSeconds correction big enough to mean a seek, a stall, or buffering rather than
 *   jitter. Those are honoured immediately, backwards or not.
 */
class SongClock(private val snapSeconds: Double = 0.25) {

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
        val extrapolated = positionAt(nowNanos)
        val error = positionSeconds - extrapolated
        lastCorrectionSeconds = error

        // Running, and the correction is small enough to be jitter rather than a seek or a
        // stall. Anything else — starting, stopping, a big jump — is taken at face value.
        val jitter = playing && previous.playing && abs(error) < snapSeconds

        if (jitter && error < 0.0 && speed == previous.speed) {
            // Backwards jitter: hold what we have. Leaving the time base alone, rather than
            // rewriting it with the value it already yields, is what makes readers exactly
            // monotonic — recomputing one instant from a new anchor can land a bit lower in
            // the last place, and a reader would see that as the song stepping backwards.
            return
        }

        val adopted = if (jitter && error < 0.0) extrapolated else positionSeconds
        base = Base(adopted, nowNanos, speed, playing)
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
