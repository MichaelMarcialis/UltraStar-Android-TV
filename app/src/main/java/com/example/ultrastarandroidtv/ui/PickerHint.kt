package com.example.ultrastarandroidtv.ui

import androidx.compose.runtime.Composable
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.example.ultrastarandroidtv.game.GameTheme

/**
 * How to get out of Android's folder picker with a remote.
 *
 * The picker lays its "USE THIS FOLDER" button *over* the file grid rather than below it, with no
 * bottom padding reserved. In any folder whose contents are taller than the screen the last row's
 * bounds run to the bottom edge, so nothing sits below the focused item, Android's downward focus
 * search has no candidate, and the button cannot be reached by any key. A folder of songs always
 * overflows, so that is not a rare case here.
 *
 * **But the button takes focus by itself every time a folder opens** — measured on the device
 * inside the 71-song folder, which overflows about as hard as anything will. So the button is not
 * unreachable, it is only unreachable *again*: press it on arrival and the picker works fine;
 * move down into the files and you are stranded until you press Back and come in once more.
 *
 * That is a small enough rule to say in two sentences, which is why the app asks for a folder
 * again rather than working around the bug. The workaround was to ask for a whole *drive* — a
 * drive root cannot overflow — and it traded this two-sentence hint for a permanent ambiguity
 * about where songs actually live, which is a worse deal for everybody who has to use it later.
 */
@Composable
fun PickerHint() {
    Text(
        "USE THIS FOLDER is already selected whenever a folder opens — press it once you are in " +
            "the right one. If you move down into the files you cannot get back up to it: press " +
            "Back and open the folder again.",
        style = MaterialTheme.typography.bodyMedium,
        color = GameTheme.lyricIdle,
    )
}
