package com.example.ultrastarandroidtv.playback

import com.example.ultrastarandroidtv.pitch.DEFAULT_SAMPLE_RATE
import com.example.ultrastarandroidtv.pitch.DEFAULT_WINDOW_SIZE

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
     * How long after this app draws a frame the TV actually shows it.
     *
     * The third delay, and the one originally missed. Output and capture latency both describe
     * *sound*; this describes *light*, and it runs the other way — a frame drawn now is seen
     * later, so the song time it depicts has to be pushed **forward** to compensate. It is
     * therefore added where the other two are subtracted.
     *
     * It exists because a frame is composited, handed to the display pipeline and then processed
     * by the TV before any of it reaches an eye, and TVs do a great deal of processing unless
     * they are in game mode. Left uncorrected it shows up exactly as reported from the sofa: the
     * lyric is sung a moment before it reaches the line.
     *
     * Dialled from the Settings screen, because the only instrument that can measure it is a
     * person watching the TV and listening to the song at the same time.
     */
    @Volatile
    var displayLeadSeconds: Double = DEFAULT_DISPLAY_LEAD_SECONDS

    /**
     * Song position to draw, so that what appears on the TV lines up with what is coming out of
     * the speakers at the moment the singer sees it.
     *
     * The sound is [outputLatencySeconds] behind the player by the time it reaches the room, and
     * the picture is [displayLeadSeconds] behind this call by the time it reaches the screen.
     * Correcting only the first leaves the picture late by the second.
     */
    fun heardSongTimeFor(playerPositionSeconds: Double): Double =
        playerPositionSeconds - outputLatencySeconds + displayLeadSeconds

    companion object {
        /**
         * Half of `PitchTracker`'s 2048-sample window at 48 kHz — the age of the audio at the
         * centre of the window a reading describes. The one part of the round trip that is
         * calculated rather than measured.
         */
        /**
         * **Half the analysis window, not the hop.** It says where a reading's *centre* sits
         * relative to the audio's end, which is a property of the window alone — so it does not
         * move when the hop does. Written as a literal it was indistinguishable from the two
         * hop-derived latencies in `GameSession`, and halving the hop would have looked like it
         * ought to change this too.
         */
        const val CAPTURE_LATENCY_SECONDS: Double =
            DEFAULT_WINDOW_SIZE / 2.0 / DEFAULT_SAMPLE_RATE

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

        /**
         * Zero, and arrived at the same way the 40 ms it replaces was: by playing songs.
         *
         * It sat at 40 ms from an evening of dialling it up until the lyric met the sing line as
         * it was sung. Two things have happened since that make zero the better answer, and the
         * user's own verdict from the sofa after more play is that it feels better: the app is
         * driven at 120 Hz while it is open (`ui/LowLatencyVideo.kt`), which takes 8.35 ms off
         * the Shield's own presentation deadline and skips the television's motion interpolation
         * with it, and the arrow's other latency terms were re-measured and shrunk.
         *
         * Still a real setting with a real range, because it describes the *television* rather
         * than this app — a different set, or this one in a different picture mode, will want a
         * different number. It is judged by eye and ear, which is why it is a dial rather than a
         * constant.
         */
        const val DEFAULT_DISPLAY_LEAD_SECONDS: Double = 0.0
    }
}
