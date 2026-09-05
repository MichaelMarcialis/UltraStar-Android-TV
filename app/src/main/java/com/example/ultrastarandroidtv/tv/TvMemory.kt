package com.example.ultrastarandroidtv.tv

import android.content.Context

private const val PREFS = "tv_game_mode"
private const val KEY_CLIENT = "client_key"
private const val KEY_HOST = "host"
private const val KEY_MODEL = "model"
private const val KEY_RESTORE = "restore_mode"
private const val KEY_FINGERPRINT_PREFIX = "fp_"

/**
 * What this app remembers about the television between launches.
 *
 * ## Why each of these is kept
 *
 * - **The client key** is the pairing token. Without it every launch would put a prompt on the
 *   television and wait for somebody to accept it, which is not a thing an app may do to a room.
 * - **The address** because a search costs two seconds at launch and a set that has not moved
 *   answers on its old address at once. It is a cache, not a setting: a failure re-runs discovery.
 * - **The learned fingerprints** because the set will not name its own picture mode, so the only
 *   way to recognise one is to have seen its values before. See [PictureModes].
 * - **The mode to go back to**, which is the whole point of the feature.
 *
 * The client key is a token for a device on the user's own network and it is kept in the app's
 * private preferences, which is the same treatment the USDB password gets and for the same
 * reason: it never leaves the device, and anyone with `adb` on this Shield could read it either
 * way. It is excluded from backup along with everything else in this app.
 */
class TvMemory(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var clientKey: String?
        get() = prefs.getString(KEY_CLIENT, null)
        set(value) = prefs.edit().putString(KEY_CLIENT, value).apply()

    var host: String?
        get() = prefs.getString(KEY_HOST, null)
        set(value) = prefs.edit().putString(KEY_HOST, value).apply()

    var model: String?
        get() = prefs.getString(KEY_MODEL, null)
        set(value) = prefs.edit().putString(KEY_MODEL, value).apply()

    /**
     * The picture mode to put the television back into.
     *
     * Survives a launch on purpose. The app reads the mode in force when it opens, but a crash,
     * a power cut or being killed in the background all leave the set in Game Optimizer with
     * nothing in memory to say what it was before — and the answer to that is the answer from
     * last time, which is nearly always right.
     */
    var restoreMode: String?
        get() = prefs.getString(KEY_RESTORE, null)
        set(value) = prefs.edit().putString(KEY_RESTORE, value).apply()

    /** Every picture mode whose values this app has seen, so it can recognise them again. */
    fun learned(): Map<String, PictureFingerprint> = prefs.all
        .filterKeys { it.startsWith(KEY_FINGERPRINT_PREFIX) }
        .mapNotNull { (key, value) ->
            (value as? String)?.let { key.removePrefix(KEY_FINGERPRINT_PREFIX) to PictureFingerprint.decode(it) }
        }
        .toMap()

    fun learn(mode: String, fingerprint: PictureFingerprint) {
        if (fingerprint.isEmpty) return
        prefs.edit().putString(KEY_FINGERPRINT_PREFIX + mode, fingerprint.encode()).apply()
    }

    /** Forgets the television entirely, which is what turning the feature off has to mean. */
    fun forget() {
        prefs.edit().clear().apply()
    }
}
