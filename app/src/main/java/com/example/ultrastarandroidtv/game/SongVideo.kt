package com.example.ultrastarandroidtv.game

import android.view.TextureView
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.requiredSize
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

/** When the picture is sampled for bars, and how many times. Spread out; see the effect below. */
private const val LETTERBOX_FIRST_MS = 2000L
private const val LETTERBOX_INTERVAL_MS = 2000L
private const val LETTERBOX_SAMPLES = 6

/**
 * How finely the frame is sampled.
 *
 * Each sampled row is one seventy-second of the height, so a bar has to be about 1.5 % of the
 * picture before it can be seen at all — which is roughly where a bar stops being visible on a
 * television anyway. The coarser 64x36 this started at could not resolve the 22-pixel bars that
 * several files in this library actually have.
 */
private const val SAMPLE_WIDTH = 128
private const val SAMPLE_HEIGHT = 72

/** Anything this dark counts as a black bar rather than a dark shot. */
private const val BAR_LUMA = 20

/**
 * How much of a line has to be dark for it to count as bar.
 *
 * Not all of it. A bar is black because it was encoded black, but compression leaves noise along
 * the edge where it meets the picture, and requiring every single pixel means one stray bright
 * one hides a bar a hundred and thirty pixels thick.
 */
private const val BAR_PURITY = 0.97f

/** Refuse to crop more than this; past it, the picture is dark rather than letterboxed. */
private const val MAX_CROP = 1.5f

/** Safety margin on a measured crop, so a coarsely sampled bar cannot leave a sliver behind. */
private const val CROP_MARGIN = 1.02f

/**
 * Works out how much of the picture is black bar, and returns the scale that removes it.
 *
 * Aspect ratio alone cannot answer this, and on this library it is not even close. Measured with
 * `ffmpeg cropdetect` over a sample of the card: about half the videos carry bars *inside* the
 * frame — 1920x1080 files holding 2.35:1 pictures with 130-pixel bands top and bottom, and one
 * holding a 4:3 picture with 278-pixel bands at the sides. Every dimension the player reports for
 * those files says 16:9. The only evidence is the pixels.
 *
 * Returns 1 when there is nothing to crop, null when the frame is too dark to judge, and never
 * more than [MAX_CROP] — a shot that opens on black would otherwise be mistaken for a letterbox
 * and the video zoomed to nothing.
 */
private fun measureLetterbox(view: TextureView): Float? {
    val width = SAMPLE_WIDTH
    val height = SAMPLE_HEIGHT
    val frame = runCatching { view.getBitmap(width, height) }.getOrNull() ?: return null

    fun dark(x: Int, y: Int): Boolean {
        val pixel = frame.getPixel(x, y)
        val luma = ((pixel shr 16 and 0xFF) * 299 + (pixel shr 8 and 0xFF) * 587 +
            (pixel and 0xFF) * 114) / 1000
        return luma <= BAR_LUMA
    }

    fun rowDark(y: Int) = (0 until width).count { dark(it, y) } >= width * BAR_PURITY
    fun columnDark(x: Int) = (0 until height).count { dark(x, it) } >= height * BAR_PURITY

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

    // A hair over what the measurement says: a bar can be a fraction of a sampled row thicker
    // than it looks, and a sliver of black at the edge is far more noticeable than one per cent
    // more crop.
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
    /** Called if the file will not play, so something else can take the screen. */
    onFailed: () -> Unit = {},
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
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    // A file this device cannot decode is not a reason to stare at a blank
                    // background for three minutes. Hand the screen to the visualiser.
                    onFailed()
                }

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
            // requiredSize, not size. `size` is clamped by the incoming constraints, so a view
            // deliberately sized past the screen edge — which is exactly what covering means —
            // silently snapped back to the screen, and a TextureView stretches its content to
            // whatever bounds it ends up with. Every video was being squashed to 16:9, and the
            // letterbox crop below could never take effect either, since it multiplies a size
            // that was being clamped away.
            modifier = Modifier.requiredSize(width, height).align(Alignment.Center),
        )
    }

    // Measure the letterbox once the picture is running, and crop it away.
    //
    // Several samples, and the **smallest** crop any of them asked for wins. A bar is in every
    // frame of the file, so a real one survives every sample; a dark sky at the top of one shot
    // does not, and taking the first answer would have zoomed the whole video for the rest of the
    // song on the strength of it. Applied as it goes rather than at the end, because bars left up
    // for twelve seconds while the evidence is gathered is the thing being fixed.
    LaunchedEffect(player) {
        delay(LETTERBOX_FIRST_MS)
        var smallest = Float.MAX_VALUE
        repeat(LETTERBOX_SAMPLES) {
            val measured = surface?.let(::measureLetterbox)
            if (measured != null && measured < smallest) {
                smallest = measured
                crop = measured
            }
            delay(LETTERBOX_INTERVAL_MS)
        }
    }
}
