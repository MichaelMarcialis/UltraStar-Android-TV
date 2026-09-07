package com.example.ultrastarandroidtv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.ultrastarandroidtv.tv.TvGameMode

/**
 * Asks the television for game mode while this app is on screen, and puts the picture back when
 * it is not.
 *
 * `ON_START` and `ON_STOP` rather than resume and pause, the same choice [PauseWhenBackgrounded]
 * makes and for the same reason: on Android TV a permission dialog over the app pauses it, and a
 * television flicking out of game mode because the USB prompt appeared would be its own small
 * bug. Stop is the event that means *not visible*, which is the question being asked.
 *
 * Both calls return immediately and do their work on [TvGameMode]'s own scope, so a set that is
 * switched off or on another network costs the app nothing at all — not a frame, not a stall on
 * the way out.
 */
@Composable
fun TvGameModeWhileOpen(tv: TvGameMode) {
    val owner = LocalContext.current.lifecycleOwner()
    DisposableEffect(owner, tv) {
        if (owner == null) return@DisposableEffect onDispose { }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> tv.engage()
                Lifecycle.Event.ON_STOP -> tv.release()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}
