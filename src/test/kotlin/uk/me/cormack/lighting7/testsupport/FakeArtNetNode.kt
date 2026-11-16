package uk.me.cormack.lighting7.testsupport

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

/** An Art-Net node on 127.0.0.1 that answers each ArtPoll with an ArtPollReply naming 127.0.0.1, while [answering]. */
class FakeArtNetNode : AutoCloseable {
    private val socket = DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0)).also { it.soTimeout = 100 }

    @Volatile
    var answering = true

    /** The ArtPolls it has received. */
    val polls = AtomicInteger()

    @Volatile
    private var running = true

    private val thread = Thread {
        val buffer = ByteArray(1024)
        while (running) {
            val p = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(p)
            } catch (_: Exception) {
                continue
            }
            val op = (buffer[8].toInt() and 0xFF) or ((buffer[9].toInt() and 0xFF) shl 8)
            if (p.length < 12 || op != 0x2000) continue
            polls.incrementAndGet()
            if (answering) runCatching { socket.send(DatagramPacket(REPLY, REPLY.size, p.socketAddress)) }
        }
    }.apply {
        isDaemon = true
        start()
    }

    val port: Int get() = socket.localPort

    override fun close() {
        running = false
        thread.join(1000)
        socket.close()
    }

    companion object {
        /** An ArtPollReply's length, its IP 127.0.0.1 and its port 6454. */
        val REPLY: ByteArray = ByteArray(239).also {
            "Art-Net\u0000".toByteArray(Charsets.US_ASCII).copyInto(it)
            it[8] = 0x00
            it[9] = 0x21
            byteArrayOf(127, 0, 0, 1).copyInto(it, 10)
            it[14] = 0x36
            it[15] = 0x19
        }
    }
}
