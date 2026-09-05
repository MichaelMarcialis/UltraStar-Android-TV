package com.example.ultrastarandroidtv.game

import android.view.TextureView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
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
 * How finely the frame is sampled when looking for bars.
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
 * How many frames have to be judged before the crop is believed, and how far apart.
 *
 * Packed close together rather than spread across the song, and that is the whole change from
 * the version this replaces. Every one of these happens behind a black screen, so the interval
 * paces nothing anybody can see — it only has to be long enough that consecutive samples are
 * different frames rather than the same one read twice.
 *
 * Eight at a fifth of a second is 1.6 s of picture: *more* samples than the six this used to
 * take, in an eighth of the time. Spreading them was never what the sampling needed. It was
 * there because the crop was applied as the evidence arrived, and the fault everybody saw was a
 * video that had already been on screen for seconds suddenly zooming.
 */
private const val CROP_SAMPLES = 8
private const val CROP_SAMPLE_INTERVAL_MS = 200L

/**
 * Longest the screen may stay black waiting for a measurable frame.
 *
 * A video that opens on a fade from black gives no usable reading at all, and refusing to give
 * up would leave a song playing over nothing. Past this, whatever has been measured is used —
 * usually nothing, which means no crop, which is the safe answer anyway.
 */
private const val CROP_DEADLINE_MS = 4_000L

/**
 * How far one sampled pixel must move before it counts as having moved at all.
 *
 * Deliberately tiny, because the question is not "how much motion" but "any at all". A still
 * image decodes to the *same* frame every time, so a photograph held under the audio reads as
 * exactly zero changed pixels; this only has to survive the odd bit of dither in the read-back.
 */
private const val MOTION_LEVELS = 3

/** How much of the frame has to move for the picture to count as a moving picture. */
private const val MOTION_SHARE = 0.002f

/**
 * How many readable samples "nothing moved" needs before it is a verdict rather than a guess.
 *
 * More than the crop needs, and that is deliberate: plenty of real music videos open on a held
 * title card, and calling one of those a photograph would spend the whole song on the visualiser.
 * At a fifth of a second apiece this is 2.4 s of picture — comfortably longer than a title shot
 * and comfortably inside [CROP_DEADLINE_MS].
 *
 * It costs a real video nothing, because the loop stops watching the moment anything moves, which
 * for a moving picture is the second sample. Only a still image ever pays for the whole window.
 *
 * Only frames that could actually be measured count towards it, so a video opening on black
 * spends samples waiting rather than being called a photograph.
 */
private const val MOTION_MIN_SAMPLES = 12

/**
 * How long the picture takes to arrive, and to leave.
 *
 * The fade exists to hide the measurement rather than for its own sake, so in is brisk. Out is
 * slower because it has nothing to hide and is only there to stop the picture vanishing on a
 * single frame.
 */
private const val FADE_IN_MILLIS = 700
private const val FADE_OUT_MILLIS = 1_100

/** How long before the video's own end the fade to black begins. */
private const val FADE_OUT_LEAD_SECONDS = 1.2

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
 * and the video zoomed to nothing. Null is not a failure: it is what makes an opening fade from
 * black cost a sample rather than produce a confident wrong answer.
 */
private fun measureLetterbox(luma: IntArray): Float? {
    val width = SAMPLE_WIDTH
    val height = SAMPLE_HEIGHT

    fun dark(x: Int, y: Int): Boolean = luma[y * width + x] <= BAR_LUMA

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
 * Reads the picture into [luma] as coarse greyscale, or false when there is no frame to read.
 *
 * One `getPixels` for the whole sample rather than a `getPixel` per look: the bar search asks
 * about most pixels several times over, and the motion test wants the frame kept anyway.
 */
private fun readFrame(view: TextureView, pixels: IntArray, luma: IntArray): Boolean {
    val frame = runCatching { view.getBitmap(SAMPLE_WIDTH, SAMPLE_HEIGHT) }.getOrNull()
        ?: return false
    frame.getPixels(pixels, 0, SAMPLE_WIDTH, 0, 0, SAMPLE_WIDTH, SAMPLE_HEIGHT)
    frame.recycle()
    for (i in pixels.indices) {
        val pixel = pixels[i]
        luma[i] = ((pixel shr 16 and 0xFF) * 299 + (pixel shr 8 and 0xFF) * 587 +
            (pixel and 0xFF) * 114) / 1000
    }
    return true
}

/** Whether two readings of the picture are different pictures. */
private fun moved(before: IntArray, after: IntArray): Boolean {
    val needed = (before.size * MOTION_SHARE).toInt().coerceAtLeast(1)
    var changed = 0
    for (i in before.indices) {
        if (abs(before[i] - after[i]) >= MOTION_LEVELS) {
            changed++
            if (changed >= needed) return true
        }
    }
    return false
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
 * **Sized to cover the screen, and then cropped by whatever black bars are baked into the file**
 * — see [measureLetterbox]. A letterboxed music video behind a game reads as a mistake; losing a
 * few per cent of a decorative background does not. This went the other way for two days: the
 * picture was shown whole and the bars filled with a blurred wash of the video's own colours, the
 * way YouTube does it. That is gone, along with `AmbientWash`, and git has it if the argument ever
 * turns back — the bars were the thing being complained about, and filling them prettily is not
 * the same as not having them.
 *
 * **Nothing is shown until the crop is settled.** Measuring needs real frames, so the answer
 * cannot be known before the video starts, and applying it as the evidence arrived was the part
 * everybody saw: the picture zoomed a couple of seconds in, every song. The measurement now runs
 * behind a black screen and the picture fades up once it is done, which costs about a second and
 * a half at the start of a song — over an intro, and under the title card's own fade — and hides
 * the machinery completely. It fades back to black as the video runs out, for the same reason.
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
    /**
     * Called when the "video" turns out to be one photograph held for the whole song.
     *
     * A good many uploads are exactly that -- a sleeve scan under the audio -- and a still image
     * behind a karaoke game is worse than no image at all: it is the one background that cannot
     * respond to the music, so it reads as a frozen video rather than as a choice. The visualiser
     * is strictly better there, and it is already what a song with no video gets.
     */
    onStillImage: () -> Unit = {},
    /**
     * Whether the picture fills the screen, cropping what will not fit.
     *
     * A setting rather than a rule, and the only thing it changes is the two lines that size the
     * view plus whether a measured letterbox crop is applied at all. **The measuring still
     * happens either way**, because the frames it reads are the same frames the still-image test
     * reads — so turning this off saves nothing and would only mean the answer is thrown away
     * one step later. Shown whole, a file's baked-in bars are part of its picture and belong on
     * screen with it; cropping them is exactly the zoom this setting turns off.
     */
    fillScreen: Boolean = true,
    modifier: Modifier = Modifier,
) {
    if (videoUri == null) return

    val context = LocalContext.current
    var aspect by remember { mutableFloatStateOf(16f / 9f) }

    /** Extra scale that crops away black bars baked into the file. 1 until measured. */
    var crop by remember(videoUri) { mutableFloatStateOf(1f) }
    var surface by remember(videoUri) { mutableStateOf<TextureView?>(null) }

    /** Whether the crop is settled, and so whether the picture may be shown at all. */
    var revealed by remember(videoUri) { mutableStateOf(false) }

    /** Whether the video is close enough to its own end to start leaving. */
    var ending by remember(videoUri) { mutableStateOf(false) }

    val fade by animateFloatAsState(
        targetValue = if (revealed && !ending) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (ending) FADE_OUT_MILLIS else FADE_IN_MILLIS,
            easing = LinearEasing,
        ),
        label = "video",
    )

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

            // A video is very often shorter than its song, so it runs out mid-track. Going out
            // on a fade rather than on whatever frame the decoder stopped at is the same
            // courtesy as fading in — and it is the only ending this ever needs, because when
            // the *song* finishes the results are already over the top of it.
            val duration = player.duration
            if (duration != C.TIME_UNSET && duration > 0) {
                val videoSeconds = duration / 1000.0
                ending = target >= videoSeconds - FADE_OUT_LEAD_SECONDS

                // Past the end of the file there is nothing left to keep in step with, and
                // trying to costs a seek every quarter of a second for the rest of the song:
                // the position stops advancing at the duration while the song's does not, so
                // the drift only ever grows and every check asks for another decoder flush.
                if (target >= videoSeconds) {
                    if (player.isPlaying) player.pause()
                    continue
                }
            }

            if (abs(player.currentPosition / 1000.0 - target) > MAX_DRIFT_SECONDS) {
                player.seekTo((target * 1000).toLong())
            }
            if (playing != player.isPlaying) {
                if (playing) player.play() else player.pause()
            }
        }
    }

    // Measure the letterbox before anything is on screen, and stop measuring once it is.
    //
    // Several samples, and the **smallest** crop any of them asked for wins. A bar is in every
    // frame of the file, so a real one survives every sample; a dark sky at the top of one shot
    // does not, and taking the first answer would zoom the whole video for the rest of the song
    // on the strength of it.
    //
    // Only readable frames count towards the total, which is what makes packing the samples this
    // tightly safe: a video opening on a fade from black returns null for every one of them and
    // simply waits, up to [CROP_DEADLINE_MS], rather than measuring the fade and cropping to it.
    LaunchedEffect(player) {
        // Nothing to read until frames are actually being produced, and the song sits on a title
        // card for over two seconds before that.
        while (!isPlaying()) delay(100)

        val pixels = IntArray(SAMPLE_WIDTH * SAMPLE_HEIGHT)
        var current = IntArray(pixels.size)
        var previous = IntArray(pixels.size)

        val deadline = System.currentTimeMillis() + CROP_DEADLINE_MS
        var smallest = Float.MAX_VALUE
        var taken = 0

        // The same frames answer both questions, which is what makes asking the second one free.
        var moving = false

        // Two things are being waited for and they finish at different times: the crop needs
        // its samples, and motion needs either to be seen or to have failed to appear for long
        // enough. Whichever is outstanding keeps the loop going.
        while (
            (taken < CROP_SAMPLES || (!moving && taken < MOTION_MIN_SAMPLES)) &&
            System.currentTimeMillis() < deadline
        ) {
            delay(CROP_SAMPLE_INTERVAL_MS)
            val view = surface ?: continue
            if (!readFrame(view, pixels, current)) continue
            val measured = measureLetterbox(current) ?: continue
            taken++
            if (measured < smallest) smallest = measured
            if (!moving && taken > 1 && moved(previous, current)) moving = true

            val spare = previous
            previous = current
            current = spare
        }

        // A picture that has not changed across a couple of seconds is a photograph, and the
        // visualiser is a better background than a frozen one. Decided *before* anything is
        // revealed, so there is no switch to see -- the screen goes from the title card to the
        // visualiser, exactly as it does for a song with no video file at all.
        if (!moving && taken >= MOTION_MIN_SAMPLES) {
            onStillImage()
            return@LaunchedEffect
        }

        if (smallest != Float.MAX_VALUE && fillScreen) crop = smallest
        revealed = true
    }

    // Black behind the picture, because that is what it fades from and back to. Clipping is what
    // makes "cover" work: the video is deliberately sized larger than the screen in one direction
    // and the overflow is cut off.
    BoxWithConstraints(modifier.background(Color.Black).clipToBounds()) {
        val boxAspect = maxWidth / maxHeight

        // The whole of the setting, in two lines. Filling means matching the screen on whichever
        // axis leaves no gap and letting the other overflow; fitting is the same comparison the
        // other way round, so the picture lands inside the screen with black either side of it.
        // `crop` is 1 unless filling, so it drops out of the second case.
        val wide = if (fillScreen) aspect > boxAspect else aspect < boxAspect
        val width = (if (wide) maxHeight * aspect else maxWidth) * crop
        val height = (if (wide) maxHeight else maxWidth / aspect) * crop

        // A TextureView rather than a SurfaceView. A SurfaceView is a separate compositor layer
        // that is not reliably clipped by its parent, so sizing it past the screen edge — which
        // is exactly what covering requires — is not something it can be trusted to do, and it
        // cannot be read back a frame at a time either, which the crop measurement needs. A
        // TextureView is an ordinary view: it obeys the clip, it honours `alpha`, and it costs an
        // extra copy per frame, which the Shield does not notice.
        AndroidView(
            factory = {
                TextureView(it).also { view ->
                    // Starts invisible, so the first frame cannot be seen before it is measured.
                    view.alpha = 0f
                    player.setVideoTextureView(view)
                    surface = view
                }
            },
            // Set on the view rather than through a graphics layer. A TextureView is the one kind
            // of view where that distinction has historically mattered, and its own alpha is the
            // path certain to composite.
            update = { it.alpha = fade },
            // requiredSize, not size. `size` is clamped by the incoming constraints, so a view
            // deliberately sized past the screen edge — which is exactly what covering means —
            // silently snapped back to the screen, and a TextureView stretches its content to
            // whatever bounds it ends up with. Every video was being squashed to 16:9, and the
            // letterbox crop could never take effect either, since it multiplies a size that was
            // being clamped away.
            modifier = Modifier.requiredSize(width, height).align(Alignment.Center),
        )
    }
}
