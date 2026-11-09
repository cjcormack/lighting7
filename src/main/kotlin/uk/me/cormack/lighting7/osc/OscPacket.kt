package uk.me.cormack.lighting7.osc

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * OSC 1.0 packets, parsed by hand (OSC plan D9): a null-padded address, a type-tag string and
 * big-endian `i`, `f`, `s` and `b` arguments; `T` and `F` read as true and false, `h` and `d` read
 * whole. Any other tag drops its message with a reason. Bundles are unpacked to a depth of
 * [MAX_DEPTH] and at most [MAX_MESSAGES] messages a packet, past which the whole packet is dropped;
 * timetags are read and ignored, so every message runs on arrival.
 *
 * Runs on the receive thread, so it reads bytes and nothing else: no database, no show.
 */
object OscPacket {
    const val MAX_DEPTH = 8
    const val MAX_MESSAGES = 64

    private val BUNDLE = "#bundle".toByteArray(Charsets.US_ASCII) + 0

    /** One thing a packet held: a message, or why one (or the packet) could not be read. */
    sealed interface Item {
        data class Message(val message: OscMessage) : Item

        /** [address] is the message's when it got that far. */
        data class Bad(val address: String?, val reason: String) : Item
    }

    /** Every message in [length] bytes of [bytes], in packet order. */
    fun parse(bytes: ByteArray, length: Int = bytes.size): List<Item> {
        if (length <= 0) return listOf(Item.Bad(null, "empty packet"))
        val items = ArrayList<Item>()
        return try {
            readElement(ByteBuffer.wrap(bytes, 0, length).slice().order(ByteOrder.BIG_ENDIAN), 0, items)
            items
        } catch (e: PacketDropped) {
            listOf(Item.Bad(null, e.message ?: "bad packet"))
        }
    }

    /** A packet error that drops the whole packet, not one message. */
    private class PacketDropped(message: String) : Exception(message)

    /** A message error: the message is dropped and the rest of its bundle read. */
    private class MessageDropped(val address: String?, message: String) : Exception(message)

    private fun readElement(buffer: ByteBuffer, depth: Int, items: MutableList<Item>) {
        if (isBundle(buffer)) {
            if (depth >= MAX_DEPTH) throw PacketDropped("bundles nested deeper than $MAX_DEPTH")
            buffer.position(buffer.position() + BUNDLE.size)
            if (buffer.remaining() < 8) throw PacketDropped("bundle truncated before its timetag")
            buffer.position(buffer.position() + 8) // the timetag: every message runs on arrival
            while (buffer.hasRemaining()) {
                if (buffer.remaining() < 4) throw PacketDropped("bundle truncated in an element size")
                val size = buffer.int
                if (size < 0 || size > buffer.remaining()) throw PacketDropped("bundle element longer than the packet")
                if (size % 4 != 0) throw PacketDropped("bundle element of $size bytes is not a multiple of 4")
                val element = buffer.slice().order(ByteOrder.BIG_ENDIAN)
                element.limit(size)
                buffer.position(buffer.position() + size)
                readElement(element, depth + 1, items)
            }
        } else {
            if (items.size >= MAX_MESSAGES) throw PacketDropped("more than $MAX_MESSAGES messages in one packet")
            items += try {
                Item.Message(readMessage(buffer))
            } catch (e: MessageDropped) {
                Item.Bad(e.address, e.message ?: "bad message")
            }
        }
    }

    private fun isBundle(buffer: ByteBuffer): Boolean {
        if (buffer.remaining() < BUNDLE.size) return false
        val start = buffer.position()
        return BUNDLE.indices.all { buffer.get(start + it) == BUNDLE[it] }
    }

    private fun readMessage(buffer: ByteBuffer): OscMessage {
        val address = readString(buffer, null) ?: throw MessageDropped(null, "message truncated in its address")
        if (!address.startsWith("/")) throw MessageDropped(null, "address does not start with /")
        // A message with no type-tag string at all is OSC 1.0's older form: no arguments.
        if (!buffer.hasRemaining()) return OscMessage(address, emptyList())
        val tags = readString(buffer, address) ?: throw MessageDropped(address, "message truncated in its type tags")
        if (!tags.startsWith(",")) throw MessageDropped(address, "type tags do not start with ,")
        val args = ArrayList<OscArg>(tags.length - 1)
        for (tag in tags.substring(1)) {
            args += when (tag) {
                'i' -> OscArg.Int32(need(buffer, 4, address, tag).int)
                'f' -> OscArg.Float32(need(buffer, 4, address, tag).float)
                'h' -> OscArg.Int64(need(buffer, 8, address, tag).long)
                'd' -> OscArg.Float64(need(buffer, 8, address, tag).double)
                's' -> OscArg.Str(readString(buffer, address) ?: throw MessageDropped(address, "message truncated in a string argument"))
                'b' -> OscArg.Blob(readBlob(buffer, address))
                'T' -> OscArg.Bool(true)
                'F' -> OscArg.Bool(false)
                else -> throw MessageDropped(address, "type tag '$tag' is not read")
            }
        }
        return OscMessage(address, args)
    }

    private fun need(buffer: ByteBuffer, bytes: Int, address: String, tag: Char): ByteBuffer {
        if (buffer.remaining() < bytes) throw MessageDropped(address, "message truncated in its '$tag' argument")
        return buffer
    }

    /** A null-terminated string padded to four bytes, or null when the buffer ends first. */
    private fun readString(buffer: ByteBuffer, address: String?): String? {
        val start = buffer.position()
        var end = start
        while (end < buffer.limit() && buffer.get(end) != 0.toByte()) end++
        if (end >= buffer.limit()) return null
        val bytes = ByteArray(end - start).also { buffer.get(it) }
        val padded = (end - start + 4) and 3.inv()
        if (start + padded > buffer.limit()) {
            throw MessageDropped(address, "string padding runs past the end of the message")
        }
        buffer.position(start + padded)
        return String(bytes, Charsets.UTF_8)
    }

    private fun readBlob(buffer: ByteBuffer, address: String): ByteArray {
        if (buffer.remaining() < 4) throw MessageDropped(address, "message truncated in a blob's size")
        val size = buffer.int
        if (size < 0 || size > buffer.remaining()) throw MessageDropped(address, "blob longer than the message")
        val padded = (size + 3) and 3.inv()
        if (padded > buffer.remaining()) throw MessageDropped(address, "blob longer than the message")
        val bytes = ByteArray(size).also { buffer.get(it) }
        buffer.position(buffer.position() + (padded - size))
        return bytes
    }

    // ─── Encoding (replies and tests) ──────────────────────────────────────────────────────────

    /** One message as its packet. */
    fun encode(message: OscMessage): ByteArray {
        val out = ByteArrayOutputStream()
        writeString(out, message.address)
        writeString(out, "," + message.args.joinToString("") { it.tag.toString() })
        for (arg in message.args) {
            when (arg) {
                is OscArg.Int32 -> out.write(ByteBuffer.allocate(4).putInt(arg.value).array())
                is OscArg.Float32 -> out.write(ByteBuffer.allocate(4).putFloat(arg.value).array())
                is OscArg.Int64 -> out.write(ByteBuffer.allocate(8).putLong(arg.value).array())
                is OscArg.Float64 -> out.write(ByteBuffer.allocate(8).putDouble(arg.value).array())
                is OscArg.Str -> writeString(out, arg.value)
                is OscArg.Blob -> {
                    out.write(ByteBuffer.allocate(4).putInt(arg.value.size).array())
                    out.write(arg.value)
                    repeat((4 - arg.value.size % 4) % 4) { out.write(0) }
                }
                is OscArg.Bool -> Unit
            }
        }
        return out.toByteArray()
    }

    fun encode(address: String, vararg args: OscArg): ByteArray = encode(OscMessage(address, args.toList()))

    /** A bundle of already-encoded elements (messages or bundles), timetag "immediately". */
    fun bundle(vararg elements: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(BUNDLE)
        out.write(ByteBuffer.allocate(8).putLong(1L).array())
        for (element in elements) {
            out.write(ByteBuffer.allocate(4).putInt(element.size).array())
            out.write(element)
        }
        return out.toByteArray()
    }

    private fun writeString(out: ByteArrayOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        out.write(bytes)
        repeat(4 - bytes.size % 4) { out.write(0) }
    }
}

/** One OSC message: its address and arguments. */
data class OscMessage(val address: String, val args: List<OscArg>)

/** One OSC argument, by its type tag. */
sealed interface OscArg {
    val tag: Char

    data class Int32(val value: Int) : OscArg { override val tag get() = 'i' }
    data class Float32(val value: Float) : OscArg { override val tag get() = 'f' }
    data class Int64(val value: Long) : OscArg { override val tag get() = 'h' }
    data class Float64(val value: Double) : OscArg { override val tag get() = 'd' }
    data class Str(val value: String) : OscArg { override val tag get() = 's' }
    class Blob(val value: ByteArray) : OscArg {
        override val tag get() = 'b'
        override fun equals(other: Any?) = other is Blob && other.value.contentEquals(value)
        override fun hashCode() = value.contentHashCode()
        override fun toString() = "Blob(${value.size} bytes)"
    }
    data class Bool(val value: Boolean) : OscArg { override val tag get() = if (value) 'T' else 'F' }

    /**
     * The argument as a number, where an address wants one (D9): every numeric tag, `T` and `F` as 1
     * and 0; null for a string or a blob.
     */
    fun number(): Double? = when (this) {
        is Int32 -> value.toDouble()
        is Float32 -> value.toDouble()
        is Int64 -> value.toDouble()
        is Float64 -> value
        is Bool -> if (value) 1.0 else 0.0
        is Str, is Blob -> null
    }

    /** How the address log writes it. */
    fun render(): String = when (this) {
        is Int32 -> value.toString()
        is Float32 -> value.toString()
        is Int64 -> value.toString()
        is Float64 -> value.toString()
        is Str -> "\"${value.take(40)}\""
        is Blob -> "blob(${value.size})"
        is Bool -> if (value) "T" else "F"
    }
}
