package com.example.ultrastarandroidtv.ui

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import com.example.ultrastarandroidtv.library.drivesOn

/**
 * "Which drive are your songs on?" — one button per attached drive.
 *
 * The app asks this itself rather than letting the system picker ask, because listing drives
 * needs no permission and this list is ours: it is large, it is a plain row, and every key on the
 * remote does what it looks like it does. See `drivesOn` for why that matters — the system
 * picker's confirm button becomes unreachable by D-pad in any folder taller than the screen, and
 * a drive's root is the one place that cannot happen.
 *
 * Picking a drive opens the picker already at that drive's root, so the only thing left to do
 * there is press the button that works.
 */
@Composable
fun DriveChoices(
    firstFocus: FocusRequester? = null,
    onGranted: (Uri) -> Unit,
    onRefused: (String) -> Unit,
) {
    val context = LocalContext.current
    val drives = remember { drivesOn(context) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val tree = result.data?.data
        when {
            result.resultCode != Activity.RESULT_OK || tree == null ->
                onRefused("No drive chosen.")
            else -> onGranted(tree)
        }
    }

    if (drives.isEmpty()) {
        Text(
            "No storage found. Plug in the drive holding your songs.",
            style = MaterialTheme.typography.bodyLarge,
            color = GameTheme.sparkWarm,
        )
        return
    }

    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            drives.forEachIndexed { index, drive ->
                Button(
                    onClick = { launcher.launch(drive.pickerIntent()) },
                    modifier = if (index == 0 && firstFocus != null) {
                        Modifier.focusRequester(firstFocus)
                    } else {
                        Modifier
                    },
                ) {
                    Text(
                        drive.name,
                        fontSize = 24.sp,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            "Then press USE THIS FOLDER, and Allow. The whole drive is searched for songs, so " +
                "there is no need to go looking for the right folder.",
            style = MaterialTheme.typography.bodyMedium,
            color = GameTheme.lyricIdle,
        )
    }
}
