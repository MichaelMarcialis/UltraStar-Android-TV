package com.example.ultrastarandroidtv.playback

/**
 * How far behind the player's clock the singer's captured voice runs.
 *
 * There are two delays between the player reporting a position and a pitch reading describing
 * what the singer did about it:
 *
 *  - **Output.** The player's position tracks audio handed to the HAL, not air. HDMI to a TV
 *    adds a display's worth of buffering on top, and TVs are notoriously bad at this.
 *  - **Capture and analysis.** USB buffering plus a 2048-sample YIN window: a reading describes
 *    audio centred half a window before it arrives.
 *
 * They collapse into **one number**, because they sit on the same path. The singer hears
 * position `P` at `L_out` after the player emitted it, responds, and that response becomes a
 * reading `L_cap` later. So a reading available now describes song position
 * `playerPosition(now) - (L_out + L_cap)` — one subtraction, and the split between the two
 * never matters.
 *
 * The sign is the thing to get right: readings describe the **past**, so the offset is always
 * subtracted. Getting it backwards doubles the error instead of removing it, and looks like a
 * singer who is consistently, impossibly early.
 *
 * The default is the half-window, which is the only part known from first principles; the
 * output path has to be measured on the actual TV. `diagnostics/SyncCalibrationScreen.kt`
 * measures the total by playing tones at known song positions and listening for them.
 */
class SyncCalibration(totalLatencySeconds: Double = DEFAULT_LATENCY_SECONDS) {

    /** Measured round trip, in seconds. Settings will persist this; for now it starts at the default. */
    @Volatile
    var totalLatencySeconds: Double = totalLatencySeconds

    /** Song position described by a reading taken when the player was at [playerPositionSeconds]. */
    fun songTimeFor(playerPositionSeconds: Double): Double =
        playerPositionSeconds - totalLatencySeconds

    companion object {
        /**
         * Half of `PitchTracker`'s 2048-sample window at 48 kHz — the age of the audio at the
         * centre of the window a reading describes. Everything else needs measuring.
         */
        const val DEFAULT_LATENCY_SECONDS: Double = 1024.0 / 48_000.0
    }
}
