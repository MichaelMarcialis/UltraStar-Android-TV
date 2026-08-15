package com.example.ultrastarandroidtv

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.example.ultrastarandroidtv.game.SongLauncher

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Nobody touches the remote while singing, and the screen going dark mid-song would
        // take the whole game with it: Compose drives gameplay from frame callbacks, which
        // stop being delivered the moment the display sleeps.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            // The game. Diagnostics still live in `diagnostics/` — `SyncCalibrationScreen.kt`,
            // `IsoCaptureScreen.kt` and `SongLibraryScreen.kt` — swap the call here to run one.
            SongLauncher()
        }
    }
}