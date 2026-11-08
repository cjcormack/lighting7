package uk.me.cormack.lighting7.osc

import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import kotlin.test.assertEquals

/** A loopback port free for both UDP and TCP, as the listener binds both on one number. */
internal fun freeOscPort(): Int {
    val loopback = InetAddress.getByName("127.0.0.1")
    repeat(20) {
        ServerSocket(0, 1, loopback).use { tcp ->
            val port = tcp.localPort
            val free = runCatching { DatagramSocket(InetSocketAddress(loopback, port)).close() }.isSuccess
            if (free) return port
        }
    }
    error("no port free for both UDP and TCP")
}

/**
 * Asserts the desk closed [socket]: a clean end of stream, or a reset where it closed with bytes
 * still unread, which Linux answers with RST.
 */
internal fun assertClosedByDesk(socket: Socket, message: String) {
    val end = try {
        socket.getInputStream().read()
    } catch (_: SocketException) {
        -1
    }
    assertEquals(-1, end, message)
}
