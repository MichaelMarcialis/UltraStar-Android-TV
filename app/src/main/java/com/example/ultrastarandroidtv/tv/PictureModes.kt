package com.example.ultrastarandroidtv.tv

/**
 * The picture mode this app switches the television into.
 *
 * `game` is webOS's id for what a C1 calls **Game Optimizer** — confirmed on the set: sending
 * this puts the banner on screen and the mode stays until something changes it back.
 */
const val GAME_PICTURE_MODE = "game"

/**
 * Every picture mode worth trying to identify, most likely first.
 *
 * ## Why a list exists at all
 *
 * The set will not say which mode it is in. `getSystemSettings` refuses the `pictureMode` key
 * outright — *"Some keys are not allowed for the request"* — while happily returning the
 * *values* of the mode in force. So the mode is identified the other way round: try one, read the
 * values back, and see whether they are the values that were there before the app touched
 * anything. See [learnFingerprints].
 *
 * Ordered by what somebody is most likely to be sitting in rather than alphabetically, because
 * learning stops at the first match and every attempt is a visible flicker on the television.
 * `filmMaker` is first because it is what this household uses and what any picture-quality
 * article recommends; `standard` and `eco` next because they are what a set ships in.
 */
val KNOWN_PICTURE_MODES = listOf(
    "filmMaker", "standard", "eco", "cinema", "vivid", "sports", "hdrEffect",
    "expert1", "expert2", "photo", "normal", "game",
)

/**
 * Which mode a set of picture values belongs to, or null when nothing matches.
 *
 * Exact comparison, deliberately. A near match would be a *different* mode with similar values,
 * and putting the television into the wrong one is worse than admitting the mode is not known —
 * the fallback for "not known" is to leave it where the user last had it, which is at least a
 * choice they made.
 */
fun modeMatching(
    fingerprint: PictureFingerprint,
    learned: Map<String, PictureFingerprint>,
): String? = learned.entries.firstOrNull { it.value.values == fingerprint.values }?.key

/**
 * Whether what is on screen is already this app's doing.
 *
 * The guard that stops the television being trapped in Game Optimizer. Without it, opening the
 * app twice in a row would read "game" as the mode to go back to and there would be no way out
 * but the remote: the first run sets game, the second reads game, notes it as the mode to
 * restore, and restores it for ever.
 */
fun isGameMode(
    fingerprint: PictureFingerprint,
    learned: Map<String, PictureFingerprint>,
): Boolean = learned[GAME_PICTURE_MODE]?.values == fingerprint.values && !fingerprint.isEmpty
