package com.example.ultrastarandroidtv.game

import android.graphics.Bitmap
import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
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
 * Size of the wash drawn behind the video, in pixels, before it is stretched over the screen.
 *
 * Tiny on purpose: stretching a 32x18 image across a 4K television *is* the blur, and it costs
 * one bilinear upscale rather than a shader. Larger starts to resolve edges, which is the one
 * thing this must not do — it is a glow, and anything you can make out in it competes with the
 * picture it sits behind.
 */
private const val AMBIENT_WIDTH = 32
private const val AMBIENT_HEIGHT = 18

/** What the frame is grabbed at before being averaged down. A little detail to average away. */
private const val AMBIENT_SOURCE_WIDTH = 64
private const val AMBIENT_SOURCE_HEIGHT = 36

/** How often the wash is re-read. Six times a second is well under what the eye tracks. */
private const val AMBIENT_INTERVAL_MS = 160L

/**
 * How much of a new sample is taken each time.
 *
 * The wash has to lag the picture heavily. A cut between two shots is instant, and a wash that
 * followed it exactly would flash the whole room; at this rate a cut takes about a second to
 * arrive, which reads as the light in the scene changing rather than as the screen blinking.
 */
private const val AMBIENT_BLEND = 0.16f

/**
 * Fraction of each edge of the frame the wash ignores.
 *
 * Videos in this library carry black bars *inside* the picture — 1920x1080 files holding a
 * 2.39:1 image with 138-pixel bands, measured with ffmpeg cropdetect over the card. Sampling
 * those would derive the colour of a scene from its letterbox and put a black glow behind a
 * bright shot. The middle of a frame is always picture.
 */
private const val AMBIENT_INSET = 0.18f

/** How bright the wash is drawn. It is a suggestion of the picture, not a second copy of it. */
private const val AMBIENT_ALPHA = 0.5f

/**
 * The soft glow behind a letterboxed video, in the colours of the video itself.
 *
 * Rebuilt in place every sample: one small bitmap, one accumulator, nothing allocated per frame.
 * The accumulator is kept apart from the pixels because the blend needs more precision than
 * eight bits — at [AMBIENT_BLEND] a step of one level would never round its way to the next one,
 * and the wash would stick a few levels short of the colour it is chasing.
 */
private class AmbientWash {
    private val accumulator = FloatArray(AMBIENT_WIDTH * AMBIENT_HEIGHT * 3)
    private val pixels = IntArray(AMBIENT_WIDTH * AMBIENT_HEIGHT)
    private var seeded = false

    /** The current wash, or null until a frame has been read. Compose state, so drawing follows. */
    var image by mutableStateOf<ImageBitmap?>(null)
        private set

    fun sample(view: TextureView) {
        val frame = runCatching {
            view.getBitmap(AMBIENT_SOURCE_WIDTH, AMBIENT_SOURCE_HEIGHT)
        }.getOrNull() ?: return

        val insetX = (AMBIENT_SOURCE_WIDTH * AMBIENT_INSET).toInt()
        val insetY = (AMBIENT_SOURCE_HEIGHT * AMBIENT_INSET).toInt()
        val usableWidth = AMBIENT_SOURCE_WIDTH - 2 * insetX
        val usableHeight = AMBIENT_SOURCE_HEIGHT - 2 * insetY

        for (y in 0 until AMBIENT_HEIGHT) {
            val sourceY = insetY + y * usableHeight / AMBIENT_HEIGHT
            for (x in 0 until AMBIENT_WIDTH) {
                val sourceX = insetX + x * usableWidth / AMBIENT_WIDTH
                val pixel = frame.getPixel(sourceX, sourceY)
                val at = (y * AMBIENT_WIDTH + x) * 3
                val red = (pixel shr 16 and 0xFF).toFloat()
                val green = (pixel shr 8 and 0xFF).toFloat()
                val blue = (pixel and 0xFF).toFloat()

                // The first frame is taken whole. Easing up from black would fade the room in
                // over a second at the start of every song, which looks like the video loading.
                if (!seeded) {
                    accumulator[at] = red
                    accumulator[at + 1] = green
                    accumulator[at + 2] = blue
                } else {
                    accumulator[at] += (red - accumulator[at]) * AMBIENT_BLEND
                    accumulator[at + 1] += (green - accumulator[at + 1]) * AMBIENT_BLEND
                    accumulator[at + 2] += (blue - accumulator[at + 2]) * AMBIENT_BLEND
                }

                pixels[y * AMBIENT_WIDTH + x] = (0xFF shl 24) or
                    (accumulator[at].toInt() shl 16) or
                    (accumulator[at + 1].toInt() shl 8) or
                    accumulator[at + 2].toInt()
            }
        }
        frame.recycle()
        seeded = true

        image = Bitmap.createBitmap(pixels, AMBIENT_WIDTH, AMBIENT_HEIGHT, Bitmap.Config.ARGB_8888)
            .asImageBitmap()
    }
}

/** Stretches the wash over the whole screen. The upscale is what turns 32x18 into a blur. */
private fun DrawScope.drawAmbient(wash: ImageBitmap) {
    drawImage(
        image = wash,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(wash.width, wash.height),
        dstOffset = IntOffset.Zero,
        dstSize = IntSize(size.width.toInt(), size.height.toInt()),
        alpha = AMBIENT_ALPHA,
        filterQuality = FilterQuality.High,
    )
}

/**
 * The song's music video, behind the game.
 *
 * **Its own player, and silent.** The obvious approach — play the mp4 and take the audio from it
 * too — would quietly invalidate everything: the notes are timed against the mp3, the 127 ms
 * round trip was measured against that same path, and a video file's audio track is very often a
 * different master with a different offset. So the mp3 stays the single source of both sound and
 * truth, and this player is muted and told where to be.
 *
 * VIDEOGAP says how long *after* the audio the video starts, so the video's position is the song
 * position minus that. It is in **seconds**, unlike GAP which is in milliseconds — an
 * inconsistency in the UltraStar format itself, and one that cost this app a bug before video was
 * ever wired up.
 *
 * **The picture is shown whole.** It used to be sized to *cover* the screen and then cropped
 * further by however much black bar could be measured in the frame — a lot of machinery aimed at
 * never showing a bar, and wrong about which cost was worse. A crop that guesses takes the top of
 * somebody's head off, and it guesses hardest on the videos that are hardest to measure. Most of
 * this library is standard-definition 4:3, so covering a 16:9 screen meant throwing away a
 * quarter of every one of those pictures for a whole song.
 *
 * The bars are filled with the video's own colours instead — a heavily blurred, heavily lagged
 * wash taken from the middle of the frame, the way YouTube does it. The screen stays full and
 * nothing is cut off. See [AmbientWash].
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
    var surface by remember(videoUri) { mutableStateOf<TextureView?>(null) }
    val wash = remember(videoUri) { AmbientWash() }

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

    // Keeps the wash following the picture. Reading a frame is a copy of 64x36 pixels, so it is
    // affordable several times a second; what makes it look right is the lag, not the rate.
    LaunchedEffect(player) {
        while (true) {
            delay(AMBIENT_INTERVAL_MS)
            surface?.let(wash::sample)
        }
    }

    BoxWithConstraints(modifier.clipToBounds()) {
        // Behind the picture, filling everything the picture does not.
        wash.image?.let { image ->
            Canvas(modifier = Modifier.fillMaxSize()) { drawAmbient(image) }
        }

        // Contain, not cover: whichever dimension runs out first decides, and the other follows
        // from the aspect ratio. Nothing is cut off.
        val boxAspect = maxWidth / maxHeight
        val width = if (aspect > boxAspect) maxWidth else maxHeight * aspect
        val height = if (aspect > boxAspect) maxWidth / aspect else maxHeight

        // A TextureView rather than a SurfaceView. A TextureView is an ordinary view and can be
        // read back a frame at a time, which is what the ambient wash needs; a SurfaceView is a
        // separate compositor layer with nothing to read from and no reliable clipping. It costs
        // an extra copy per frame, which the Shield does not notice.
        AndroidView(
            factory = {
                TextureView(it).also { view ->
                    player.setVideoTextureView(view)
                    surface = view
                }
            },
            // requiredSize, not size: `size` is clamped by the incoming constraints, and a
            // TextureView stretches its content to whatever bounds it ends up with — so a
            // clamped size silently squashes the picture rather than letterboxing it.
            modifier = Modifier.requiredSize(width, height).align(Alignment.Center),
        )
    }
}
