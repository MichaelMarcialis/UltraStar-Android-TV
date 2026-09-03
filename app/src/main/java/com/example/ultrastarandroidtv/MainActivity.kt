package com.example.ultrastarandroidtv

import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.example.ultrastarandroidtv.ui.AppRoot

private const val TAG = "Display"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Nobody touches the remote while singing, and the screen going dark mid-song would
        // take the whole game with it: Compose drives gameplay from frame callbacks, which
        // stop being delivered the moment the display sleeps.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        askForGameMode()
        setContent {
            // Menu, settings, song picker and the game. Diagnostics still live in
            // `diagnostics/` — `SyncCalibrationScreen.kt`, `IsoCaptureScreen.kt` and
            // `SongLibraryScreen.kt` — swap the call here to run one.
            AppRoot()
        }
    }

    /**
     * Asks the television to turn its picture processing off while this app is in front.
     *
     * **This is the one lever on the largest term in the arrow's lag.** `displayLeadSeconds`
     * exists because a frame is not seen at the moment it is drawn: the set buys it, de-noises
     * it, interpolates it and only then lights the panel, and on this LG that was dialled in at
     * 40 ms by eye. Game mode is exactly the switch that skips all of it, and on this television
     * it usually takes the number to somewhere near ten.
     *
     * `preferMinimalPostProcessing` is the platform's own request for that, added in **API 30**,
     * which is this device's level exactly — so there is no compatibility hedge to write. Android
     * turns it into HDMI's Auto Low Latency Mode where the link supports it, and otherwise into
     * an AVI InfoFrame saying the content is a game, which is the older signal LG sets have
     * honoured for years.
     *
     * **Scoped to this window on purpose.** The Shield is mostly a streaming box, and a
     * television left in game mode would show films with its motion handling switched off. The
     * flag lives on the window, so it applies while this app is in front and lapses the moment
     * it is not — which is the behaviour asked for: game mode for the karaoke game, and nothing
     * else changed.
     *
     * A request rather than a guarantee. It is logged either way, because "the TV did not
     * switch" and "the app never asked" are indistinguishable from the sofa, and the answer
     * decides whether the display lead is worth re-dialling.
     */
    private fun askForGameMode() {
        val params = window.attributes
        params.preferMinimalPostProcessing = true
        window.attributes = params
        Log.i(
            TAG,
            "asked for minimal post-processing; display reports supported=" +
                "${display?.isMinimalPostProcessingSupported}",
        )
    }
}
