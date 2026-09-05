package com.example.ultrastarandroidtv.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * Asks this Shield to drive the television at its highest refresh rate while the app is open.
 *
 * ## Why a *refresh rate* is the lever on the television's own lag
 *
 * The proper way to do this is HDMI's Auto Low Latency Mode, and it is unreachable here: Android
 * has the request (`preferMinimalPostProcessing`, API 30, this device's level exactly), the LG
 * honours it, and the Shield reports `allmSupported false` / `gameContentTypeSupported false` and
 * drops it before it reaches the wire — see `MainActivity`. There is no CEC route either;
 * `HdmiControlManager` needs a signature permission and there is no shell command behind it.
 *
 * What *is* reachable is the output mode, and it turns out to be worth more than it sounds. The
 * expensive half of a television's processing is motion interpolation, and interpolation has
 * nothing to do at 120 Hz — there is no gap between frames to invent one for — so a set fed 120 Hz
 * bypasses the slowest thing it does even outside game mode.
 *
 * ## What was measured
 *
 * Requesting the mode works: `dumpsys display` goes to `mActiveModeId=121`, 1920x1080 at 120 Hz,
 * through the AVR the Shield is actually plugged into. And the Shield's own presentation deadline
 * — its half of the pipeline, before the television sees anything — falls from **17.58 ms to
 * 9.23 ms**. That 8.35 ms is banked whatever the LG does with the rest.
 *
 * **The picture costs nothing.** Android already composites this whole app at 1920x1080 and
 * upscales to 4K on the way out (`mOverrideDisplayInfo` says so), so all that changes is which
 * box does the upscale — and the C1's scaler is not worse than the Shield's. No video on the card
 * is above 1080p either.
 *
 * ## And then it was measured during a song, and it is a net loss
 *
 * Reported from the sofa the evening after it shipped: the music video looked choppy. Measured on
 * the television, same build, same song, the setting the only difference — 50 s of *Thriller*
 * from `dumpsys gfxinfo`:
 *
 * | | frames | janky | frame p50 | GPU p50 |
 * |---|---|---|---|---|
 * | off (2160p60) | 2882 | **2 (0.07 %)** | **5 ms** | **2 ms** |
 * | on (1080p120) | 2877 | 2866 (99.6 %) | 31 ms | 16 ms |
 *
 * The same again with the visualiser instead of a video — 99.6 % janky, GPU 15 ms — so it is the
 * mode rather than anything about decoding a picture.
 *
 * **What is *not* happening is dropped frames.** `framestats` says the app is asked for a frame
 * every **16.67 ms** at 120 Hz, 119 times out of 119, and delivers each one — Android is running
 * the app at 60 fps and showing each frame for two display refreshes. The cadence is perfectly
 * regular.
 *
 * What *is* happening is a pipeline two frames deeper. A frame finishes **31 ms** after the vsync
 * it was drawn for against **5 ms** with the setting off, and the wait is in the render thread
 * between queueing a frame and starting the next — buffer back-pressure. So the mode **costs
 * about 25 ms of display latency to save the 8.35 ms of presentation deadline it was adopted
 * for**, which inverts the entire argument for it.
 *
 * It also explains the report exactly. The notes are drawn on the app's own steady 60 fps and
 * look the same either way; the *video* is not, because ExoPlayer snaps each decoded frame to a
 * display vsync — an 8.33 ms grid at 120 Hz — while the app samples the texture on its own
 * 16.67 ms one. A 24 fps video then lands on a wandering 2-or-3-frame cadence instead of a
 * steady one, which is what choppy looks like.
 *
 * ## So it defaults off
 *
 * Kept as a setting rather than deleted, because the measurement is about this chain — a Shield
 * through a Denon AVR into a C1 — and the code that makes it repeatable is worth more than the
 * dozen lines it costs. Turn it on and time a song if the television or the amplifier ever
 * changes. Nothing else in the app reads it.
 */
@Composable
fun PreferLowLatencyVideo(enabled: Boolean) {
    val activity = LocalContext.current.activity()
    DisposableEffect(activity, enabled) {
        val window = activity?.window ?: return@DisposableEffect onDispose { }
        val was = window.attributes.preferredDisplayModeId

        // The fastest mode there is, and among equals the largest. On this Shield that is the
        // only mode above 60 Hz — 1080p120 — because its HDMI is 2.0b and 4K tops out at 60.
        val fastest = activity.display?.supportedModes.orEmpty()
            .maxWithOrNull(
                compareBy(
                    { it.refreshRate },
                    { it.physicalWidth.toLong() * it.physicalHeight },
                ),
            )

        if (enabled && fastest != null) {
            window.attributes = window.attributes.apply { preferredDisplayModeId = fastest.modeId }
        }
        onDispose {
            // Back to whatever the system wanted, so leaving the app leaves the television alone.
            window.attributes = window.attributes.apply { preferredDisplayModeId = was }
        }
    }
}

/** The activity behind a composable's context, unwrapped by hand — see [PauseWhenBackgrounded]. */
private fun Context.activity(): Activity? {
    var context: Context? = this
    while (context != null) {
        if (context is Activity) return context
        context = (context as? ContextWrapper)?.baseContext
    }
    return null
}
