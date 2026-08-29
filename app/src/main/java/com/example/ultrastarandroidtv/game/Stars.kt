package com.example.ultrastarandroidtv.game

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ultrastarandroidtv.score.MAX_STARS
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** How deep the points cut in, as a fraction of the outer radius. The classic five-pointed shape. */
private const val INNER_RATIO = 0.42f

/**
 * A row of stars saying how well the song went.
 *
 * Drawn rather than imported: `androidx.tv:tv-material` ships no star, and pulling in an icon
 * library for one ten-line path would be a dependency for a shape — the same call the loading bar
 * made.
 *
 * The unearned ones are drawn as faint sockets rather than left out, so five slots with three
 * filled reads as "three out of five" at a glance. Leaving them out would make three stars and
 * five stars look like the same result in different sizes.
 */
@Composable
fun StarRow(stars: Int, modifier: Modifier = Modifier, starSize: Dp = GameTheme.starSize) {
    Row(modifier = modifier) {
        repeat(MAX_STARS) { index ->
            Canvas(modifier = Modifier.padding(horizontal = 3.dp).size(starSize)) {
                drawStar(if (index < stars) GameTheme.starFilled else GameTheme.starEmpty)
            }
        }
    }
}

/** Kept separate so the row above stays a layout and this stays geometry. */
private fun DrawScope.drawStar(color: Color) {
    val radius = min(size.width, size.height) / 2f * 0.92f
    val centre = Offset(size.width / 2f, size.height / 2f)
    val path = Path()

    // Ten vertices alternating outer and inner, starting at the top — which is what makes it a
    // star standing up rather than one lying on its side.
    for (i in 0 until 10) {
        val angle = (-Math.PI / 2 + i * Math.PI / 5).toFloat()
        val r = if (i % 2 == 0) radius else radius * INNER_RATIO
        val x = centre.x + r * cos(angle)
        val y = centre.y + r * sin(angle)
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    drawPath(path, color)
}
