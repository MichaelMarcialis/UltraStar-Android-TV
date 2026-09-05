package com.example.ultrastarandroidtv.tv

import org.json.JSONArray
import org.json.JSONObject

/** The port a webOS set listens on for plain SSAP. 3001 is the same thing wrapped in TLS. */
private const val SSAP_PORT = 3000

/**
 * How long to wait for the pairing prompt to be accepted.
 *
 * Somebody has to notice a dialog on a television and press a button on a remote, which is a
 * different order of time from anything else here.
 */
private const val PAIRING_TIMEOUT_MILLIS = 60_000

/**
 * The picture settings this app is allowed to read, which is fewer than it would like.
 *
 * `pictureMode` itself is refused — *"Some keys are not allowed for the request. ( pictureMode )"*
 * — so the mode cannot be asked for by name. These five can be read, they differ from mode to
 * mode, and together they identify one: measured on a C1, Game Optimizer is backlight 75 /
 * contrast 85 / colour 55, FILMMAKER 25 / 85 / 50, Cinema 80 / 85 / 50 and Vivid 100 / 100 / 70.
 * That is what [PictureFingerprint] is for.
 */
private val READABLE_PICTURE_KEYS = listOf(
    "backlight", "contrast", "brightness", "color", "energySaving",
)

/**
 * What the television looks like without being able to ask it what mode it is in.
 *
 * Values arrive as numbers or as strings depending on the firmware's mood — a `getSystemSettings`
 * immediately after a mode change returns `"85"` where the same call a moment later returns `85`
 * — so everything is held as text and compared as text.
 */
data class PictureFingerprint(val values: Map<String, String>) {
    val isEmpty: Boolean get() = values.isEmpty()

    fun encode(): String = values.entries.sortedBy { it.key }.joinToString(";") { "${it.key}=${it.value}" }

    companion object {
        fun decode(text: String): PictureFingerprint = PictureFingerprint(
            text.split(";").filter { it.contains('=') }
                .associate { it.substringBefore('=') to it.substringAfter('=') },
        )
    }
}

/** What went wrong, in terms a screen can put in front of somebody. */
class TvException(message: String) : Exception(message)

/**
 * One conversation with an LG webOS television.
 *
 * ## What this is for
 *
 * Putting the set into Game Optimizer while the app is open and back afterwards. The Shield
 * cannot signal Auto Low Latency Mode — the request is dropped before it reaches HDMI — and CEC
 * is behind a signature permission, so the television's own network API is the only route left,
 * and it is the one that does exactly what was asked.
 *
 * ## The protocol, as measured against a real C1 rather than assumed
 *
 * 1. Find the set: an SSDP search for `urn:lge-com:service:webos-second-screen:1`. See
 *    [discoverWebOsTv].
 * 2. Open a WebSocket to port 3000, **with no `Origin` header** — see [WebSocket].
 * 3. `register`, which puts a prompt on the television. Accepting it returns a *client key*,
 *    which is kept and skips the prompt for ever after.
 * 4. Reading settings is an ordinary `ssap://` request, but **only for a handful of keys**, and
 *    `pictureMode` is not one of them.
 * 5. Writing the picture mode is not exposed over SSAP at all, and goes through [luna].
 *
 * Every method blocks. This is called from a background thread and never from the main one.
 */
class WebOsTv(
    private val host: String,
    private val socketFactory: (String, Int) -> WebSocketLike = { h, p -> RealWebSocket(h, p) },
) : AutoCloseable {

    private val socket = socketFactory(host, SSAP_PORT)
    private var counter = 0

    /**
     * Registers, and returns the key to use next time.
     *
     * Pass the saved key to skip the prompt; pass null the first time and somebody has to accept
     * a dialog on the television within a minute.
     */
    fun register(clientKey: String?): String {
        val payload = JSONObject()
            .put("forcePairing", false)
            .put("pairingType", "PROMPT")
            .put("manifest", JSONObject(PAIRING_MANIFEST))
        if (clientKey != null) payload.put("client-key", clientKey)
        send("register", JSONObject().put("type", "register").put("id", nextId("register")).put("payload", payload))

        // The set answers the request immediately and only sends the key once the prompt has been
        // accepted, so this waits for the second message rather than the first.
        while (true) {
            val reply = socket.receive(PAIRING_TIMEOUT_MILLIS)
                ?: throw TvException("The television closed the connection while pairing.")
            val message = JSONObject(reply)
            when (message.optString("type")) {
                "registered" -> return message.optJSONObject("payload")?.optString("client-key")
                    ?.takeIf { it.isNotBlank() }
                    ?: throw TvException("The television paired but sent no key.")
                "error" -> throw TvException("The television refused to pair: ${message.optString("error")}")
            }
        }
    }

    /** The five picture values this set will part with. Empty if it refuses even those. */
    fun fingerprint(): PictureFingerprint {
        val payload = JSONObject()
            .put("category", "picture")
            .put("keys", JSONArray(READABLE_PICTURE_KEYS))
        val settings = request("ssap://settings/getSystemSettings", payload)
            .optJSONObject("payload")?.optJSONObject("settings")
            ?: return PictureFingerprint(emptyMap())
        return PictureFingerprint(
            settings.keys().asSequence().associateWith { settings.get(it).toString() },
        )
    }

    /** Switches the picture mode. `game`, `filmMaker`, `cinema`, `standard`, `vivid`, … */
    fun setPictureMode(mode: String) {
        luna(
            "com.webos.settingsservice/setSystemSettings",
            JSONObject()
                .put("category", "picture")
                .put("settings", JSONObject().put("pictureMode", mode)),
        )
    }

    /** What the set calls itself, for a screen to show. Null if it will not say. */
    fun modelName(): String? =
        request("ssap://system/getSystemInfo", JSONObject())
            .optJSONObject("payload")?.optString("modelName")?.takeIf { it.isNotBlank() }

    /**
     * Calls a `luna://` service, which SSAP does not expose directly.
     *
     * The way round is an invisible alert whose `onclose` handler names the luna URI, closed
     * immediately: the set fires the handler on the way down. It is a workaround for the set's own
     * restriction and not a way past any authentication — the pairing prompt still had to be
     * accepted by somebody in the room.
     *
     * **It has to be an alert, and it has to be closed.** `createToast` takes the same `onclose`,
     * answers `returnValue: true` with a toast id, and does nothing whatever, because nothing ever
     * closes a toast. Measured on the C1: the toast reported success and the picture mode did not
     * move; the alert moved it.
     */
    private fun luna(uri: String, params: JSONObject) {
        val full = "luna://$uri"
        val raised = request(
            "ssap://system.notifications/createAlert",
            JSONObject()
                .put("message", " ")
                .put(
                    "buttons",
                    JSONArray().put(JSONObject().put("label", "").put("onClick", full).put("params", params)),
                )
                .put("onclose", JSONObject().put("uri", full).put("params", params)),
        )
        val alertId = raised.optJSONObject("payload")?.optString("alertId")?.takeIf { it.isNotBlank() }
            ?: throw TvException("The television would not raise the alert the setting rides on.")
        request("ssap://system.notifications/closeAlert", JSONObject().put("alertId", alertId))
    }

    private fun request(uri: String, payload: JSONObject): JSONObject {
        val id = nextId("req")
        send(
            uri,
            JSONObject().put("type", "request").put("id", id).put("uri", uri).put("payload", payload),
        )
        // Replies can arrive out of order once anything is subscribed, so match on the id rather
        // than taking whatever comes back first.
        repeat(MAX_SKIPPED_REPLIES) {
            val reply = socket.receive() ?: throw TvException("The television closed the connection.")
            val message = JSONObject(reply)
            if (message.optString("id") == id) {
                if (message.optString("type") == "error") {
                    throw TvException("The television refused $uri: ${message.optString("error")}")
                }
                return message
            }
        }
        throw TvException("The television never answered $uri.")
    }

    private fun send(what: String, message: JSONObject) {
        runCatching { socket.send(message.toString()) }
            .onFailure { throw TvException("Could not reach the television to $what: ${it.message}") }
    }

    private fun nextId(kind: String): String = "${kind}_${++counter}"

    override fun close() {
        socket.close()
    }

    private companion object {
        const val MAX_SKIPPED_REPLIES = 8
    }
}

/**
 * The socket [WebOsTv] talks over, as an interface so the protocol can be tested without one.
 *
 * The framing is its own problem and has its own test; what is worth pinning here is the
 * *conversation* — that pairing waits for the second message, that a reply is matched by id, and
 * that a picture mode change is an alert raised and then closed.
 */
interface WebSocketLike : AutoCloseable {
    fun send(text: String)
    fun receive(timeoutMillis: Int = 5_000): String?
}

private class RealWebSocket(host: String, port: Int) : WebSocketLike {
    private val socket = WebSocket(host, port)
    override fun send(text: String) = socket.send(text)
    override fun receive(timeoutMillis: Int): String? = socket.receive(timeoutMillis)
    override fun close() = socket.close()
}

/**
 * The handshake manifest, copied verbatim from the one every webOS remote sends.
 *
 * Not ours and deliberately unedited: the `signed` block carries a signature over its own
 * contents, so changing the app name inside it — which was the obvious first thing to try —
 * makes a manifest that no longer matches its own signature. It names LG's own test application
 * because that is the identity the set will pair with; what appears on the pairing prompt is that
 * name, not this app's.
 */
private const val PAIRING_MANIFEST = """
{
  "manifestVersion": 1,
  "appVersion": "1.1",
  "signed": {
    "created": "20140509",
    "appId": "com.lge.test",
    "vendorId": "com.lge",
    "localizedAppNames": {"": "LG Remote App", "ko-KR": "리모컨 앱", "zxx-XX": "ЛГ Rэмotэ AПП"},
    "localizedVendorNames": {"": "LG Electronics"},
    "permissions": ["TEST_SECURE", "CONTROL_INPUT_TEXT", "CONTROL_MOUSE_AND_KEYBOARD",
      "READ_INSTALLED_APPS", "READ_LGE_SDX", "READ_NOTIFICATIONS", "SEARCH", "WRITE_SETTINGS",
      "WRITE_NOTIFICATION_ALERT", "CONTROL_POWER", "READ_CURRENT_CHANNEL", "READ_RUNNING_APPS",
      "READ_UPDATE_INFO", "UPDATE_FROM_REMOTE_APP", "READ_ORIGINAL_SETTINGS", "CONTROL_DISPLAY"],
    "serial": "2f930e2d2cfe083771f68e4fe7bb07"
  },
  "permissions": ["LAUNCH", "LAUNCH_WEBAPP", "APP_TO_APP", "CLOSE", "TEST_OPEN", "TEST_PROTECTED",
    "CONTROL_AUDIO", "CONTROL_DISPLAY", "CONTROL_INPUT_JOYSTICK", "CONTROL_INPUT_MEDIA_RECORDING",
    "CONTROL_INPUT_MEDIA_PLAYBACK", "CONTROL_INPUT_TV", "CONTROL_POWER", "READ_APP_STATUS",
    "READ_CURRENT_CHANNEL", "READ_INPUT_DEVICE_LIST", "READ_NETWORK_STATE", "READ_RUNNING_APPS",
    "READ_TV_CHANNEL_LIST", "WRITE_NOTIFICATION_TOAST", "READ_POWER_STATE", "READ_COUNTRY_INFO",
    "READ_SETTINGS", "CONTROL_TV_SCREEN", "CONTROL_INPUT_TEXT", "CONTROL_MOUSE_AND_KEYBOARD",
    "READ_INSTALLED_APPS"],
  "signatures": [{
    "signatureVersion": 1,
    "signature": "eyJhbGdvcml0aG0iOiJSU0EtU0hBMjU2Iiwia2V5SWQiOiJ0ZXN0LXNpZ25pbmctY2VydCIsInNpZ25hdHVyZVZlcnNpb24iOjF9.hrVRgjCwXVvE2OOSpDZ58hR+59aFNwYDyjQgKk3auukd7pcegmE2CzPCa0bJ0ZsRAcKkCTJrWo5iDzNhMBWRyaMOv5zWSrthlf7G128qvIlpMT0YNY+n/FaOHE73uLrS/g7swl3/qH/BGFG2Hu4RlL48eb3lLKqTt2xKHdCs6Cd4RMfJPYnzgvI4BNrFUKsjkcu+WD4OO2A27Pq1n50cMchmcaXadJhGrOqH5YmHdOCj5NSHzJYrsW0HPlpuAx/ECMeIZYDh6RMqaFM2DXzdKX9NmmyqzJ3o/0lkk/N97gfVRLW5hA29yeAwaCViZNCP8iC9aO0q9fQojoa7NQnAtw=="
  }]
}
"""
