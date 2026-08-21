package com.example.ultrastarandroidtv.usdb

import com.example.ultrastarandroidtv.net.Http
import com.example.ultrastarandroidtv.net.HttpReply
import com.example.ultrastarandroidtv.net.HttpRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsdbSessionTest {

    // -----------------------------------------------------------------------------------------
    // Cookies
    // -----------------------------------------------------------------------------------------

    @Test
    fun `keeps the name and value and drops the attributes`() {
        val jar = cookiesFrom(listOf("PHPSESSID=abc123; path=/; HttpOnly; SameSite=Lax"))
        assertEquals(mapOf("PHPSESSID" to "abc123"), jar)
    }

    @Test
    fun `keeps every cookie the site sets`() {
        val jar = cookiesFrom(listOf("PHPSESSID=abc; path=/", "usdb_remember=xyz; Max-Age=99"))
        assertEquals(mapOf("PHPSESSID" to "abc", "usdb_remember" to "xyz"), jar)
    }

    /**
     * A cleared cookie must not be stored: [UsdbSession.hasSession] is "do I hold a cookie", so a
     * sign-out that left a dead one behind would read as a live session ever after.
     */
    @Test
    fun `a cleared cookie is not a cookie`() {
        val jar = cookiesFrom(
            listOf(
                "PHPSESSID=deleted; expires=Thu, 01-Jan-1970 00:00:01 GMT",
                "other=; path=/",
            ),
        )
        assertTrue(jar.isEmpty())
    }

    @Test
    fun `renders a cookie header`() {
        assertEquals("a=1; b=2", cookieHeader(linkedMapOf("a" to "1", "b" to "2")))
    }

    // -----------------------------------------------------------------------------------------
    // Telling signed in from signed out
    // -----------------------------------------------------------------------------------------

    /** Verbatim from an anonymous request to `?link=list` on 2026-08-20. */
    private val signedOutPage = """
        <html><body>
        &#187; Home &#187; Login &#187; Register
        <a href="index.php?link=register">Register</a>
        Welcome, Please login ...
        ERROR: You are not logged in. Login to use this function.
        </body></html>
    """.trimIndent()

    @Test
    fun `the real signed-out page is recognised`() {
        assertFalse(pageIsSignedIn(signedOutPage))
    }

    @Test
    fun `a logout link means signed in`() {
        val page = """<html><a href="index.php?link=logout">Logout</a> Welcome back</html>"""
        assertTrue(pageIsSignedIn(page))
    }

    /** The site is bilingual, so the German complaint must not read as a session either. */
    @Test
    fun `the german complaint is recognised too`() {
        assertFalse(pageIsSignedIn("<html>Du bist nicht eingeloggt.</html>"))
    }

    @Test
    fun `an empty page is not a session`() {
        assertFalse(pageIsSignedIn(""))
        assertFalse(pageIsSignedIn("   "))
    }

    // -----------------------------------------------------------------------------------------
    // Signing in
    // -----------------------------------------------------------------------------------------

    @Test
    fun `signs in and keeps the session`() {
        val http = FakeUsdb(loginSetsCookie = true, listPage = SIGNED_IN_PAGE)
        val session = UsdbSession(http, BASE)
        assertEquals(SignIn.SUCCESS, session.signIn("someone", "hunter2"))
        assertTrue(session.hasSession)
    }

    @Test
    fun `sends the credentials as an ordinary form post`() {
        val http = FakeUsdb(loginSetsCookie = true, listPage = SIGNED_IN_PAGE)
        UsdbSession(http, BASE).signIn("someone", "hunter2")
        assertEquals("someone", http.loginFields["user"])
        assertEquals("hunter2", http.loginFields["pass"])
        assertEquals("1", http.loginFields["remember"])
    }

    /**
     * The login answers with a redirect and the cookie rides along with it. Following the redirect
     * throws the reply away before anything has read the cookie, so the session never starts.
     */
    @Test
    fun `does not follow the login redirect`() {
        val http = FakeUsdb(loginSetsCookie = true, listPage = SIGNED_IN_PAGE)
        UsdbSession(http, BASE).signIn("someone", "hunter2")
        assertFalse(http.loginFollowedRedirects)
    }

    @Test
    fun `no cookie means rejected`() {
        val http = FakeUsdb(loginSetsCookie = false, listPage = SIGNED_IN_PAGE)
        val session = UsdbSession(http, BASE)
        assertEquals(SignIn.REJECTED, session.signIn("someone", "wrong"))
        assertFalse(session.hasSession)
    }

    /**
     * A site of this age hands out a session cookie to anyone who loads the login page, so holding
     * one proves nothing. Success is confirmed by asking for a page that needs an account -- and a
     * failure there must clear the cookie, or the next request looks signed in.
     */
    @Test
    fun `a cookie that cannot see the song list is not a session`() {
        val http = FakeUsdb(loginSetsCookie = true, listPage = signedOutPage)
        val session = UsdbSession(http, BASE)
        assertEquals(SignIn.REJECTED, session.signIn("someone", "wrong"))
        assertFalse(session.hasSession)
    }

    @Test
    fun `attaches the session to later requests`() {
        val http = FakeUsdb(loginSetsCookie = true, listPage = SIGNED_IN_PAGE)
        val session = UsdbSession(http, BASE)
        session.signIn("someone", "hunter2")
        session.get("?link=detail&id=31037")
        assertEquals("PHPSESSID=abc123", http.lastCookieHeader)
    }

    @Test
    fun `sends no cookie header before signing in`() {
        val http = FakeUsdb(loginSetsCookie = true, listPage = SIGNED_IN_PAGE)
        UsdbSession(http, BASE).get("?link=list")
        assertNull(http.lastCookieHeader)
    }

    @Test
    fun `signing out drops the session`() {
        val http = FakeUsdb(loginSetsCookie = true, listPage = SIGNED_IN_PAGE)
        val session = UsdbSession(http, BASE)
        session.signIn("someone", "hunter2")
        session.signOut()
        assertFalse(session.hasSession)
    }

    @Test
    fun `a blank username is a programming error`() {
        val http = FakeUsdb(loginSetsCookie = true, listPage = SIGNED_IN_PAGE)
        try {
            UsdbSession(http, BASE).signIn("  ", "x")
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    // -----------------------------------------------------------------------------------------

    private class FakeUsdb(
        private val loginSetsCookie: Boolean,
        private val listPage: String,
    ) : Http {
        var loginFields: Map<String, String> = emptyMap()
        var loginFollowedRedirects = true
        var lastCookieHeader: String? = null

        override fun send(request: HttpRequest): HttpReply {
            lastCookieHeader = request.headers["Cookie"]
            return HttpReply(200, listPage, emptyMap())
        }

        override fun postForm(
            url: String,
            fields: Map<String, String>,
            headers: Map<String, String>,
            followRedirects: Boolean,
        ): HttpReply {
            lastCookieHeader = headers["Cookie"]
            if (url.contains("link=login")) {
                loginFields = fields
                loginFollowedRedirects = followRedirects
                val cookies = if (loginSetsCookie) {
                    mapOf("Set-Cookie" to listOf("PHPSESSID=abc123; path=/; HttpOnly"))
                } else {
                    emptyMap()
                }
                return HttpReply(302, "", cookies)
            }
            return HttpReply(200, listPage, emptyMap())
        }
    }

    private companion object {
        const val BASE = "https://usdb.example/index.php"
        const val SIGNED_IN_PAGE =
            """<html><a href="index.php?link=logout">Logout</a><table>songs</table></html>"""
    }
}
