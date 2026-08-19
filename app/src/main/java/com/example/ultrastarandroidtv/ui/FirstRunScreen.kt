package com.example.ultrastarandroidtv.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.example.ultrastarandroidtv.game.GameTheme
import com.example.ultrastarandroidtv.library.LibraryLocation

/**
 * Asked once, the first time somebody presses Play with no song folder chosen.
 *
 * The app only ever reads the one folder it was pointed at — it has never searched the device,
 * and searching a terabyte card for stray `.txt` files would be slow and would still guess wrong.
 * So there is exactly one thing it must be told before it can do anything, and this is the
 * moment to ask: somebody pressing Play has said what they want, and the answer to "why can't
 * I?" should be a question they can answer rather than a disabled button and an instruction to
 * go and look somewhere else.
 *
 * **It sits in front of Play rather than replacing it.** Once a folder is chosen this screen is
 * never seen again — the grant survives reboots — so it costs a returning household nothing.
 *
 * It is also the recovery path when a grant genuinely goes away: the card gets reformatted, or
 * pulled and replaced, and [LibraryLocation.saved] starts returning null. That is indistinguishable
 * from a first run and wants the same screen.
 */
@Composable
fun FirstRunScreen(
    onReady: () -> Unit,
    onMenu: () -> Unit,
) {
    val context = LocalContext.current
    val location = remember { LibraryLocation(context) }
    var problem by remember { mutableStateOf<String?>(null) }

    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    BackHandler(onBack = onMenu)

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { picked ->
        when {
            picked == null -> problem = "No folder picked."
            location.remember(picked) -> onReady()
            else -> problem = "Android would not keep access to that folder. Try another."
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(GameTheme.background)
            .padding(72.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "Where are your songs?",
            style = MaterialTheme.typography.headlineLarge,
            color = GameTheme.lyricActive,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "Point the game at the folder holding your UltraStar songs — the one with a folder " +
                "per song inside it. It is only asked once.",
            style = MaterialTheme.typography.bodyLarge,
            color = GameTheme.lyricIdle,
        )

        problem?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, color = GameTheme.sparkWarm)
        }

        Spacer(Modifier.height(40.dp))
        Row {
            Button(onClick = { picker.launch(null) }, modifier = Modifier.focusRequester(first)) {
                Text(
                    "Choose song folder",
                    fontSize = 24.sp,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
                )
            }
            Spacer(Modifier.width(20.dp))
            Button(onClick = onMenu) {
                Text(
                    "Main menu",
                    fontSize = 24.sp,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
                )
            }
        }
    }
}
