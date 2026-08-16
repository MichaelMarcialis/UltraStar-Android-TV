package com.example.ultrastarandroidtv.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.ultrastarandroidtv.game.GameTheme

/** How much of the bar the moving chunk covers. */
private const val CHUNK = 0.3f

/**
 * An indeterminate progress bar, drawn rather than imported.
 *
 * `androidx.tv:tv-material` ships no progress indicator, and pulling in the phone Material
 * library for one shape would be a dependency for a rounded rectangle. Twenty lines of `Canvas`
 * costs nothing and stays inside this project's rule about what it depends on.
 *
 * Deliberately **indeterminate**: the song scan cannot report a percentage, because how many
 * songs there are is only known once the walk has finished. A bar that filled to a made-up
 * fraction would be a lie told smoothly; a sweep says "working" and the count beside it says how
 * much has been found, which between them is everything actually known.
 */
@Composable
fun LoadingBar(
    modifier: Modifier = Modifier,
    color: Color = GameTheme.playerColors[0],
) {
    val transition = rememberInfiniteTransition(label = "loading")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "sweep",
    )

    Canvas(
        modifier = modifier
            .width(360.dp)
            .height(6.dp)
            // The chunk is drawn past both ends so it enters and leaves rather than appearing.
            .clip(RoundedCornerShape(3.dp)),
    ) {
        drawRect(color.copy(alpha = 0.16f))

        val chunk = size.width * CHUNK
        drawRect(
            color = color,
            topLeft = Offset(phase * (size.width + chunk) - chunk, 0f),
            size = Size(chunk, size.height),
        )
    }
}
