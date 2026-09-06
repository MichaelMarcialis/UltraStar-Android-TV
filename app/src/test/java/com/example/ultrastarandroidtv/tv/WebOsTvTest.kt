package com.example.ultrastarandroidtv.tv

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The conversation with the television, without a television.
 *
 * The framing is [WebSocket]'s problem; what is worth pinning here is everything that was got
 * wrong against the real set and would be got wrong again: that pairing waits for the *second*
 * message, that a reply is matched by its id, and above all that a picture mode change is an
 * alert **raised and then closed** rather than a toast.
 */
class WebOsTvTest {

    // ---------------------------------------------------------------------------------------
    // Recognising a picture mode by its values, because the set will not say
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a fingerprint survives being written down and read back`() {
        val original = PictureFingerprint(mapOf("backlight" to "75", "contrast" to "85"))
        assertEquals(original.values, PictureFingerprint.decode(original.encode()).values)
    }

    @Test
    fun `two fingerprints are the same however their keys were ordered`() {
        // They arrive from a JSON object, which has no order worth relying on.
        val one = PictureFingerprint(mapOf("backlight" to "75", "contrast" to "85"))
        val other = PictureFingerprint(mapOf("contrast" to "85", "backlight" to "75"))
        assertEquals(one.encode(), other.encode())
    }

    @Test
    fun `a mode is recognised only by an exact match`() {
        // Measured on the C1: Game Optimizer is backlight 75 / contrast 85, Filmmaker is 25 / 85.
        val learned = mapOf(
            "game" to PictureFingerprint(mapOf("backlight" to "75", "contrast" to "85")),
            "filmMaker" to PictureFingerprint(mapOf("backlight" to "25", "contrast" to "85")),
        )
        assertEquals("filmMaker", modeMatching(learned.getValue("filmMaker"), learned))

        // Nearly Filmmaker is not Filmmaker. A near match would be a *different* mode with
        // similar values, and putting a television into the wrong one is worse than admitting
        // the mode is not known.
        val nearly = PictureFingerprint(mapOf("backlight" to "26", "contrast" to "85"))
        assertNull(modeMatching(nearly, learned))
    }

    @Test
    fun `game mode is recognised so the television is never trapped in it`() {
        // Without this guard, opening the app twice traps the set: the first run sets game, the
        // second reads game back and files it as the mode to restore, for ever.
        val game = PictureFingerprint(mapOf("backlight" to "75", "contrast" to "85"))
        val learned = mapOf("game" to game)

        assertTrue(isGameMode(game, learned))
        assertFalse(isGameMode(PictureFingerprint(mapOf("backlight" to "25")), learned))

        // Nothing read at all must not read as game mode, or a set that refuses to answer would
        // have its real picture mode forgotten.
        assertFalse(isGameMode(PictureFingerprint(emptyMap()), mapOf("game" to PictureFingerprint(emptyMap()))))
    }

    // ---------------------------------------------------------------------------------------
    // The conversation
    // ---------------------------------------------------------------------------------------

    @Test
    fun `pairing waits for the key, not for the acknowledgement`() {
        // The set answers the register immediately with `pairingType: PROMPT` and only sends the
        // key once somebody has accepted the dialog. Taking the first reply would report success
        // with no key and a television nobody had agreed to.
        val socket = FakeSocket(
            """{"type":"response","id":"register_1","payload":{"pairingType":"PROMPT","returnValue":true}}""",
            """{"type":"registered","id":"register_1","payload":{"client-key":"abc123"}}""",
        )
        assertEquals("abc123", tvOver(socket).register(null))
    }

    @Test
    fun `a refusal to pair is reported rather than waited out`() {
        val socket = FakeSocket("""{"type":"error","id":"register_1","error":"403 denied"}""")
        assertThrows(TvException::class.java) { tvOver(socket).register(null) }
    }

    @Test
    fun `a reply is matched by its id`() {
        // Anything subscribed pushes messages in between the ones that were asked for, so taking
        // whatever comes back first reads someone else's answer.
        val socket = FakeSocket(
            """{"type":"registered","id":"register_1","payload":{"client-key":"k"}}""",
            """{"type":"response","id":"something_else","payload":{"settings":{"backlight":1}}}""",
            """{"type":"response","id":"req_2","payload":{"settings":{"backlight":75,"contrast":85}}}""",
        )
        val tv = tvOver(socket)
        tv.register(null)
        assertEquals(mapOf("backlight" to "75", "contrast" to "85"), tv.fingerprint().values)
    }

    @Test
    fun `setting the picture mode raises an alert and then closes it`() {
        // The whole finding, in one test. `createToast` takes the same payload and answers
        // `returnValue: true` with a toast id -- and does nothing whatever, because nothing ever
        // closes a toast and the luna call rides on the close handler. Measured on the C1: the
        // toast reported success and the picture mode did not move; the alert moved it.
        val socket = FakeSocket(
            """{"type":"registered","id":"register_1","payload":{"client-key":"k"}}""",
            """{"type":"response","id":"req_2","payload":{"alertId":"alert-7"}}""",
            """{"type":"response","id":"req_3","payload":{"returnValue":true}}""",
        )
        val tv = tvOver(socket)
        tv.register(null)
        tv.setPictureMode("game")

        val sent = socket.sent.map { JSONObject(it) }
        assertEquals(
            listOf(
                "register",
                "ssap://system.notifications/createAlert",
                "ssap://system.notifications/closeAlert",
            ),
            sent.map { if (it.optString("type") == "register") "register" else it.optString("uri") },
        )

        val alert = sent[1].getJSONObject("payload")
        val onClose = alert.getJSONObject("onclose")
        assertEquals("luna://com.webos.settingsservice/setSystemSettings", onClose.getString("uri"))
        assertEquals(
            "game",
            onClose.getJSONObject("params").getJSONObject("settings").getString("pictureMode"),
        )
        assertEquals("alert-7", sent[2].getJSONObject("payload").getString("alertId"))
    }

    @Test
    fun `a set that will not part with its picture values says so rather than inventing them`() {
        val socket = FakeSocket(
            """{"type":"registered","id":"register_1","payload":{"client-key":"k"}}""",
            """{"type":"error","id":"req_2","error":"500 Application error"}""",
        )
        val tv = tvOver(socket)
        tv.register(null)
        assertThrows(TvException::class.java) { tv.fingerprint() }
    }

    // ---------------------------------------------------------------------------------------

    // ---------------------------------------------------------------------------------------
    // Which television is on the other end
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a set presenting a different certificate is refused before the key is sent`() {
        // The pairing key is a working credential for somebody's television. Trust on first use
        // only means anything if the second use checks, and it has to check *before* registering
        // -- once the key has gone out it cannot be taken back.
        val socket = FakeSocket(peerFingerprint = "bb")

        assertThrows(TvException::class.java) {
            WebOsTv("192.0.2.1", expectedPin = "aa") { _, _ -> socket }
        }
        assertTrue("nothing may be sent to it", socket.sent.isEmpty())
    }

    @Test
    fun `the first connection records the certificate it found`() {
        var learned: String? = null
        val socket = FakeSocket(
            """{"type":"registered","id":"register_1","payload":{"client-key":"k"}}""",
            peerFingerprint = "aa",
        )

        WebOsTv("192.0.2.1", expectedPin = null, onPin = { learned = it }) { _, _ -> socket }
            .register(null)

        assertEquals("aa", learned)
    }

    @Test
    fun `the same set is accepted`() {
        val socket = FakeSocket(
            """{"type":"registered","id":"register_1","payload":{"client-key":"k"}}""",
            peerFingerprint = "aa",
        )

        val tv = WebOsTv("192.0.2.1", expectedPin = "aa") { _, _ -> socket }

        assertEquals("k", tv.register("k"))
    }

    // ---------------------------------------------------------------------------------------

    private fun tvOver(socket: FakeSocket) = WebOsTv("192.0.2.1") { _, _ -> socket }

    /** A scripted set: hands back the prepared replies in order, and keeps what was sent. */
    private class FakeSocket(
        vararg replies: String,
        override val peerFingerprint: String? = null,
    ) : WebSocketLike {
        val sent = mutableListOf<String>()
        private val queue = ArrayDeque(replies.toList())

        override fun send(text: String) {
            sent += text
        }

        override fun receive(timeoutMillis: Int): String? = queue.removeFirstOrNull()

        override fun close() = Unit
    }
}
