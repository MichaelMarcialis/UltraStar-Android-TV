package com.example.ultrastarandroidtv.audio

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.MediaItem
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.ProgressiveMediaSource

/**
 * A player for the thirty-second samples both library screens play as you browse.
 *
 * Its own builder purely so that [PreviewLevel] sits in the audio path — every preview then plays
 * at the same loudness whatever it was mastered at. Deliberately **not** the player songs are sung
 * to: that one takes pass-through processors only, because the levelling here delays audio by
 * 300 ms and the game's whole timing rests on a measured 127 ms round trip.
 */
@OptIn(UnstableApi::class)
fun previewPlayer(context: Context): ExoPlayer = ExoPlayer.Builder(
    context,
    object : DefaultRenderersFactory(context) {
        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean,
        ) = DefaultAudioSink.Builder(context)
            .setAudioProcessors(arrayOf(PreviewLevel()))
            .build()
    },
).build()

/**
 * Plays a sample already held in memory, rather than streaming it.
 *
 * ## Why previews are fetched whole first
 *
 * Reported from the sofa: the samples on the Add-songs screen were sometimes broken or choppy. The
 * song picker's previews never are, and the difference is where they come from — the picker reads
 * an audio file off the card, and this screen streams thirty seconds from Apple over the same
 * connection that is *also* fetching thirty cover images, checking whether songs are available,
 * and quite possibly running a download at fifty megabytes a second. A player streaming into a
 * small buffer against that loses, and losing sounds exactly like this.
 *
 * A preview is about half a megabyte. Fetching it in one go and handing the player the bytes moves
 * the whole contest to *before* playback: the worst a busy connection can now do is make the
 * sample start a moment later, which is a thing nobody notices, instead of stuttering through it,
 * which everybody does.
 *
 * The array is held only as long as the sample is playing — the next focus replaces it — so this
 * costs one clip of memory against a 192 MB heap.
 */
@OptIn(UnstableApi::class)
fun ExoPlayer.playSample(bytes: ByteArray) {
    // The URI is ignored: ByteArrayDataSource answers every request from the array it was built
    // with. It still has to be a valid one, because MediaItem insists.
    val source = ProgressiveMediaSource.Factory { ByteArrayDataSource(bytes) }
        .createMediaSource(MediaItem.fromUri("bytes:///sample"))
    setMediaSource(source)
    prepare()
    // Full scale, because PreviewLevel has already brought the clip to a fixed loudness --
    // turning it down again would only undo half of that.
    volume = 1f
    play()
}
