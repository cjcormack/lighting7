package uk.me.cormack.lighting7.osc

import java.io.ByteArrayOutputStream

/**
 * OSC 1.1's framing over a stream: double-ended SLIP (RFC 1055), each packet between two `END`
 * bytes, an `END` or `ESC` inside it escaped. QLab frames OSC over TCP this way.
 */
object OscSlip {
    const val END: Byte = 0xC0.toByte()
    const val ESC: Byte = 0xDB.toByte()
    const val ESC_END: Byte = 0xDC.toByte()
    const val ESC_ESC: Byte = 0xDD.toByte()

    /** [packet] as one frame. */
    fun encode(packet: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(packet.size + 8)
        out.write(END.toInt())
        for (b in packet) {
            when (b) {
                END -> { out.write(ESC.toInt()); out.write(ESC_END.toInt()) }
                ESC -> { out.write(ESC.toInt()); out.write(ESC_ESC.toInt()) }
                else -> out.write(b.toInt())
            }
        }
        out.write(END.toInt())
        return out.toByteArray()
    }

    /**
     * Frames out of a byte stream, fed as it arrives, so a frame split across reads is joined. A
     * frame longer than [maxFrame] bytes is dropped whole and reported to [onOversize]; an `ESC`
     * followed by anything but `ESC_END` or `ESC_ESC` keeps that byte, as RFC 1055 does. Empty
     * frames — the `END` that opens one after another closed — are skipped.
     */
    class Decoder(private val maxFrame: Int) {
        private val frame = ByteArray(maxFrame)
        private var size = 0
        private var escaping = false
        private var oversize = false

        fun feed(bytes: ByteArray, length: Int, onFrame: (ByteArray) -> Unit, onOversize: () -> Unit) {
            for (i in 0 until length) {
                val b = bytes[i]
                if (escaping) {
                    escaping = false
                    append(
                        when (b) {
                            ESC_END -> END
                            ESC_ESC -> ESC
                            else -> b
                        },
                    )
                    continue
                }
                when (b) {
                    END -> {
                        when {
                            oversize -> onOversize()
                            size > 0 -> onFrame(frame.copyOf(size))
                        }
                        size = 0
                        oversize = false
                    }
                    ESC -> escaping = true
                    else -> append(b)
                }
            }
        }

        private fun append(b: Byte) {
            if (oversize) return
            if (size == maxFrame) {
                oversize = true
                return
            }
            frame[size++] = b
        }
    }
}
