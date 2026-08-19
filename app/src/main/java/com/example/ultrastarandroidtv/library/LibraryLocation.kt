package com.example.ultrastarandroidtv.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

private const val TAG = "LibraryLocation"
private const val PREFS = "library"
private const val KEY_TREE_URI = "tree_uri"

/**
 * Remembers which folder holds the songs, across launches and reboots.
 *
 * This is the whole reason the app exists in the shape it does. UltraStar Play forgot its song
 * folder on every single launch, and re-picking it on a TV remote each time is what made it
 * unusable. Getting this right is not a detail.
 *
 * Two things have to happen, and skipping either produces a path that works until it doesn't:
 * the grant has to be made **persistable** at the moment it is given, and it has to be **checked
 * against the grants the system still holds** on the way back. A stored string on its own
 * outlives the permission it describes — the card gets pulled, the volume is reformatted, the
 * user clears app data — and a URI without a live grant fails at the first read with something
 * unhelpful about permission denial. Better to notice here and ask again.
 */
class LibraryLocation(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * The remembered folder, or null if there isn't one the system will still let us read.
     *
     * Checking [android.content.ContentResolver.getPersistedUriPermissions] rather than trusting
     * the stored string is what turns "the library disappeared" into "pick the folder again".
     */
    fun saved(): Uri? {
        val stored = prefs.getString(KEY_TREE_URI, null) ?: return null
        val uri = Uri.parse(stored)
        val held = context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission
        }
        if (!held) {
            Log.w(TAG, "grant for $uri is gone; asking for the folder again")
            forget()
            return null
        }
        return uri
    }

    /**
     * True when the remembered folder can also be *written* to, which is what removing a song
     * needs.
     *
     * Read and write are separate grants, and this app asked for read alone until songs could be
     * deleted. A folder granted by an older build is therefore readable and nothing more, and no
     * amount of asking at delete time can widen it — the grant is only handed out by the picker.
     * So this exists to let the screen say "choose the folder again to remove songs" instead of
     * offering a button that fails.
     */
    fun canModify(): Boolean {
        val stored = prefs.getString(KEY_TREE_URI, null) ?: return false
        val uri = Uri.parse(stored)
        return context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isWritePermission
        }
    }

    /**
     * Stores the folder the user picked and asks to keep reading and writing it indefinitely.
     *
     * Write is taken even though playing a song never needs it, because the only moment it can
     * be taken is this one. `takePersistableUriPermission` can only confirm access the picker
     * just granted; there is no later prompt that upgrades a read-only grant in place. Asking
     * for it here costs nothing and is the difference between removing a broken song from the
     * sofa and fetching a laptop.
     *
     * Returns false if the system refused to make the grant persistable, which means it would
     * be lost on reboot — worth failing visibly rather than appearing to work for one session.
     */
    fun remember(uri: Uri): Boolean {
        return try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            prefs.edit().putString(KEY_TREE_URI, uri.toString()).apply()
            true
        } catch (error: SecurityException) {
            Log.e(TAG, "could not persist the grant for $uri", error)
            false
        }
    }

    fun forget() {
        prefs.edit().remove(KEY_TREE_URI).apply()
    }
}
