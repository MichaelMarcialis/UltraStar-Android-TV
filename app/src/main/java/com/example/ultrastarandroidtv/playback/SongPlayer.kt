package com.example.ultrastarandroidtv.playback

import android.content.Context
import android.view.Choreographer
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * Plays one song and publishes where it has got to.
 *
 * A thin wrapper over ExoPlayer whose real job is [clock]: it re-anchors the clock once per
 * display frame so that capture and rendering threads can ask for the song position without
 * touching the player. Anchoring on a frame callback rather than a timer means the sampling
 * matches the rate the screen actually redraws at, and costs one field read a frame.
 *
 * **Main thread only** — ExoPlayer and [Choreographer] both require the thread that created
 * them. [clock] is the exception and is safe to read from anywhere. Call [release] when done.
 */
class SongPlayer(context: Context) {

    private val player: ExoPlayer = ExoPlayer.Builder(context).build().apply {
        setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            /* handleAudioFocus = */ true,
        )
    }

    private val choreographer = Choreographer.getInstance()
    private var ticking = false

    val clock = SongClock()

    /** Called on the main thread if playback fails. */
    var onError: ((String) -> Unit)? = null

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            sampleClock()
            if (ticking) choreographer.postFrameCallback(this)
        }
    }

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) = sampleClock()

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                // A seek moves the song somewhere the clock could never have extrapolated to,
                // so snap rather than letting it slew across the gap.
                clock.reset(newPosition.positionMs / 1000.0, System.nanoTime(), player.isPlaying)
            }

            override fun onPlayerError(error: PlaybackException) {
                onError?.invoke(error.errorCodeName + ": " + (error.message ?: "playback failed"))
            }
        })
    }

    /**
     * Backing-track volume, 0..1.
     *
     * Only the song is affected — the microphones are captured over USB and never pass through
     * the player, so turning this down leaves the singers as loud as they ever were. It cannot
     * isolate the original vocal, which is mixed into the same stereo file as everything else.
     */
    var volume: Float
        get() = player.volume
        set(value) {
            player.volume = value.coerceIn(0f, 1f)
        }

    /** True once the song has buffered enough to start. */
    val isReady: Boolean get() = player.playbackState == Player.STATE_READY

    val isPlaying: Boolean get() = player.isPlaying

    /** Total length in seconds, or 0 until it is known. */
    val durationSeconds: Double
        get() = player.duration.let { if (it == C.TIME_UNSET) 0.0 else it / 1000.0 }

    /**
     * Loads [uri] and begins tracking it. Accepts anything ExoPlayer's default data sources
     * handle, including `asset:///name.wav` for bundled files and `content://` from the
     * document picker once the song library exists.
     */
    fun load(uri: String) {
        clock.reset(0.0, System.nanoTime(), playing = false)
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        if (!ticking) {
            ticking = true
            choreographer.postFrameCallback(frameCallback)
        }
    }

    fun play() {
        player.play()
    }

    fun pause() {
        player.pause()
    }

    /** Jumps to [seconds]. Anything scoring the song has to be reset alongside this. */
    fun seekTo(seconds: Double) {
        player.seekTo((seconds * 1000).toLong())
    }

    fun release() {
        ticking = false
        choreographer.removeFrameCallback(frameCallback)
        player.release()
    }

    /**
     * Reads the position and the wall clock as close together as possible — the pair is what
     * anchors [clock], so anything between them lands straight in the sync error.
     */
    private fun sampleClock() {
        val position = player.currentPosition / 1000.0
        val now = System.nanoTime()
        clock.sample(position, now, player.playbackParameters.speed.toDouble(), player.isPlaying)
    }
}
