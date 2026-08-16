package com.example.ultrastarandroidtv.game

import android.view.TextureView
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
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

/** How long to let the picture settle before measuring it, and how many times to try. */
private const val LETTERBOX_CHECK_MS = 1500L
private const val LETTERBOX_ATTEMPTS = 4

/** Anything this dark counts as a black bar rather than a dark shot. */
private const val BAR_LUMA = 14

/** Refuse to crop more than this; past it, the picture is dark rather than letterboxed. */
private const val MAX_CROP = 1.5f

/** Safety margin on a measured crop, so a coarsely sampled bar cannot leave a sliver behind. */
private const val CROP_MARGIN = 1.02f

/**
 * Works out how much of the picture is black bar, and returns the scale that removes it.
 *
 * Aspect ratio alone cannot answer this. A 2:1 picture encoded into a 16:9 file carries its bars
 * inside the frame, and every dimension the player reports says 16:9 — the only way to know is
 * to look at the pixels. So one frame is sampled, small, once, a second or two in.
 *
 * Returns 1 when there is nothing to crop, and never more than [MAX_CROP]: a shot that happens
 * to open on black would otherwise be mistaken for a letterbox and the video zoomed to nothing.
 */
private fun measureLetterbox(view: TextureView): Float? {
    val width = 64
    val height = 36
    val frame = runCatching { view.getBitmap(width, height) }.getOrNull() ?: return null

    fun dark(x: Int, y: Int): Boolean {
        val pixel = frame.getPixel(x, y)
        val luma = ((pixel shr 16 and 0xFF) * 299 + (pixel shr 8 and 0xFF) * 587 +
            (pixel and 0xFF) * 114) / 1000
        return luma <= BAR_LUMA
    }

    fun rowDark(y: Int) = (0 until width).all { dark(it, y) }
    fun columnDark(x: Int) = (0 until height).all { dark(x, it) }

    var top = 0
    while (top < height / 2 && rowDark(top)) top++
    var bottom = 0
    while (bottom < height / 2 && rowDark(height - 1 - bottom)) bottom++
    var left = 0
    while (left < width / 2 && columnDark(left)) left++
    var right = 0
    while (right < width / 2 && columnDark(width - 1 - right)) right++

    frame.recycle()

    // A frame that is black all over is a fade, not a letterbox. Try again later.
    if (top + bottom >= height / 2 || left + right >= width / 2) return null

    val verticalScale = height.toFloat() / (height - top - bottom)
    val horizontalScale = width.toFloat() / (width - left - right)
    val needed = maxOf(verticalScale, horizontalScale)

    // A hair over what the measurement says. The sample is coarse — 64x36 — so a bar can be a
    // fraction of a sampled row thicker than it looks, and a sliver of black left at the edge
    // is far more noticeable than one per cent more crop.
    return if (needed <= 1.001f) 1f else (needed * CROP_MARGIN).coerceAtMost(MAX_CROP)
}

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
 * Sized to **cover** the screen rather than fit inside it, and then cropped further by whatever
 * black bars are baked into the file — see [measureLetterbox]. A letterboxed music video behind
 * a game reads as a mistake, and losing a few per cent of a decorative background does not.
 *
 * Played at full brightness. Contrast for the game comes from panels behind the game, not from
 * dimming the picture.
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

    /** Extra scale that crops away black bars baked into the file. 1 until measured. */
    var crop by remember(videoUri) { mutableFloatStateOf(1f) }
    var surface by remember(videoUri) { mutableStateOf<TextureView?>(null) }

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

    // Clipping here is what makes "cover" work: the video is deliberately sized larger than the
    // screen in one direction and the overflow is cut off.
    BoxWithConstraints(modifier.clipToBounds()) {
        val boxAspect = maxWidth / maxHeight
        val width = (if (aspect > boxAspect) maxHeight * aspect else maxWidth) * crop
        val height = (if (aspect > boxAspect) maxHeight else maxWidth / aspect) * crop

        // A TextureView rather than a SurfaceView. A SurfaceView is a separate compositor layer
        // that is not reliably clipped by its parent, so sizing it past the screen edge — which
        // is exactly what filling the screen requires — is not something it can be trusted to
        // do. A TextureView is an ordinary view and obeys the clip. It costs an extra copy per
        // frame, which the Shield does not notice.
        AndroidView(
            factory = {
                TextureView(it).also { view ->
                    player.setVideoTextureView(view)
                    surface = view
                }
            },
            modifier = Modifier.size(width, height).align(Alignment.Center),
        )
    }

    // Measure the letterbox once the picture is running, and crop it away.
    LaunchedEffect(player) {
        delay(LETTERBOX_CHECK_MS)
        repeat(LETTERBOX_ATTEMPTS) {
            val measured = surface?.let(::measureLetterbox)
            if (measured != null && measured > 1.001f) {
                crop = measured
                return@LaunchedEffect
            }
            if (measured != null) return@LaunchedEffect
            delay(LETTERBOX_CHECK_MS)
        }
    }
}
