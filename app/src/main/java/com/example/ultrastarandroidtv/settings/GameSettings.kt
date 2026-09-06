package com.example.ultrastarandroidtv.settings

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.ultrastarandroidtv.game.DEFAULT_WINDOW_SECONDS
import com.example.ultrastarandroidtv.playback.SyncCalibration

private const val PREFS = "settings"
private const val KEY_LEAD = "display_lead_seconds"
private const val KEY_WINDOW = "window_seconds"
private const val KEY_MIC_SOLO = "mic_threshold_solo"
private const val KEY_MIC_DUET = "mic_threshold_duet"
private const val KEY_DIFFICULTY = "difficulty"
private const val KEY_FILL_SCREEN = "fill_screen_video"

/** Bounds for the sliders, and the reason each one has the range it does. */
object SettingsRange {
    /** Up to a third of a second: beyond that a TV is broken rather than slow. */
    val lead = 0.0..0.30

    /** Narrower crowds nothing but shows almost no lookahead; wider crowds the lyrics. */
    val window = 1.0..6.0

    /**
     * Normalised RMS a window must reach to be treated as singing at all.
     *
     * The low end is the old default and hears everything in the room; the high end needs a
     * voice right on the microphone. This is the only lever available against crosstalk —
     * loudness is the only proxy these mics give for proximity.
     */
    val micThreshold = 0.01f..0.20f
}

/**
 * The handful of numbers a person is allowed to change, remembered across launches.
 *
 * Persisted for the same reason the song folder is: this app exists because UltraStar Play
 * forgot its settings on every launch, and re-dialling anything on a TV remote is miserable.
 *
 * Values are Compose state, so a screen that reads one recomposes when it moves; every setter
 * writes through to disk immediately, because there is no natural "save" moment on a TV and a
 * setting that survives only until the next reboot is worse than no setting at all.
 */
class GameSettings(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * How far ahead to draw, cancelling the TV's own display lag. See
     * [SyncCalibration.displayLeadSeconds] — this is the number dialled in from the sofa.
     */
    var displayLeadSeconds by mutableDoubleStateOf(
        prefs.getFloat(KEY_LEAD, SyncCalibration.DEFAULT_DISPLAY_LEAD_SECONDS.toFloat()).toDouble(),
    )
        private set

    /** Seconds of song visible across the track. Lookahead against legibility. */
    var windowSeconds by mutableDoubleStateOf(
        prefs.getFloat(KEY_WINDOW, DEFAULT_WINDOW_SECONDS.toFloat()).toDouble(),
    )
        private set

    /**
     * How loud a voice must be before it counts, **on your own**.
     *
     * Nothing else in the room is trying to sing, so this can sit low: the only competition is
     * the song itself coming back off the television.
     */
    var soloMicThreshold by mutableFloatStateOf(
        prefs.getFloat(KEY_MIC_SOLO, DEFAULT_SOLO_MIC_THRESHOLD),
    )
        private set

    /**
     * The same gate **with two people singing**, which is a different acoustic problem and so a
     * different number.
     *
     * Each microphone now hears the other singer as well as the television, and loudness is the
     * only proxy these microphones give for which mouth is nearest. Kept as its own setting
     * because one value cannot serve both: tuned for a solo it lets a duet score the wrong
     * person, and tuned for a duet it makes a solo singer work harder than they need to.
     * Remembering it per mode is also what stops anyone having to remember at all.
     */
    var duetMicThreshold by mutableFloatStateOf(
        prefs.getFloat(KEY_MIC_DUET, DEFAULT_DUET_MIC_THRESHOLD),
    )
        private set

    /**
     * How forgiving the judging is. See [Difficulty] — it is the only thing here that changes
     * what a performance is worth, and it changes what the notes look like at the same time.
     */
    var difficulty by mutableStateOf(Difficulty.byName(prefs.getString(KEY_DIFFICULTY, null)))
        private set

    /**
     * Whether a song's video fills the screen, cropping what will not fit.
     *
     * On by default, because it is what makes a background look like a background: most of the
     * videos in this library are 4:3 standard-definition rips and about half carry black bars
     * baked into the frame on top of that, so shown whole they are a small picture in a large
     * black surround. Filling the screen and measuring away the baked-in bars is the difference
     * between a video behind the song and a video sitting in a box.
     *
     * Off is offered because it is a taste, not a fact: cropping does throw away the top and
     * bottom (or the sides) of somebody else's framing, and a person who would rather see the
     * whole picture is not wrong. Off shows the video whole, letterboxed against black, at its
     * own aspect ratio.
     */
    var fillScreenVideo by mutableStateOf(prefs.getBoolean(KEY_FILL_SCREEN, true))
        private set

    /** The gate that applies to a game with [playerCount] singers in it. */
    fun micThresholdFor(playerCount: Int): Float =
        if (playerCount >= 2) duetMicThreshold else soloMicThreshold

    fun updateLead(seconds: Double) {
        displayLeadSeconds = seconds.coerceIn(SettingsRange.lead)
        prefs.edit().putFloat(KEY_LEAD, displayLeadSeconds.toFloat()).apply()
    }

    fun updateWindow(seconds: Double) {
        windowSeconds = seconds.coerceIn(SettingsRange.window)
        prefs.edit().putFloat(KEY_WINDOW, windowSeconds.toFloat()).apply()
    }

    fun updateFillScreenVideo(value: Boolean) {
        fillScreenVideo = value
        prefs.edit().putBoolean(KEY_FILL_SCREEN, value).apply()
    }

    fun updateDifficulty(value: Difficulty) {
        difficulty = value
        prefs.edit().putString(KEY_DIFFICULTY, value.name).apply()
    }

    fun updateSoloMicThreshold(level: Float) {
        soloMicThreshold = level.coerceIn(SettingsRange.micThreshold)
        prefs.edit().putFloat(KEY_MIC_SOLO, soloMicThreshold).apply()
    }

    fun updateDuetMicThreshold(level: Float) {
        duetMicThreshold = level.coerceIn(SettingsRange.micThreshold)
        prefs.edit().putFloat(KEY_MIC_DUET, duetMicThreshold).apply()
    }

    /**
     * The same dial as [micThreshold], the way round a person expects it: 0 to 1, where **1
     * hears the most**.
     *
     * The stored value is a gate — how loud a sound must be before it counts — so raising it
     * makes the microphone hear *less*. Exposing that directly under the word "sensitivity"
     * had the slider running backwards against the plain meaning of the word: a sensitive
     * microphone picks up more of the room, not less. The gate is what the audio code needs
     * and the sensitivity is what a person reasons about, so the two are kept apart and
     * converted in one place rather than left to whoever draws the screen.
     */
    val soloMicSensitivity: Float get() = sensitivityOf(soloMicThreshold)
    val duetMicSensitivity: Float get() = sensitivityOf(duetMicThreshold)

    fun updateSoloMicSensitivity(sensitivity: Float) =
        updateSoloMicThreshold(thresholdOf(sensitivity))

    fun updateDuetMicSensitivity(sensitivity: Float) =
        updateDuetMicThreshold(thresholdOf(sensitivity))

    companion object {
        /**
         * Well above `PitchTracker`'s own 0.01 noise floor.
         *
         * With two singers in one room and a TV playing the song, the mics hear each other and
         * they hear the speakers, and every one of those counts as somebody singing. Loudness
         * is the only proxy for proximity these microphones offer: a voice a few inches away is
         * far louder than the same voice across the room, so raising the bar is what makes a
         * mic mostly hear its own singer. It is a blunt instrument — sing quietly enough and it
         * ignores you too — which is why it is a slider rather than a constant.
         */
        /**
         * Wide open, because on your own there is no second singer to keep out.
         *
         * Measured rather than chosen: with the gate at its old 0.06 a real performance lost 17 %
         * of its beats to "too quiet", and at this setting that fell to 3 %. What is left over
         * for a solo singer to worry about is the television, which the singer is generally much
         * louder than.
         */
        val DEFAULT_SOLO_MIC_THRESHOLD = thresholdOf(1f)

        /**
         * Where the gate sat when it had to serve both modes at once, near enough.
         *
         * With two people in a room each microphone hears the other one, and the bar has to be
         * high enough that a voice across the room does not clear it. This is the number that was
         * arrived at by testing two singers together, kept for the case it was actually tuned
         * for.
         */
        val DEFAULT_DUET_MIC_THRESHOLD = thresholdOf(0.75f)

        /** Gate → sensitivity: a high gate hears little, so it comes out near zero. */
        fun sensitivityOf(threshold: Float): Float {
            val range = SettingsRange.micThreshold
            val span = range.endInclusive - range.start
            return ((range.endInclusive - threshold) / span).coerceIn(0f, 1f)
        }

        /** Sensitivity → gate. The exact inverse of [sensitivityOf]. */
        fun thresholdOf(sensitivity: Float): Float {
            val range = SettingsRange.micThreshold
            val span = range.endInclusive - range.start
            return (range.endInclusive - sensitivity.coerceIn(0f, 1f) * span)
                .coerceIn(range)
        }
    }
}
