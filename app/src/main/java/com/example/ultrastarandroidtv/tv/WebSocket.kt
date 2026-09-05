package com.example.ultrastarandroidtv.tv

import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64

/**
 * A WebSocket client with nothing in it but what the television needs.
 *
 * ## Why this is hand-written
 *
 * One server, one message shape, text frames only. OkHttp would do it and is not already a
 * dependency of this app; adding a networking stack to send a dozen JSON objects at a television
 * on the same LAN is not a trade worth making, and this repo has a rule about what it depends on.
 * What is here is the handshake, masked text frames out, unmasked frames in, and a pong — the
 * whole of RFC 6455 that a webOS set ever exercises. No extensions, no continuation frames, no
 * binary, no compression.
 *
 * ## The two things measured on the real set
 *
 * - **Send no `Origin` header.** With one, the C1 closes the connection with `1008 invalid
 *   origin` before it reads a byte of the payload. A browser is obliged to send one; a native
 *   client must not.
 * - **Answer the pings.** The set pings every few seconds and hangs up with *"client did not
 *   respond to ping"* if nothing comes back, which looks exactly like a protocol error somewhere
 *   else entirely.
 *
 * Blocking by design, and every call belongs on a background thread.
 */
internal class WebSocket(host: String, port: Int, timeoutMillis: Int = 5_000) : AutoCloseable {

    private val socket = Socket().apply {
        connect(InetSocketAddress(host, port), timeoutMillis)
        soTimeout = timeoutMillis
        tcpNoDelay = true
    }
    private val input: InputStream = socket.getInputStream()
    private val output: OutputStream = socket.getOutputStream()
    private val random = SecureRandom()

    init {
        // Anything that goes wrong from here on has to close the socket on its way out. A
        // handshake that fails leaves an open connection to the television otherwise, and a set
        // allows only a handful at once -- so a few failed attempts stop being retryable and
        // start being refused, which reads as the television having blocked this app.
        runCatching { handshake(host, port) }.onFailure {
            runCatching { socket.close() }
            throw it
        }
    }

    private fun handshake(host: String, port: Int) {
        val key = ByteArray(16).also(random::nextBytes)
        val request = buildString {
            append("GET / HTTP/1.1\r\n")
            append("Host: ").append(host).append(':').append(port).append("\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Key: ").append(Base64.getEncoder().encodeToString(key)).append("\r\n")
            // Deliberately no Origin -- see the class comment.
            append("Sec-WebSocket-Version: 13\r\n\r\n")
        }
        output.write(request.toByteArray(Charsets.US_ASCII))
        output.flush()

        val status = readHandshake()
        require(status.contains(" 101")) { "the television refused the connection: $status" }
    }

    /** Reads the HTTP response headers, and returns its status line. */
    private fun readHandshake(): String {
        val head = StringBuilder()
        var run = 0
        while (run < 4) {
            val byte = input.read()
            if (byte < 0) error("the television closed the connection during the handshake")
            head.append(byte.toChar())
            run = when {
                byte == '\r'.code && run % 2 == 0 -> run + 1
                byte == '\n'.code && run % 2 == 1 -> run + 1
                else -> 0
            }
        }
        return head.lineSequence().first()
    }

    fun send(text: String) {
        val body = text.toByteArray(Charsets.UTF_8)
        val header = ArrayList<Byte>(14)
        header.add(0x81.toByte()) // FIN, text
        // A client must mask, and the length is written in one of three widths.
        when {
            body.size < 126 -> header.add((0x80 or body.size).toByte())
            body.size < 65_536 -> {
                header.add((0x80 or 126).toByte())
                header.add((body.size ushr 8).toByte())
                header.add(body.size.toByte())
            }
            else -> {
                header.add((0x80 or 127).toByte())
                for (shift in 56 downTo 0 step 8) header.add((body.size.toLong() ushr shift).toByte())
            }
        }
        val mask = ByteArray(4).also(random::nextBytes)
        mask.forEach(header::add)
        val masked = ByteArray(body.size) { (body[it].toInt() xor mask[it % 4].toInt()).toByte() }
        output.write(header.toByteArray())
        output.write(masked)
        output.flush()
    }

    /**
     * The next text frame, answering pings on the way.
     *
     * Returns null when the set closes the connection, which it does politely and often — a
     * closed channel is an ordinary end rather than a fault, and the caller decides whether it
     * was expecting more.
     */
    fun receive(timeoutMillis: Int = 5_000): String? {
        socket.soTimeout = timeoutMillis
        while (true) {
            val first = input.read()
            if (first < 0) return null
            val second = input.read()
            if (second < 0) return null

            val opcode = first and 0x0F
            var length = (second and 0x7F).toLong()
            if (length == 126L) {
                length = ((readByte() shl 8) or readByte()).toLong()
            } else if (length == 127L) {
                length = 0
                repeat(8) { length = (length shl 8) or readByte().toLong() }
            }
            // The set never sends anything large, and a length this app cannot hold in memory is
            // a sign of a desynchronised stream rather than a real message.
            require(length <= MAX_FRAME) { "the television sent an implausible frame of $length bytes" }
            val body = ByteArray(length.toInt())
            var read = 0
            while (read < body.size) {
                val n = input.read(body, read, body.size - read)
                if (n < 0) return null
                read += n
            }

            when (opcode) {
                OPCODE_PING -> sendControl(OPCODE_PONG)
                OPCODE_CLOSE -> return null
                OPCODE_TEXT, OPCODE_BINARY -> return String(body, Charsets.UTF_8)
                else -> Unit // pong, continuation: nothing here sends either
            }
        }
    }

    private fun readByte(): Int = input.read().also { if (it < 0) error("the television closed the connection") }

    private fun sendControl(opcode: Int) {
        val mask = ByteArray(4).also(random::nextBytes)
        output.write(byteArrayOf((0x80 or opcode).toByte(), 0x80.toByte()) + mask)
        output.flush()
    }

    override fun close() {
        runCatching { sendControl(OPCODE_CLOSE) }
        runCatching { socket.close() }
    }

    private companion object {
        const val OPCODE_TEXT = 0x1
        const val OPCODE_BINARY = 0x2
        const val OPCODE_CLOSE = 0x8
        const val OPCODE_PING = 0x9
        const val OPCODE_PONG = 0xA
        const val MAX_FRAME = 1L shl 20
    }
}
