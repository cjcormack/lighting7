package uk.me.cormack.lighting7.midi

/**
 * Structured inbound MIDI events emitted by [MidiController.input].
 *
 * Channel indices are 0-based (0..15) to match the wire encoding.
 * Note and CC numbers are 0..127; velocities and CC values are 0..127 carried as [UByte].
 * Pitch-bend values are 0..16383 centred at 8192, carried as [UShort].
 */
sealed class MidiInputEvent {
    data class NoteOn(val channel: Int, val note: Int, val velocity: UByte) : MidiInputEvent()
    data class NoteOff(val channel: Int, val note: Int, val velocity: UByte) : MidiInputEvent()
    data class ControlChange(val channel: Int, val cc: Int, val value: UByte) : MidiInputEvent()
    data class ProgramChange(val channel: Int, val program: Int) : MidiInputEvent()
    data class PitchBend(val channel: Int, val value: UShort) : MidiInputEvent()
    data class SysEx(val bytes: ByteArray) : MidiInputEvent() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is SysEx) return false
            return bytes.contentEquals(other.bytes)
        }
        override fun hashCode(): Int = bytes.contentHashCode()
    }
}

/**
 * Stateful MIDI byte-stream parser. A single instance should handle bytes from one input port
 * in order. Handles channel-voice messages, running status, and multi-packet SysEx accumulation.
 * System real-time bytes (0xF8..0xFF) are swallowed; non-SysEx System Common messages are ignored
 * for now since none of them are relevant to control-surface traffic at this phase.
 */
class MidiMessageParser {
    private var runningStatus: Int = 0
    private var sysExBuffer: ByteArrayBuilder? = null

    /**
     * Feed raw bytes into the parser. Accepts a slice `[offset, offset + length)`.
     * Emits zero or more [MidiInputEvent]s via [emit] as complete messages are decoded.
     */
    fun parse(bytes: ByteArray, offset: Int, length: Int, emit: (MidiInputEvent) -> Unit) {
        var i = offset
        val end = offset + length
        while (i < end) {
            val b = bytes[i].toInt() and 0xFF
            when {
                b == 0xF0 -> {
                    sysExBuffer = ByteArrayBuilder()
                    i++
                }
                b == 0xF7 -> {
                    val buf = sysExBuffer
                    if (buf != null) {
                        emit(MidiInputEvent.SysEx(buf.toByteArray()))
                        sysExBuffer = null
                    }
                    i++
                }
                b >= 0xF8 -> {
                    // System real-time byte; may interleave with other messages. Ignored.
                    i++
                }
                b in 0xF1..0xF6 -> {
                    // System Common messages (MTC, Song Select, Tune Request, etc.). Skip, but
                    // clear running status per MIDI spec.
                    runningStatus = 0
                    val expected = systemCommonDataBytes(b)
                    i = (i + 1 + expected).coerceAtMost(end)
                }
                b >= 0x80 -> {
                    // Channel voice / mode status byte. Store running status and move on —
                    // don't eagerly consume the following bytes as data, because a system
                    // real-time byte (0xF8..0xFF) may interleave before the data arrives.
                    // The next iteration will hit the running-status path and decode.
                    runningStatus = b
                    i++
                }
                else -> {
                    // Data byte arrived without a new status — use running status, or bury in SysEx.
                    if (sysExBuffer != null) {
                        sysExBuffer!!.append(b.toByte())
                        i++
                    } else if (runningStatus != 0) {
                        i = decodeChannelMessage(runningStatus, bytes, i, end, emit)
                    } else {
                        // Stray data byte with no status. Drop.
                        i++
                    }
                }
            }
        }
    }

    private fun decodeChannelMessage(
        status: Int,
        bytes: ByteArray,
        start: Int,
        end: Int,
        emit: (MidiInputEvent) -> Unit,
    ): Int {
        val type = status and 0xF0
        val channel = status and 0x0F
        return when (type) {
            0x80 -> { // Note Off
                if (end - start < 2) end
                else {
                    val note = bytes[start].toInt() and 0x7F
                    val velocity = (bytes[start + 1].toInt() and 0x7F).toUByte()
                    emit(MidiInputEvent.NoteOff(channel, note, velocity))
                    start + 2
                }
            }
            0x90 -> { // Note On (velocity 0 → Note Off per MIDI spec)
                if (end - start < 2) end
                else {
                    val note = bytes[start].toInt() and 0x7F
                    val velocity = (bytes[start + 1].toInt() and 0x7F).toUByte()
                    if (velocity == 0u.toUByte()) emit(MidiInputEvent.NoteOff(channel, note, velocity))
                    else emit(MidiInputEvent.NoteOn(channel, note, velocity))
                    start + 2
                }
            }
            0xA0 -> { // Polyphonic Aftertouch — 2 data bytes, ignored.
                if (end - start < 2) end else start + 2
            }
            0xB0 -> { // Control Change
                if (end - start < 2) end
                else {
                    val cc = bytes[start].toInt() and 0x7F
                    val value = (bytes[start + 1].toInt() and 0x7F).toUByte()
                    emit(MidiInputEvent.ControlChange(channel, cc, value))
                    start + 2
                }
            }
            0xC0 -> { // Program Change — 1 data byte.
                if (end - start < 1) end
                else {
                    val program = bytes[start].toInt() and 0x7F
                    emit(MidiInputEvent.ProgramChange(channel, program))
                    start + 1
                }
            }
            0xD0 -> { // Channel Aftertouch — 1 data byte, ignored.
                if (end - start < 1) end else start + 1
            }
            0xE0 -> { // Pitch Bend (LSB, MSB)
                if (end - start < 2) end
                else {
                    val lsb = bytes[start].toInt() and 0x7F
                    val msb = bytes[start + 1].toInt() and 0x7F
                    val value = ((msb shl 7) or lsb).toUShort()
                    emit(MidiInputEvent.PitchBend(channel, value))
                    start + 2
                }
            }
            else -> start
        }
    }

    private fun systemCommonDataBytes(status: Int): Int = when (status) {
        0xF1, 0xF3 -> 1
        0xF2 -> 2
        else -> 0
    }

    private class ByteArrayBuilder {
        private var buf = ByteArray(32)
        private var size = 0

        fun append(b: Byte) {
            if (size == buf.size) buf = buf.copyOf(buf.size * 2)
            buf[size++] = b
        }

        fun toByteArray(): ByteArray = buf.copyOf(size)
    }
}
