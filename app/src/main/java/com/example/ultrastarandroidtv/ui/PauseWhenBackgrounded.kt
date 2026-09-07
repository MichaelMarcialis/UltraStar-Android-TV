package com.example.ultrastarandroidtv.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.Player

/**
 * Stops a player when the app leaves the screen.
 *
 * Reported from the sofa: a preview kept playing after pressing Home, so the launcher sat there
 * with a song coming out of it and nothing on screen to stop it. On a television that is worse
 * than on a phone — there is no notification to swipe and no obvious owner of the sound.
 *
 * `ON_STOP` rather than `ON_PAUSE`: on Android TV a dialog over the app pauses it, and a preview
 * going silent because a permission prompt appeared would be its own small bug. Stop is the event
 * that means "not visible", which is the thing being asked about.
 *
 * **Stop, not pause.** A paused player still holds its audio output open, and this device's audio
 * path is a re-encode to E-AC3 over HDMI — holding it while another app wants it is impolite and
 * has a history of glitching on the way back. Nothing here needs to resume where it left off: a
 * preview restarts the moment something is focused again.
 */
@Composable
fun PauseWhenBackgrounded(player: Player) {
    val owner = LocalContext.current.lifecycleOwner()
    DisposableEffect(owner, player) {
        if (owner == null) return@DisposableEffect onDispose { }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) player.stop()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}

/**
 * The activity behind a composable's context.
 *
 * Unwrapped by hand rather than taken from a `LocalLifecycleOwner`, which has moved package
 * between Compose versions — this depends on nothing but `ContextWrapper`, and answers null rather
 * than throwing if it is ever composed somewhere without an activity.
 */
internal fun Context.lifecycleOwner(): LifecycleOwner? {
    var context: Context? = this
    while (context != null) {
        if (context is LifecycleOwner) return context
        context = (context as? ContextWrapper)?.baseContext
    }
    return null
}
