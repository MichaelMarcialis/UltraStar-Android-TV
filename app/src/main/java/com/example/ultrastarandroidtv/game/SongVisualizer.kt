package com.example.ultrastarandroidtv.game

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.lerp
import com.example.ultrastarandroidtv.audio.SpectrumTap

/** How fast a peak cap falls, in fractions of the height per second. */
private const val PEAK_FALL_PER_SECOND = 0.55f

/** Longest step treated as a real frame, so a stall does not drop every cap to the floor. */
private const val MAX_STEP_SECONDS = 0.1f

/**
 * A spectrum analyser for songs that ship no video.
 *
 * Most of the library has a music video; a handful do not, and those were staring at a flat
 * background. This is the old Winamp idea and it has aged well for a reason — bars that move
 * with the music are legible at a glance, cost almost nothing to draw, and never compete with
 * the game for attention the way a video sometimes does.
 *
 * Bars rise from the bottom of the screen into the space the video would have occupied, so the
 * loudest part of the display is behind the track rather than across the lyrics. Each carries a
 * **peak cap** that falls slowly — the detail that makes a spectrum readable, because it holds
 * the shape of a moment just long enough for the eye to catch it.
 *
 * Colour runs the length of the spectrum from the first singer's cyan to the second's pink, so
 * the visualiser belongs to the same picture as the arrows rather than looking bolted on.
 */
@Composable
fun SongVisualizer(tap: SpectrumTap?, modifier: Modifier = Modifier) {
    if (tap == null) return

    val bands = remember(tap) { FloatArray(tap.bandCount) }
    val peaks = remember(tap) { FloatArray(tap.bandCount) }

    // Redrawn every frame. Read inside the draw pass so nothing recomposes.
    var frameNanos by remember { mutableLongStateOf(0L) }
    var lastNanos = remember { 0L }

    LaunchedEffect(tap) {
        while (true) {
            withFrameNanos { frameNanos = it }
        }
    }

    Canvas(modifier) {
        val now = frameNanos
        val step = if (lastNanos == 0L) 0f else ((now - lastNanos) / 1e9f).coerceIn(0f, MAX_STEP_SECONDS)
        lastNanos = now

        tap.copyInto(bands)

        val count = bands.size
        if (count == 0) return@Canvas

        val slot = size.width / count
        val barWidth = slot * 0.62f
        val gap = (slot - barWidth) / 2f
        val maxHeight = size.height * 0.78f
        val capHeight = size.height * 0.006f

        for (i in 0 until count) {
            // Caps rise instantly with the bar and sink on their own, which is what lets a
            // sharp transient stay visible after the bar behind it has already dropped.
            peaks[i] = maxOf(bands[i], peaks[i] - PEAK_FALL_PER_SECOND * step)

            val colour = lerp(
                GameTheme.playerColors[0],
                GameTheme.playerColors[1],
                i.toFloat() / (count - 1).coerceAtLeast(1),
            )

            val left = i * slot + gap
            val barHeight = bands[i] * maxHeight

            if (barHeight > 1f) {
                drawRoundRect(
                    color = colour.copy(alpha = 0.55f),
                    topLeft = Offset(left, size.height - barHeight),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(barWidth / 3f),
                )
            }

            val capY = size.height - peaks[i] * maxHeight
            if (peaks[i] > 0.01f) {
                drawRoundRect(
                    color = colour.copy(alpha = 0.9f),
                    topLeft = Offset(left, capY - capHeight),
                    size = Size(barWidth, capHeight),
                    cornerRadius = CornerRadius(capHeight),
                )
            }
        }
    }
}
