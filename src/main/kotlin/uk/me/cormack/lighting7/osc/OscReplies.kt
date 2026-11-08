package uk.me.cormack.lighting7.osc

import org.slf4j.LoggerFactory
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** Where a message's `/lighting7/reply` goes: a queue's [offer], which never blocks. */
fun interface OscReplyTo {
    fun send(packet: ByteArray)
}

/**
 * The `/lighting7/reply` answering one message (OSC plan D14): the address it carried, its outcome
 * as the log's word in lower case (`done`, `not_found`, …) and the log row's detail. Only an address
 * under `/lighting7/` is answered, and never a reply itself: a peer that acknowledges what it is sent
 * in its own namespace, or another desk with replies on, would otherwise answer the desk forever.
 */
fun oscReply(address: String?, outcome: OscOutcome, detail: String): ByteArray? {
    if (address == null || !address.startsWith("${OscAddresses.PREFIX}/", ignoreCase = true)) return null
    if (address.equals(OscReplies.ADDRESS, ignoreCase = true)) return null
    return OscPacket.encode(
        OscReplies.ADDRESS,
        OscArg.Str(address),
        OscArg.Str(outcome.name.lowercase()),
        OscArg.Str(detail),
    )
}

/**
 * Replies waiting for their socket, on a thread of their own: the collector only offers, so a
 * sender that stops reading — a TCP peer whose window is full — holds up nobody but itself. At most
 * [CAPACITY] wait; past that a reply is dropped, never the message it answers.
 */
internal class OscReplies<T : Any>(threadName: String, private val write: (T) -> Unit) : AutoCloseable {
    private val queue = ArrayBlockingQueue<T>(CAPACITY)

    @Volatile
    private var open = true

    private val thread = Thread({ run() }, threadName).apply {
        isDaemon = true
        start()
    }

    fun offer(packet: T) {
        if (open && !queue.offer(packet)) logger.debug("OSC reply dropped: {} waiting", CAPACITY)
    }

    private fun run() {
        while (open) {
            val packet = try {
                queue.poll(250, TimeUnit.MILLISECONDS) ?: continue
            } catch (_: InterruptedException) {
                break
            }
            try {
                write(packet)
            } catch (e: Exception) {
                if (open) logger.debug("OSC reply failed: {}", e.toString())
            }
        }
    }

    override fun close() {
        open = false
        thread.interrupt()
        queue.clear()
    }

    companion object {
        private val logger = LoggerFactory.getLogger(OscReplies::class.java)
        const val ADDRESS = "${OscAddresses.PREFIX}/reply"
        const val CAPACITY = 64
    }
}
