package com.example.ultrastarandroidtv.game

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.example.ultrastarandroidtv.audio.SpectrumTap
import com.example.ultrastarandroidtv.visual.VisualizerView

/**
 * What fills the screen for songs that ship no video.
 *
 * This started as a row of bars along the bottom, which was legible and completely uninteresting:
 * it moved with the music without ever looking like anything, and it lived in the half of the
 * screen the track already occupies. What was wanted was the other Winamp idea — MilkDrop, which
 * takes the whole screen and keeps changing.
 *
 * So it is now a real feedback visualiser: a low-resolution buffer that redraws *itself* every
 * frame slightly moved and slightly darker, with the current instant of sound drawn on top. The
 * trail is the picture. See `visual/VisualizerRenderer.kt` for the mechanism and
 * `visual/Preset.kt` for the eight looks it rotates through.
 *
 * **No third-party visualiser was used, and that is not stubbornness.** The obvious candidate is
 * projectM, the open-source MilkDrop engine, and it fails two of this project's rules at once: it
 * is LGPL rather than permissive, and it is not on Maven Central, so it would mean vendoring a
 * large C++ library into the repo along with thousands of preset files of assorted provenance.
 * The shaders here are a few hundred lines and belong to the project.
 */
@Composable
fun SongVisualizer(tap: SpectrumTap, modifier: Modifier = Modifier) {
    // The tap is fitted for every song but analyses nothing until asked. Asking here — and
    // unasking on the way out — means a song with a working video never pays for an FFT.
    DisposableEffect(tap) {
        tap.enabled = true
        onDispose { tap.enabled = false }
    }

    AndroidView(
        factory = { VisualizerView(it, tap) },
        modifier = modifier,
        onRelease = { it.stop() },
    )
}
