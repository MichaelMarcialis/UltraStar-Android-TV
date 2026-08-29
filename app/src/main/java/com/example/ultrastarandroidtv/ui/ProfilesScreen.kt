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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.example.ultrastarandroidtv.game.GameTheme
import androidx.compose.ui.platform.LocalContext
import com.example.ultrastarandroidtv.settings.HighScores
import com.example.ultrastarandroidtv.settings.Profiles

private enum class Mode { List, Adding, Managing, Renaming, Confirming }

/**
 * Add, rename and delete the names singers pick from.
 *
 * **A top-level menu item rather than a page inside Settings**, because it is about people and
 * Settings is about the machine — latency, microphone sensitivity, how far ahead the notes
 * scroll. Those are tuned once by whoever set the thing up and then left alone; this list changes
 * whenever a friend comes round. Burying it a level down would also make it invisible at the only
 * moment it is genuinely useful, which is somebody setting up before anyone has sung.
 *
 * Names can still be created during the claim step, and that stays the common path — this exists
 * for the two things that step cannot do, correcting a name and removing one.
 */
@Composable
fun ProfilesScreen(profiles: Profiles, onBack: () -> Unit) {
    // A name is not only a name: the library credits each song's record to one. So the two things
    // this screen can do to a name have to be done to the records as well, or the library goes on
    // naming somebody who has been deleted, or somebody whose typo was corrected everywhere else.
    val records = HighScores(LocalContext.current)

    var mode by remember { mutableStateOf(Mode.List) }
    var selected by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }

    val first = remember { FocusRequester() }

    // Re-aimed after every change, because each mode puts something different under the D-pad
    // and a screen with nothing focused is a screen the remote cannot drive.
    LaunchedEffect(mode, profiles.names) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }

    BackHandler {
        if (mode == Mode.List) {
            onBack()
        } else {
            mode = Mode.List
            problem = null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(GameTheme.background)
            .padding(56.dp),
    ) {
        // Only the list wears the screen's title. Every other mode is a single question with its
        // own heading — and, more practically, the on-screen keyboard covers the bottom of the
        // screen, so a title left in place while typing pushes the buttons underneath it.
        if (mode == Mode.List) {
            Text(
                "Singers",
                style = MaterialTheme.typography.headlineLarge,
                color = GameTheme.lyricActive,
            )
            Text(
                "Most recently sung first. Names can also be made when somebody claims a microphone.",
                style = MaterialTheme.typography.bodyLarge,
                color = GameTheme.lyricIdle,
            )
            Spacer(Modifier.height(36.dp))
        }

        when (mode) {
            Mode.List -> {
                if (profiles.names.isEmpty()) {
                    Text(
                        "Nobody yet. Add a name here, or just start a game — the first singer " +
                            "can make one when they pick up a microphone.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = GameTheme.lyricIdle,
                    )
                    Spacer(Modifier.height(28.dp))
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        items(profiles.names) { name ->
                            Button(
                                onClick = {
                                    selected = name
                                    mode = Mode.Managing
                                },
                                modifier = if (name == profiles.names.first()) {
                                    Modifier.focusRequester(first)
                                } else {
                                    Modifier
                                },
                            ) {
                                Text(
                                    name,
                                    fontSize = 26.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    Text(
                        "Choose a name to rename or remove it.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = GameTheme.lyricIdle,
                    )
                    Spacer(Modifier.height(28.dp))
                }

                Row {
                    Button(
                        onClick = {
                            draft = ""
                            problem = null
                            mode = Mode.Adding
                        },
                        modifier = if (profiles.names.isEmpty()) {
                            Modifier.focusRequester(first)
                        } else {
                            Modifier
                        },
                    ) {
                        Text("Add a singer", modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    }
                    Spacer(Modifier.width(16.dp))
                    Button(onClick = onBack) {
                        Text("Main menu", modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    }
                }
            }

            Mode.Managing -> {
                Text(selected, style = MaterialTheme.typography.displaySmall, color = GameTheme.playerColors[0])
                Spacer(Modifier.height(32.dp))
                Row {
                    Button(
                        onClick = {
                            draft = selected
                            problem = null
                            mode = Mode.Renaming
                        },
                        modifier = Modifier.focusRequester(first),
                    ) {
                        Text("Rename", modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    }
                    Spacer(Modifier.width(16.dp))
                    Button(onClick = { mode = Mode.Confirming }) {
                        Text("Remove", modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    }
                    Spacer(Modifier.width(16.dp))
                    Button(onClick = { mode = Mode.List }) {
                        Text("Cancel", modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    }
                }
            }

            Mode.Adding, Mode.Renaming -> {
                val renaming = mode == Mode.Renaming
                Text(
                    if (renaming) "New name for $selected" else "What is their name?",
                    style = MaterialTheme.typography.headlineSmall,
                    color = GameTheme.lyricActive,
                )
                Spacer(Modifier.height(20.dp))

                val submit = {
                    val done = if (renaming) {
                        // Records move with the name. A rename is a correction rather than a new
                        // person, so what they already did stays theirs.
                        profiles.rename(selected, draft).also { if (it) records.rename(selected, draft) }
                    } else {
                        profiles.add(draft)
                    }
                    if (done) {
                        problem = null
                        mode = Mode.List
                    } else {
                        // The two ways it can fail read the same to whoever typed it, so they
                        // are reported the same way rather than as two separate rules.
                        problem = if (draft.isBlank()) {
                            "A name cannot be empty."
                        } else {
                            "${draft.trim()} is already on the list."
                        }
                    }
                }

                NameEntry(
                    value = draft,
                    onValueChange = {
                        draft = it
                        problem = null
                    },
                    colour = GameTheme.playerColors[0],
                    focusRequester = first,
                    onDone = submit,
                )

                problem?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, style = MaterialTheme.typography.bodyLarge, color = GameTheme.playerColors[1])
                }

                Spacer(Modifier.height(20.dp))
                Row {
                    Button(onClick = submit) {
                        Text(
                            if (renaming) "Save" else "Add",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    Button(
                        onClick = {
                            problem = null
                            mode = Mode.List
                        },
                    ) {
                        Text("Cancel", modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    }
                }
            }

            Mode.Confirming -> {
                Text(
                    "Remove $selected?",
                    style = MaterialTheme.typography.headlineSmall,
                    color = GameTheme.lyricActive,
                )
                Text(
                    "Only the name goes. Nothing else is kept against it.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = GameTheme.lyricIdle,
                )
                Spacer(Modifier.height(28.dp))

                // Cancel takes the focus, not Remove. On a remote the confirmation step and the
                // press that caused it are the same button, and a default of "yes" would make
                // this no safer than having no confirmation at all.
                Row {
                    Button(onClick = { mode = Mode.List }, modifier = Modifier.focusRequester(first)) {
                        Text("Keep", modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    }
                    Spacer(Modifier.width(16.dp))
                    Button(
                        onClick = {
                            profiles.remove(selected)
                            // And their records with them, which is the only route by which a
                            // record can ever be cleared.
                            records.forget(selected)
                            mode = Mode.List
                        },
                    ) {
                        Text("Remove", modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    }
                }
            }
        }
    }
}
