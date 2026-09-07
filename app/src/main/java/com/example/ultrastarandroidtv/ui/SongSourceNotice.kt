package com.example.ultrastarandroidtv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.example.ultrastarandroidtv.download.DownloadTerms
import com.example.ultrastarandroidtv.game.GameTheme

/**
 * What downloading a song actually does, said once before anybody does it.
 *
 * See [DownloadTerms] for why this is a gate, why it is here rather than at launch, and why it is
 * remembered. This file is only the wording and the shape.
 *
 * **The wording is plain and short on purpose.** A paragraph of terms nobody reads protects
 * nobody: the one sentence that matters is *only download songs you already own*, and it is the
 * only sentence set in the warm colour. Everything else is there to make that sentence make
 * sense — what the button is about to fetch, from where, and what does not happen to it
 * afterwards.
 *
 * **"I understand" takes the focus, not "Back".** The confirmation and the press that reached it
 * are the same button on a remote, so a default of the destructive answer is no safer than no
 * confirmation at all — but nothing here is destructive, and stranding somebody on a screen whose
 * only lit control is the way out would be its own small failure. The same rule the removal
 * dialogs follow, pointing the other way because the risk does.
 */
@Composable
fun SongSourceNotice(
    terms: DownloadTerms,
    onAccept: () -> Unit,
    onBack: () -> Unit,
) {
    val accept = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { accept.requestFocus() } }
    BackHandler(onBack = onBack)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(GameTheme.background)
            .padding(horizontal = 72.dp, vertical = 44.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "Before you download songs",
            style = MaterialTheme.typography.headlineMedium,
            color = GameTheme.lyricActive,
        )

        Spacer(Modifier.height(18.dp))
        Text(
            "Adding a song fetches its chart from USDB, a free community database, and its music " +
                "and video from YouTube. All of it is saved to your song folder — which means a " +
                "copy of a commercial recording ends up on your card.",
            style = MaterialTheme.typography.bodyLarge,
            color = GameTheme.lyricIdle,
            // Bounded rather than full width: a line of text running the whole of a television is
            // hard to track back to the start of, and this is the one screen meant to be read.
            modifier = Modifier.widthIn(max = 700.dp),
        )

        Spacer(Modifier.height(16.dp))
        Text(
            "Only download songs you already own a lawful copy of.",
            style = MaterialTheme.typography.titleMedium,
            color = GameTheme.sparkWarm,
        )

        Spacer(Modifier.height(16.dp))
        Text(
            "Whether that copy is lawful to make depends on what you own and where you live, and " +
                "this app can see neither — so the decision is yours, not its. Nothing you " +
                "download is uploaded, shared or published anywhere: the files stay on your card, " +
                "and USDB is asked for them using your own account.",
            style = MaterialTheme.typography.bodyLarge,
            color = GameTheme.lyricIdle,
            modifier = Modifier.widthIn(max = 700.dp),
        )

        Spacer(Modifier.height(32.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Button(
                onClick = {
                    terms.accept()
                    onAccept()
                },
                modifier = Modifier.focusRequester(accept),
            ) {
                Text(
                    "I understand",
                    fontSize = 22.sp,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
                )
            }
            Button(onClick = onBack) {
                Text(
                    "Back",
                    fontSize = 22.sp,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
                )
            }
        }
    }
}
