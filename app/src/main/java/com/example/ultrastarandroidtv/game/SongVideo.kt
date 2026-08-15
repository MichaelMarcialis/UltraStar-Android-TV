package com.example.ultrastarandroidtv.game

import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * How far the video may drift from the song before it is nudged back.
 *
 * Generous on purpose. The video is decoration: nobody can see a tenth of a second of lip-sync
 * error on a music video, and every correction is a visible seek. Correcting little and late
 * looks far better than tracking perfectly.
 */
private const val MAX_DRIFT_SECONDS = 0.4

/** Drift is checked a few times a second rather than every frame; it accumulates slowly. */
private const val SYNC_INTERVAL_MS = 250L

/**
 * The song's music video, behind the game.
 *
 * **Its own player, and silent.** The obvious approach — play the `.mp4` and take the audio from
 * it too — would quietly invalidate everything: the notes are timed against the `.mp3`, the
 * 127 ms round trip was measured against that same path, and a video file's audio track is very
 * often a different master with a different offset. So the mp3 stays the single source of both
 * sound and truth, and this player is muted and told where to be.
 *
 * `#VIDEOGAP` says how long *after* the audio the video starts, so the video's position is the
 * song position minus that. It is in **seconds**, unlike `#GAP` which is in milliseconds — an
 * inconsistency in the UltraStar format itself, and one that cost this app a bug before video
 * was ever wired up.
 *
 * Sized to **cover** the screen rather than fit inside it: a letterboxed music video behind a
 * game reads as a mistake, and the edges being cropped costs nothing since there is a scrim over
 * the whole thing anyway.
 */
@Composable
fun SongVideo(
    videoUri: String?,
    videoGapSeconds: Double,
    songPosition: () -> Double,
    isPlaying: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    if (videoUri == null) return

    val context = LocalContext.current
    var aspect by remember { mutableFloatStateOf(16f / 9f) }

    val player = remember(videoUri) {
        ExoPlayer.Builder(context).build().apply {
            // The mp3 is the audio. A second stream of the same song, a few frames out, would
            // sound like a fault and would be scored against.
            volume = 0f
            repeatMode = Player.REPEAT_MODE_OFF
            addListener(object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (videoSize.width > 0 && videoSize.height > 0) {
                        aspect = videoSize.width * videoSize.pixelWidthHeightRatio /
                            videoSize.height
                    }
                }
            })
            setMediaItem(MediaItem.fromUri(videoUri))
            prepare()
        }
    }

    DisposableEffect(player) {
        onDispose { player.release() }
    }

    LaunchedEffect(player) {
        while (true) {
            delay(SYNC_INTERVAL_MS)

            val target = songPosition() - videoGapSeconds
            val playing = isPlaying()

            if (target < 0.0) {
                // Still in the song's lead-in, before the video is due to start.
                if (player.isPlaying) player.pause()
                continue
            }

            if (abs(player.currentPosition / 1000.0 - target) > MAX_DRIFT_SECONDS) {
                player.seekTo((target * 1000).toLong())
            }
            if (playing != player.isPlaying) {
                if (playing) player.play() else player.pause()
            }
        }
    }

    BoxWithConstraints(modifier) {
        val boxAspect = maxWidth / maxHeight
        val width = if (aspect > boxAspect) maxHeight * aspect else maxWidth
        val height = if (aspect > boxAspect) maxHeight else maxWidth / aspect

        AndroidView(
            factory = { SurfaceView(it).also(player::setVideoSurfaceView) },
            modifier = Modifier.size(width, height).align(Alignment.Center),
        )

        // Everything the singers actually need is thin, bright text over this. Community videos
        // are lit however they were lit, so the scrim has to assume the worst one in the
        // library rather than the average one.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(GameTheme.videoScrim),
        )
    }
}
