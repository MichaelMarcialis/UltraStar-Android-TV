package com.example.ultrastarandroidtv.audio

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.DefaultAudioSink

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
