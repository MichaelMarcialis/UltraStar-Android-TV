package com.example.ultrastarandroidtv.library

import android.content.Context
import android.content.Intent
import android.os.storage.StorageManager
import android.os.storage.StorageVolume

/** A drive the songs could be on, and the intent that asks for permission to read it. */
class Drive(
    val name: String,
    val removable: Boolean,
    private val volume: StorageVolume,
) {
    /**
     * A picker opened **at this drive's root**, which is the only place the confirm button can
     * reliably be pressed with a remote — see [drivesOn].
     */
    fun pickerIntent(): Intent = volume.createOpenDocumentTreeIntent()
}

/**
 * The drives attached to this device, in the order to offer them.
 *
 * **This exists to keep the system file picker off the part of itself that is broken.**
 * DocumentsUI lays its "USE THIS FOLDER" button over the file grid with no bottom padding
 * reserved for it, so as soon as a folder's contents are taller than the screen the last row's
 * bounds run to the bottom edge, nothing sits *below* the focused item, and Android's downward
 * focus search has no candidate. The button is then unreachable with a D-pad — not disabled,
 * not hidden, simply unreachable, which is the worst of the three.
 *
 * A **drive root** is the one place that cannot happen: it holds a handful of top-level folders,
 * the grid is one row, and the button is genuinely below it. Measured on the Shield — at the
 * card's root the remote reaches the button and grants the drive with no taps at all, while
 * inside a 71-song folder it cannot be reached by any key.
 *
 * So the app asks which *drive*, not which folder, and hands DocumentsUI a
 * [StorageVolume.createOpenDocumentTreeIntent] that opens exactly there. Granting a whole
 * removable volume is allowed — the refusal Android 11 is known for applies to primary shared
 * storage, not to a USB drive — and the scanner already walks four levels deep and skips
 * `Android/`, `LOST.DIR` and `System Volume Information`, so a whole drive costs nothing over a
 * folder inside it.
 *
 * Enumerating volumes needs **no permission at all**, which is what makes this possible: the app
 * can name the drives itself, in its own D-pad-friendly list, and only ever sends the system
 * picker somewhere it works.
 *
 * Removable drives come first: songs live on the card, and the internal drive is the answer
 * almost nobody wants.
 */
fun drivesOn(context: Context): List<Drive> {
    val manager = context.getSystemService(StorageManager::class.java) ?: return emptyList()
    return manager.storageVolumes
        .filter { it.state == android.os.Environment.MEDIA_MOUNTED }
        .map {
            Drive(
                name = it.getDescription(context) ?: "Drive",
                removable = it.isRemovable,
                volume = it,
            )
        }
        .sortedByDescending { it.removable }
}
