package uk.me.cormack.lighting7.midi

/**
 * Identifies a single conflation target for outbound MIDI. Two feedback messages sharing
 * the same key overwrite each other in the transmission pipeline (last write wins).
 *
 * For channel-voice messages `(channel, type, id)` uniquely identifies the knob/button/fader
 * on the wire. SysEx coalesces on a caller-supplied `id` so distinct SysEx streams can coexist.
 */
data class MidiControlKey(val channel: Int, val type: Type, val id: Int) {
    enum class Type { NOTE, CC, PITCH_BEND, SYSEX }
}

/** Structured outbound MIDI message. `encode()` emits the bytes placed on the wire. */
sealed class MidiFeedbackMessage {
    abstract val controlKey: MidiControlKey
    abstract fun encode(): ByteArray

    data class NoteOnFeedback(val channel: Int, val note: Int, val velocity: UByte) : MidiFeedbackMessage() {
        override val controlKey get() = MidiControlKey(channel, MidiControlKey.Type.NOTE, note)
        override fun encode(): ByteArray = encodeChannelMessage(0x90, channel, note, velocity.toInt())
    }

    data class NoteOffFeedback(val channel: Int, val note: Int, val velocity: UByte = 0u) : MidiFeedbackMessage() {
        override val controlKey get() = MidiControlKey(channel, MidiControlKey.Type.NOTE, note)
        override fun encode(): ByteArray = encodeChannelMessage(0x80, channel, note, velocity.toInt())
    }

    data class ControlChangeFeedback(val channel: Int, val cc: Int, val value: UByte) : MidiFeedbackMessage() {
        override val controlKey get() = MidiControlKey(channel, MidiControlKey.Type.CC, cc)
        override fun encode(): ByteArray = encodeChannelMessage(0xB0, channel, cc, value.toInt())
    }

    data class PitchBendFeedback(val channel: Int, val value: UShort) : MidiFeedbackMessage() {
        override val controlKey get() = MidiControlKey(channel, MidiControlKey.Type.PITCH_BEND, 0)
        override fun encode(): ByteArray {
            val v = value.toInt() and 0x3FFF
            return encodeChannelMessage(0xE0, channel, v and 0x7F, (v shr 7) and 0x7F)
        }
    }

    /**
     * [streamId] segregates independent SysEx streams so overlapping transfers to different
     * devices/subsystems don't conflate.
     */
    data class SysExFeedback(val streamId: Int, val payload: ByteArray) : MidiFeedbackMessage() {
        override val controlKey get() = MidiControlKey(0, MidiControlKey.Type.SYSEX, streamId)
        override fun encode(): ByteArray {
            val out = ByteArray(payload.size + 2)
            out[0] = 0xF0.toByte()
            payload.copyInto(out, 1)
            out[out.size - 1] = 0xF7.toByte()
            return out
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is SysExFeedback) return false
            return streamId == other.streamId && payload.contentEquals(other.payload)
        }
        override fun hashCode(): Int = 31 * streamId + payload.contentHashCode()
    }
}

private fun encodeChannelMessage(statusHighNibble: Int, channel: Int, data1: Int, data2: Int): ByteArray =
    byteArrayOf(
        (statusHighNibble or (channel and 0x0F)).toByte(),
        (data1 and 0x7F).toByte(),
        (data2 and 0x7F).toByte(),
    )

