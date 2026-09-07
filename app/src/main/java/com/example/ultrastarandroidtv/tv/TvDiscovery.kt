package com.example.ultrastarandroidtv.tv

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

private const val SSDP_ADDRESS = "239.255.255.250"
private const val SSDP_PORT = 1900

/**
 * The service a webOS television answers to.
 *
 * LG's own, rather than `ssdp:all` or a generic UPnP root. Asking for this exactly is what makes
 * the search a one-line answer instead of a list of every printer, speaker and NAS on the network
 * that then has to be sifted. Measured on a C1: it replies with `Server: WebOS/4.1.0 UPnP/1.0`.
 */
private const val WEBOS_SERVICE = "urn:lge-com:service:webos-second-screen:1"

/** A television that answered, and where it is. */
data class FoundTv(val host: String, val description: String)

/**
 * Looks for a webOS television on this network.
 *
 * ## Why discovery rather than an address somebody types
 *
 * Typing an IP address on a television remote, through a drawn keyboard, is the worst input task
 * this app could ask for — and it would be wrong again the next time the router handed out a
 * different lease. A search takes two seconds and asks nothing of anybody.
 *
 * The address that comes back is still *remembered*, because a set that has not moved answers on
 * its old address immediately and a search is two seconds nobody needs to spend. See [TvMemory].
 *
 * Blocking, and returns whatever answered within [timeoutMillis]. An empty list is the ordinary
 * answer when the television is switched off: a webOS set drops off the network entirely in
 * standby, ports and SSDP alike.
 */
fun discoverWebOsTv(timeoutMillis: Int = 2_500): List<FoundTv> {
    val search = buildString {
        append("M-SEARCH * HTTP/1.1\r\n")
        append("HOST: ").append(SSDP_ADDRESS).append(':').append(SSDP_PORT).append("\r\n")
        append("MAN: \"ssdp:discover\"\r\n")
        append("MX: 2\r\n")
        append("ST: ").append(WEBOS_SERVICE).append("\r\n\r\n")
    }.toByteArray(Charsets.US_ASCII)

    val found = LinkedHashMap<String, FoundTv>()
    runCatching {
        DatagramSocket().use { socket ->
            socket.soTimeout = RECEIVE_SLICE_MILLIS
            socket.reuseAddress = true
            val target = InetAddress.getByName(SSDP_ADDRESS)
            // Three times, because a search is a single UDP datagram and a lost one is a
            // television that appears not to exist.
            repeat(3) { socket.send(DatagramPacket(search, search.size, target, SSDP_PORT)) }

            val deadline = System.currentTimeMillis() + timeoutMillis
            val buffer = ByteArray(2048)
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buffer, buffer.size)
                // A receive timing out is this loop's own clock ticking rather than a failure:
                // the socket is polled in slices so the deadline is honoured either way.
                val arrived = runCatching { socket.receive(packet); true }.getOrDefault(false)
                if (!arrived) continue
                val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                if (!text.contains(WEBOS_SERVICE)) continue
                val host = packet.address?.hostAddress ?: continue
                val server = text.lineSequence()
                    .firstOrNull { it.startsWith("Server:", ignoreCase = true) }
                    ?.substringAfter(':')?.trim().orEmpty()
                found.putIfAbsent(host, FoundTv(host, server))
            }
        }
    }
    return found.values.toList()
}

private const val RECEIVE_SLICE_MILLIS = 400
