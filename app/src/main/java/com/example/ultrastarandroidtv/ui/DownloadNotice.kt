package com.example.ultrastarandroidtv.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.example.ultrastarandroidtv.download.ANNOUNCEMENT_SECONDS
import com.example.ultrastarandroidtv.download.Downloads
import com.example.ultrastarandroidtv.game.GameTheme
import kotlinx.coroutines.delay

/**
 * What a download did, said wherever you happen to be.
 *
 * The point of a queue is that you can go and do something else while it works, and the cost of
 * that is nobody is looking at the list any more when a song lands. So the news comes to them:
 * one line, bottom right, gone after a few seconds.
 *
 * **It takes no focus and cannot be pressed.** A panel that appeared over a menu and stole the
 * cursor would move the selection out from under somebody mid-press, which on a remote is how you
 * end up in a screen you did not choose. It is a notice, not a button.
 *
 * Not drawn during a song. Downloads are held while anybody is singing, so one finishing then is
 * already unlikely — but a download that started before the song can still land, and a panel
 * appearing over the pitch track mid-phrase is exactly the sort of distraction this whole screen
 * layout is arranged to avoid. The announcement waits, and is read on the way out.
 */
@Composable
fun DownloadNotice(downloads: Downloads, visible: Boolean) {
    val announcement = downloads.announcement

    // The timer runs even while the notice is hidden, so a song does not come back to a stale
    // notice about something that finished three minutes ago.
    LaunchedEffect(announcement) {
        if (announcement == null) return@LaunchedEffect
        delay(ANNOUNCEMENT_SECONDS * 1_000L)
        downloads.dismissAnnouncement()
    }

    if (announcement == null || !visible) return

    val shown by animateFloatAsState(targetValue = 1f, label = "notice")

    Box(modifier = Modifier.fillMaxSize().padding(40.dp), contentAlignment = Alignment.BottomEnd) {
        Row(
            modifier = Modifier
                .graphicsLayer { alpha = shown }
                .widthIn(max = 560.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(GameTheme.chipBackground)
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // A colour rather than a word: "Added" and "Failed" are already the detail line, and
            // repeating them in a label would be the only two words on the panel that are twice.
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        if (announcement.good) GameTheme.playerColors[0] else GameTheme.sparkWarm,
                    ),
            ) {
                // Height comes from the row, so the bar is as tall as whatever is beside it.
                Text("", fontSize = 30.sp)
            }
            Spacer(Modifier.width(14.dp))

            Column {
                Text(
                    announcement.title,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = GameTheme.lyricActive,
                )
                Text(
                    announcement.detail,
                    fontSize = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = if (announcement.good) GameTheme.lyricIdle else GameTheme.sparkWarm,
                )
            }
        }
    }
}
