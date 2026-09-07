package com.example.ultrastarandroidtv.tv

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "TvGameMode"

/** How long the set is given to settle after a mode change before its values are read back. */
private const val SETTLE_MILLIS = 900L

/**
 * Puts the television into game mode while this app is open, and back afterwards.
 *
 * ## Why this exists, and why it is the third answer rather than the first
 *
 * A television spends most of its lag on picture processing, and every set has a mode that turns
 * that off — on an LG C1 it is called Game Optimizer. Consoles get it automatically because HDMI
 * carries Auto Low Latency Mode; **this Shield cannot send that signal**, and the framework drops
 * the request before it reaches the wire (`allmSupported false`). CEC is behind a signature
 * permission with no shell command either. Driving the set at 120 Hz did work and was measured to
 * cost more latency than it saved, so it was withdrawn.
 *
 * What is left is the television's own network API, and it turns out to do exactly the thing that
 * was wanted: switch on the way in, switch back on the way out, no remote, no picture mode left
 * wrong for tomorrow's film.
 *
 * ## The problem this is mostly made of
 *
 * **The set will not say which mode it is in.** `getSystemSettings` refuses the `pictureMode` key
 * and returns the *values* of the mode in force instead, so the mode is recognised by its values
 * rather than by name — see [PictureModes]. Which means the first run has to *learn*: try each
 * mode, read what it looks like, and stop at the one that looks like what was there to begin
 * with. That is a few seconds of visible flicker, it happens once, and it happens where somebody
 * asked for it — in Settings, when the feature is switched on — rather than as a surprise when
 * they close the app.
 *
 * ## What it is careful about
 *
 * - **Never trapping the set in game mode.** If the values on arrival are the game preset's, the
 *   app assumes it left them there and keeps the mode it already meant to restore.
 * - **Never blocking the app.** Everything runs on its own scope; a television that is switched
 *   off, unplugged or on another network simply fails quietly and leaves [status] saying so.
 * - **Remembering across a crash.** The mode to restore is on disk, so a process killed in the
 *   background still puts the picture back on the next launch.
 */
class TvGameMode(context: Context) {

    private val memory = TvMemory(context.applicationContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** One conversation at a time: engage, release and learning must not overlap. */
    private val lock = Mutex()

    /**
     * What to tell somebody looking at the Settings screen.
     *
     * Compose state, because this is the only feedback there is — the thing it controls is a
     * television, and whether it worked is not otherwise visible from the app.
     */
    var status: String by mutableStateOf("")
        private set

    /** True once a client key exists, which is what "this app knows a television" means. */
    val isPaired: Boolean get() = memory.clientKey != null

    val model: String? get() = memory.model

    /** Switches the television into game mode. Safe to call when nothing is paired. */
    fun engage() {
        if (!isPaired) return
        scope.launch {
            lock.withLock {
                runCatching { engageNow() }.onFailure {
                    Log.i(TAG, "could not engage game mode", it)
                    status = reasonFor(it)
                }
            }
        }
    }

    /** Puts the picture mode back. Called when the app leaves the screen. */
    fun release() {
        if (!isPaired) return
        scope.launch {
            lock.withLock {
                runCatching { releaseNow() }.onFailure {
                    Log.i(TAG, "could not restore the picture mode", it)
                }
            }
        }
    }

    /**
     * Finds a television, pairs with it, and learns what its picture modes look like.
     *
     * The one operation that asks anything of anybody: a prompt appears on the television and
     * somebody has to accept it. Everything after this is silent.
     */
    fun pair() {
        scope.launch {
            lock.withLock {
                runCatching { pairNow() }.onFailure {
                    Log.i(TAG, "pairing failed", it)
                    status = reasonFor(it)
                }
            }
        }
    }

    /** Puts the picture back, forgets the television, and stops. */
    fun forget() {
        scope.launch {
            lock.withLock {
                runCatching { releaseNow() }
                memory.forget()
                status = "No television."
            }
        }
    }

    // -----------------------------------------------------------------------------------------

    private suspend fun pairNow() = withContext(Dispatchers.IO) {
        status = "Looking for a television…"
        val answered = discoverWebOsTv().map { it.host }
        Log.i(TAG, "televisions that answered: $answered")
        if (answered.isEmpty()) {
            status = "No television answered. Switch it on and try again."
            return@withContext
        }

        // **More than one television can answer, and it did.** This house has two webOS sets on
        // the network and the Shield is plugged into the second of them; taking the first one
        // found meant every attempt went to a television nobody was looking at, which reset the
        // connection and read exactly like a bug in this app.
        //
        // The way to tell them apart is the one thing an app cannot see and a person cannot
        // miss: **the prompt appears on the television in front of you**. So each is tried in
        // turn, and whichever one somebody accepts is the one that gets remembered. A set that
        // refuses the connection outright is skipped in a fraction of a second; a set that
        // prompts and is ignored takes itself out of the running when its own prompt expires.
        for ((index, host) in answered.withIndex()) {
            status = if (answered.size > 1) {
                "Accept the prompt on the television you are watching (${index + 1} of ${answered.size})…"
            } else {
                "Accept the pairing prompt on the television…"
            }
            var accepted = false
            val paired = runCatching {
                openTv(host).use { tv ->
                    memory.clientKey = tv.register(null)
                    accepted = true
                    memory.host = host
                    memory.model = tv.modelName()
                    status = "Learning the picture modes…"
                    learn(tv)
                }
                // **Paired means paired *and* able to undo itself.** Learning is what produces
                // the mode to go back to, and it can end without one -- a set that will not
                // report its picture values, or a failure part way through the walk. Keeping the
                // key anyway would leave an app that switches a television into Game Optimizer
                // on every launch and can never put it back, which is worse than not having the
                // feature. So the key is only kept when there is a way home.
                memory.restoreMode != null
            }.getOrElse {
                Log.i(TAG, "not this one ($host): ${it.message}")
                false
            }
            if (paired) {
                // Straight into game mode, rather than waiting for the next launch.
                //
                // Engaging happens on `ON_START`, and by the time somebody has turned this on in
                // Settings that has long since fired — so the row said "On" while the television
                // sat in whatever mode learning had just put back. The one moment the two must
                // agree is the moment somebody switches it on and looks up at the set.
                runCatching { engageNow() }.onFailure { Log.i(TAG, "paired but could not engage", it) }
                return@withContext
            }
            memory.forget()

            // Somebody accepted the prompt on this set and learning still could not find a way
            // back — the television was already in game mode, or it reports the same values for
            // every mode. [learn] has already said which, in words somebody can act on, so the
            // search stops here rather than moving to the next television and overwriting that
            // with a message about prompts nobody was ever shown.
            if (accepted) return@withContext
        }
        status = "No television accepted the pairing prompt. Turn this off and on to try again."
    }

    /**
     * Walks the picture modes, recording what each looks like, and puts back the one that was in
     * force to begin with.
     *
     * Stops at the first mode whose values match the ones read on the way in, because that is the
     * mode the television was already in — there is nothing further to learn that this feature
     * needs, and every extra attempt is another flicker on screen.
     *
     * **A match on the very first candidate has to be checked**, and that is not paranoia: a C1
     * was observed reporting the *same* five values for every picture mode there is — vivid,
     * cinema, eco, standard and game all reading backlight 100 / contrast 100 / colour 55, where
     * the day before they had been plainly different. Whatever puts a set into that state (an HDR
     * signal on the input is the likeliest), the effect is that the first mode tried always
     * "matches", so this would confidently record whatever happens to be first in the list as the
     * mode to go back to, and restore that instead of what somebody actually had. Believing a
     * measurement that cannot fail is worse than admitting the instrument is blind.
     */
    private fun learn(tv: WebOsTv) {
        val before = tv.fingerprint()
        if (before.isEmpty) {
            status = "The television will not say what its picture looks like."
            return
        }
        for ((index, mode) in KNOWN_PICTURE_MODES.withIndex()) {
            tv.setPictureMode(mode)
            Thread.sleep(SETTLE_MILLIS)
            val seen = tv.fingerprint()
            memory.learn(mode, seen)
            if (seen.values == before.values) {
                // An immediate match is only believable if a different mode looks different. One
                // extra change to find out, and only in the case where the doubt exists.
                if (index == 0 && !valuesVaryByMode(tv, seen)) {
                    tv.setPictureMode(mode)
                    status = "This television reports the same picture values for every mode, " +
                        "so there is no way to tell which one you had. Game mode is not safe to " +
                        "switch on here."
                    memory.restoreMode = null
                    return
                }

                // **Game mode is never the way back.** A set that is already in Game Optimizer
                // when somebody pairs would otherwise have that recorded as the mode to restore,
                // and then every release puts it straight back — the television trapped in game
                // mode for ever, which is the exact thing the guard in `engageNow` exists to
                // prevent and cannot help with, because it only stops the mode being adopted
                // *later*. What somebody had before is unknowable from here, so the honest
                // answers are to keep an earlier one or to ask.
                if (mode == GAME_PICTURE_MODE) {
                    val known = memory.restoreMode?.takeIf { it != GAME_PICTURE_MODE }
                    if (known == null) {
                        status = "Your television is already in game mode, so there is no way to " +
                            "tell what to put back. Set the picture mode you normally use and " +
                            "turn this on again."
                        memory.restoreMode = null
                    } else {
                        tv.setPictureMode(known)
                        status = "Ready. Your picture mode will be put back when the app closes."
                    }
                    return
                }

                memory.restoreMode = mode
                status = "Ready. Your picture mode will be put back when the app closes."
                return
            }
        }
        // Nothing matched: the set was in a mode this app does not know the name of, or one whose
        // values have been adjusted. Putting it back exactly is then impossible, so it goes to
        // the first candidate -- which is a mode somebody chose to have on the list, rather than
        // whichever one the walk happened to stop on.
        val fallback = memory.restoreMode ?: KNOWN_PICTURE_MODES.first()
        tv.setPictureMode(fallback)
        memory.restoreMode = fallback
        status = "Ready, but your picture mode was not one this app recognises — " +
            "it will go back to ${prettyName(fallback)}."
    }

    /**
     * Whether this set's readable values actually differ between picture modes.
     *
     * Asked by trying one deliberately unlike the first candidate and seeing whether anything
     * moves. Vivid is the loudest mode a television has, so if its numbers match Filmmaker's the
     * numbers are telling us nothing at all.
     */
    private fun valuesVaryByMode(tv: WebOsTv, reference: PictureFingerprint): Boolean {
        val probe = KNOWN_PICTURE_MODES.firstOrNull { it == "vivid" } ?: return true
        tv.setPictureMode(probe)
        Thread.sleep(SETTLE_MILLIS)
        val seen = tv.fingerprint()
        memory.learn(probe, seen)
        return seen.values != reference.values
    }

    private fun engageNow() {
        // Belt and braces against the same thing pairing guards: never enter a mode there is no
        // way out of. If this is ever reached without a mode to restore, doing nothing leaves the
        // television as its owner set it.
        if (memory.restoreMode == null) return
        withTv { tv ->
            val learned = memory.learned()
            val now = tv.fingerprint()
            // Anything but game mode is worth remembering as the thing to go back to. Game mode
            // is not: reading it back means this app put it there, and adopting it would trap the
            // television in it for ever.
            if (!isGameMode(now, learned)) {
                modeMatching(now, learned)?.let { memory.restoreMode = it }
            }
            tv.setPictureMode(GAME_PICTURE_MODE)
            memory.learn(GAME_PICTURE_MODE, runCatching {
                Thread.sleep(SETTLE_MILLIS); tv.fingerprint()
            }.getOrDefault(PictureFingerprint(emptyMap())))
            status = "Game mode on ${memory.model ?: "the television"}."
        }
    }

    private fun releaseNow() {
        val mode = memory.restoreMode ?: return
        withTv { tv -> tv.setPictureMode(mode) }
    }

    /**
     * One connection, opened, used and closed.
     *
     * Deliberately not a connection held open for the life of the app. It would have to survive
     * the set being switched off, the network dropping and the app being backgrounded for hours,
     * and all this ever does is send two messages a session — the cost of opening a socket on a
     * LAN is nothing beside the cost of keeping one honest.
     *
     * **The remembered address is tried first and a search is the fallback**, in that order and
     * not the other way round. A set that has not moved answers on its old address immediately,
     * where a search costs a couple of seconds every single launch; but a router hands out a new
     * lease eventually, and a feature that then stopped working for good would be worse than one
     * that is occasionally slow.
     */
    private fun <T> withTv(block: (WebOsTv) -> T): T {
        val remembered = memory.host
        if (remembered != null) {
            runCatching { open(remembered, block) }.getOrNull()?.let { return it }
        }
        // The address only changes when a router hands out a new lease, and then the set is
        // still the same one -- so a search here is a re-find rather than a fresh choice. Every
        // answering set is tried, because more than one can answer and only the paired one will
        // accept this app's key.
        for (host in discoverWebOsTv().map { it.host }) {
            if (host == remembered) continue
            runCatching { open(host, block) }.getOrNull()?.let { return it }
        }
        throw TvException("No television answered. It has to be switched on.")
    }

    private fun <T> open(host: String, block: (WebOsTv) -> T): T = openTv(host).use { tv ->
        memory.clientKey = tv.register(memory.clientKey)
        memory.host = host
        block(tv)
    }

    /**
     * A connection to [host], pinned to the certificate this app paired with.
     *
     * The pin is only *recorded* when there is not one already — first use establishes it, and
     * after that a set presenting a different certificate is refused before the key is sent.
     */
    private fun openTv(host: String) = WebOsTv(
        host = host,
        expectedPin = memory.certificatePin,
        onPin = { fingerprint ->
            if (memory.certificatePin == null) memory.certificatePin = fingerprint
        },
    )

    private fun reasonFor(error: Throwable): String = when {
        // The set's own prompt times out after about eighty seconds and answers this, which is
        // the same word it uses when somebody presses No. Either way the useful thing to say is
        // how to ask again, not what the error code was.
        error is TvException && error.message.orEmpty().contains("cancelled") ->
            "The prompt on the television was not accepted. Turn this off and on to ask again."
        error is TvException -> error.message ?: "The television refused."
        else -> "Could not reach the television. It has to be switched on."
    }
}

/** A picture mode's name as a person would read it, for the one line of status text. */
internal fun prettyName(mode: String): String = when (mode) {
    "filmMaker" -> "Filmmaker"
    "hdrEffect" -> "HDR Effect"
    "expert1" -> "Expert (bright room)"
    "expert2" -> "Expert (dark room)"
    "eco" -> "Eco"
    "game" -> "Game Optimizer"
    else -> mode.replaceFirstChar(Char::uppercase)
}
