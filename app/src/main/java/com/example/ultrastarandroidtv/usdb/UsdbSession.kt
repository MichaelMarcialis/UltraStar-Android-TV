package com.example.ultrastarandroidtv.usdb

import com.example.ultrastarandroidtv.net.Http
import com.example.ultrastarandroidtv.net.HttpFailure
import com.example.ultrastarandroidtv.net.HttpReply
import com.example.ultrastarandroidtv.net.HttpRequest

/** Where USDB lives. A constant so tests can point a session at a fake without a network. */
const val USDB_BASE = "https://usdb.animux.de/index.php"

/** The outcome of trying to sign in. A network that is down throws instead — see [UsdbSession]. */
enum class SignIn {
    /** Signed in, and the session cookie is held. */
    SUCCESS,

    /** The site answered, and said no. Wrong username or password. */
    REJECTED,
}

/**
 * A signed-in conversation with USDB.
 *
 * USDB is a community database of UltraStar charts, and **everything useful on it needs an
 * account** — measured on 2026-08-20, an anonymous request for the song list answers *"You are
 * not logged in. Login to use this function."* Only the RSS feed of the ten newest songs and the
 * bare page titles are public. So a session is the first thing any search has to have.
 *
 * Sign-in is an ordinary HTML form post — `user`, `pass`, `remember` — and the session is an
 * ordinary cookie, which this holds for the life of the object. There is no API and no token:
 * this is a PHP site of the vintage its markup suggests, and it is talked to the way a browser
 * would talk to it.
 *
 * Refusals are values and broken networks are exceptions, the same split the YouTube side makes:
 * "your password is wrong" is a normal answer a screen renders, and "the internet is down" is not.
 */
class UsdbSession(
    private val http: Http,
    private val baseUrl: String = USDB_BASE,
) {

    private val cookies = mutableMapOf<String, String>()

    /** True once [signIn] has succeeded and the session cookie is still held. */
    val hasSession: Boolean get() = cookies.isNotEmpty()

    /**
     * Signs in and keeps the session cookie.
     *
     * Success is confirmed by *asking for something that needs an account* rather than by reading
     * the login response. A site of this age reports a bad password by re-rendering the form with
     * a message in it, which is a moving target in two languages — whereas "can I now see the song
     * list" is the thing actually being bought, and it cannot be wrong about itself.
     */
    fun signIn(user: String, password: String): SignIn {
        require(user.isNotBlank()) { "username must not be blank" }
        cookies.clear()

        val reply = http.postForm(
            url = "$baseUrl?link=login",
            fields = mapOf(
                "user" to user,
                "pass" to password,
                // The site's own "stay signed in" box. Worth having: the alternative is typing a
                // password on a television with a remote every time the session lapses.
                "remember" to "1",
                "login" to "Login",
            ),
            headers = browserHeaders(),
            // A login reports success by redirecting, and the cookie rides along with the 302.
            // Following it discards the Set-Cookie before anything has read it.
            followRedirects = false,
        )
        absorbCookies(reply)

        if (!hasSession) return SignIn.REJECTED
        return if (signedInSomewhere()) SignIn.SUCCESS else SignIn.REJECTED.also { cookies.clear() }
    }

    /** Drops the session. The next [signIn] starts clean. */
    fun signOut() {
        cookies.clear()
    }

    /** GETs a page on USDB with the session attached, e.g. `?link=detail&id=31037`. */
    fun get(query: String): String = exchange(
        HttpRequest(url = baseUrl + query, headers = sessionHeaders()),
    )

    /** POSTs a form on USDB with the session attached. */
    fun postForm(query: String, fields: Map<String, String>): String {
        val reply = http.postForm(
            url = baseUrl + query,
            fields = fields,
            headers = sessionHeaders(),
        )
        absorbCookies(reply)
        if (!reply.ok) throw HttpFailure("USDB answered HTTP ${reply.status}", reply.status)
        return reply.body
    }

    private fun exchange(request: HttpRequest): String {
        val reply = http.send(request)
        absorbCookies(reply)
        if (!reply.ok) throw HttpFailure("USDB answered HTTP ${reply.status}", reply.status)
        return reply.body
    }

    /** Asks for a page that needs an account, and reports whether it was given one. */
    private fun signedInSomewhere(): Boolean = pageIsSignedIn(get("?link=list"))

    private fun absorbCookies(reply: HttpReply) {
        cookies.putAll(cookiesFrom(reply.headers("Set-Cookie")))
    }

    private fun sessionHeaders(): Map<String, String> =
        browserHeaders() + if (cookies.isEmpty()) emptyMap() else mapOf("Cookie" to cookieHeader(cookies))

    private fun browserHeaders(): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-GB,en;q=0.9",
        "Referer" to "$baseUrl?link=login",
    )

    private companion object {
        /**
         * An ordinary desktop browser string. Not a disguise — USDB serves a plain HTML site and
         * this app reads it the way a browser does; some hosts simply refuse requests that
         * announce no user agent at all.
         */
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/128.0.0.0 Safari/537.36"
    }
}

// ---------------------------------------------------------------------------------------------
// The rules, as free functions: pure, and so testable without a network or an account.
// ---------------------------------------------------------------------------------------------

/**
 * Reads the `name=value` out of each `Set-Cookie` header, discarding the attributes.
 *
 * A cookie being cleared (`deleted`, or an empty value) is dropped rather than stored, so a
 * sign-out response cannot leave a dead cookie behind that later reads as a live session.
 */
fun cookiesFrom(setCookieHeaders: List<String>): Map<String, String> {
    val jar = mutableMapOf<String, String>()
    for (header in setCookieHeaders) {
        val pair = header.substringBefore(';').trim()
        val name = pair.substringBefore('=', "").trim()
        val value = pair.substringAfter('=', "").trim()
        if (name.isEmpty()) continue
        if (value.isEmpty() || value.equals("deleted", ignoreCase = true)) continue
        jar[name] = value
    }
    return jar
}

/** Renders a cookie jar as a `Cookie:` header. */
fun cookieHeader(cookies: Map<String, String>): String =
    cookies.entries.joinToString("; ") { (name, value) -> "$name=$value" }

/**
 * Whether a page came back to somebody with an account.
 *
 * Looks for a way to *log out*, which is the one thing a signed-in page has and a signed-out one
 * cannot: the site is bilingual and its wording changes, but the link target does not. The
 * signed-out sentence is checked too, as a second opinion rather than the only one.
 */
fun pageIsSignedIn(html: String): Boolean {
    if (html.isBlank()) return false
    val lower = html.lowercase()
    // A way to log out is the one thing only a signed-in page has, and a link target survives the
    // site's wording changing or switching language.
    if (lower.contains("link=logout")) return true
    // Otherwise fall back on what has actually been measured: signed out, this site says so in
    // plain words and offers Login and Register. Absence of all of that is the positive signal,
    // which matters because the logged-in markup is the half that has not been seen.
    return SIGNED_OUT_MARKERS.none { it in lower }
}

private val SIGNED_OUT_MARKERS = listOf(
    "you are not logged in",
    "please login",
    "nicht eingeloggt",
    "link=register",
)
