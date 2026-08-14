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
 * **For scoring they collapse into one number**, because they sit on the same path. The singer
 * hears position `P` at `L_out` after the player emitted it, responds, and that response
 * becomes a reading `L_cap` later. So a reading available now describes song position
 * `playerPosition(now) - (L_out + L_cap)` — see [songTimeFor].
 *
 * **For anything drawn on screen the split does matter.** Lyrics and the pitch bar have to line
 * up with what the singer *hears*, which is only `L_out` behind the player — see
 * [heardSongTimeFor]. Rendering at the raw player position puts the display ahead of the sound
 * by the whole output latency, which on this hardware is over a tenth of a second: enough that
 * a singer following the screen is pushed measurably early, and then scored for it.
 *
 * The sign is the thing to get right: both describe the **past**, so both are subtracted.
 * Getting it backwards doubles the error instead of removing it, and looks like a singer who is
 * consistently, impossibly early.
 *
 * Only [captureLatencySeconds] is known from first principles. The output path has to be
 * measured on the actual TV, which is what `diagnostics/SyncCalibrationScreen.kt` does — it
 * measures the total, and the output half is what is left after taking capture off.
 */
class SyncCalibration(
    totalLatencySeconds: Double = DEFAULT_LATENCY_SECONDS,
    /** This app's own contribution, which is calculable rather than measurable. */
    val captureLatencySeconds: Double = CAPTURE_LATENCY_SECONDS,
) {

    /** Measured round trip, in seconds. Settings will persist this; for now it starts at the default. */
    @Volatile
    var totalLatencySeconds: Double = totalLatencySeconds

    /**
     * Everything between the player and the room: the measured round trip less the part this
     * app adds itself. What the singer is hearing lags the player by this much.
     */
    val outputLatencySeconds: Double
        get() = (totalLatencySeconds - captureLatencySeconds).coerceAtLeast(0.0)

    /** Song position described by a reading taken when the player was at [playerPositionSeconds]. */
    fun songTimeFor(playerPositionSeconds: Double): Double =
        playerPositionSeconds - totalLatencySeconds

    /**
     * Song position the singer is currently *hearing* — what lyrics and the pitch bar should be
     * drawn against, so the display agrees with the sound rather than with the decoder.
     */
    fun heardSongTimeFor(playerPositionSeconds: Double): Double =
        playerPositionSeconds - outputLatencySeconds

    companion object {
        /**
         * Half of `PitchTracker`'s 2048-sample window at 48 kHz — the age of the audio at the
         * centre of the window a reading describes. The one part of the round trip that is
         * calculated rather than measured.
         */
        const val CAPTURE_LATENCY_SECONDS: Double = 1024.0 / 48_000.0

        /**
         * Measured on the Shield into the LG OLED: 127 ms, from four calibration runs whose
         * medians landed within 3 ms of each other. Most of it is the Shield re-encoding to
         * E-AC3 for HDMI and the TV decoding it again.
         *
         * A measured default rather than a guess, because this app targets exactly one device
         * and one TV. Re-run the calibration if either changes — or if the Shield's audio
         * output is switched away from Dolby, which is most of this number.
         */
        const val DEFAULT_LATENCY_SECONDS: Double = 0.127
    }
}
