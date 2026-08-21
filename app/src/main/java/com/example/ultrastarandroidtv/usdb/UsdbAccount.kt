package com.example.ultrastarandroidtv.usdb

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

private const val PREFS = "usdb_account"
private const val KEY_USER = "user"
private const val KEY_PASS = "pass"

/**
 * The USDB login, remembered so nobody has to type a password on a television.
 *
 * **Why the password and not just the session cookie.** USDB's `PHPSESSID` lasts six days
 * (measured 2026-08-20). Keeping only the cookie would mean typing a password with a remote
 * roughly once a week, which is exactly the kind of friction that makes a feature go unused —
 * and the "remember me" box is the site's own answer to it. So the login is kept and the session
 * is re-established silently whenever it has lapsed. The user's call, made knowing the trade.
 *
 * **Where it lives, and what that does and does not protect.** The app's private storage, next to
 * the player names and the settings — readable by this app and no other, which is what stops
 * another app on the TV from taking it. It is **not** encrypted, and it is worth being straight
 * about the limit: anyone with `adb` access to this Shield can read it, because debug builds allow
 * `run-as`. That door is one this project deliberately leaves open in order to deploy at all.
 * Android's Keystore would not close it either — `run-as` runs *as this app*, so it could simply
 * ask the Keystore to decrypt. What Keystore would defend against is someone taking the flash off
 * the device, which is not the threat here.
 *
 * The protection that *does* matter is cheap and is applied: this file is **excluded from backup**
 * (`backup_rules.xml`, `data_extraction_rules.xml`), so the password never leaves the Shield.
 *
 * **One account per person, never bundled.** Nothing here ships with the app — every user signs in
 * as themselves. That is what makes it honest to hand this to somebody else, and it is what keeps
 * a throttle breach on one account from being everybody's problem.
 */
class UsdbAccount(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Who is signed in. Compose state, so a screen showing "Signed in as …" follows a sign-out
     * without being told.
     */
    var username: String by mutableStateOf(prefs.getString(KEY_USER, "").orEmpty())
        private set

    /** True when there is a login to try. Not a promise that USDB will still accept it. */
    val hasAccount: Boolean get() = username.isNotBlank()

    /**
     * The stored password.
     *
     * Deliberately a function and deliberately **not** Compose state: it is read at the moment of
     * signing in and nowhere else. State would invite a screen to observe it, and the one thing a
     * password must never be is accidentally rendered.
     */
    fun password(): String = prefs.getString(KEY_PASS, "").orEmpty()

    /** Stores a login. Blank input clears it rather than saving an unusable one. */
    fun remember(user: String, password: String) {
        val cleanUser = user.trim()
        if (cleanUser.isEmpty() || password.isEmpty()) {
            forget()
            return
        }
        prefs.edit().putString(KEY_USER, cleanUser).putString(KEY_PASS, password).apply()
        username = cleanUser
    }

    /** Signs out for good: the next visit asks again. */
    fun forget() {
        prefs.edit().remove(KEY_USER).remove(KEY_PASS).apply()
        username = ""
    }
}
