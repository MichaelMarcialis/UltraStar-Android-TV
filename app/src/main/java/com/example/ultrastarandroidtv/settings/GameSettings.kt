package com.example.ultrastarandroidtv.settings

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import com.example.ultrastarandroidtv.game.DEFAULT_WINDOW_SECONDS
import com.example.ultrastarandroidtv.playback.SyncCalibration

private const val PREFS = "settings"
private const val KEY_LEAD = "display_lead_seconds"
private const val KEY_WINDOW = "window_seconds"
private const val KEY_MIC_THRESHOLD = "mic_threshold"

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

    /** How loud a voice must be before it counts. The lever against mics hearing each other. */
    var micThreshold by mutableFloatStateOf(
        prefs.getFloat(KEY_MIC_THRESHOLD, DEFAULT_MIC_THRESHOLD),
    )
        private set

    fun updateLead(seconds: Double) {
        displayLeadSeconds = seconds.coerceIn(SettingsRange.lead)
        prefs.edit().putFloat(KEY_LEAD, displayLeadSeconds.toFloat()).apply()
    }

    fun updateWindow(seconds: Double) {
        windowSeconds = seconds.coerceIn(SettingsRange.window)
        prefs.edit().putFloat(KEY_WINDOW, windowSeconds.toFloat()).apply()
    }

    fun updateMicThreshold(level: Float) {
        micThreshold = level.coerceIn(SettingsRange.micThreshold)
        prefs.edit().putFloat(KEY_MIC_THRESHOLD, micThreshold).apply()
    }

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
        const val DEFAULT_MIC_THRESHOLD = 0.06f
    }
}
