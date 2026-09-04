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
 * ## Why it is a setting, and why it defaults on
 *
 * On, because the whole point is that a manual picture-mode switch on the television is a thing
 * anybody would stop doing by the second evening. Off, available, because changing the output
 * mode makes the link renegotiate — a second or two of black at launch and again on the way out,
 * longer through an AVR — and because how much the television really saves cannot be measured
 * from in here. That last part is now answerable from the sofa: dial the display lead on the
 * D-pad during a song with this on and with it off, and the difference between the two numbers is
 * what the television was spending.
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
