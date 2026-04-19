package uk.me.cormack.lighting7.midi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MidiParserTest {

    private fun parse(vararg bytes: Int): List<MidiInputEvent> {
        val events = mutableListOf<MidiInputEvent>()
        val parser = MidiMessageParser()
        val bytesArr = ByteArray(bytes.size) { bytes[it].toByte() }
        parser.parse(bytesArr, 0, bytesArr.size) { events.add(it) }
        return events
    }

    @Test
    fun `note on decodes channel note and velocity`() {
        val events = parse(0x90, 0x3C, 0x64)
        assertEquals(listOf(MidiInputEvent.NoteOn(0, 60, 100u)), events)
    }

    @Test
    fun `note on with velocity zero decodes as note off per MIDI spec`() {
        val events = parse(0x91, 0x40, 0x00)
        assertEquals(listOf(MidiInputEvent.NoteOff(1, 64, 0u)), events)
    }

    @Test
    fun `explicit note off`() {
        val events = parse(0x82, 0x2A, 0x40)
        assertEquals(listOf(MidiInputEvent.NoteOff(2, 42, 64u)), events)
    }

    @Test
    fun `control change decodes channel cc and value`() {
        val events = parse(0xB0, 0x07, 0x7F)
        assertEquals(listOf(MidiInputEvent.ControlChange(0, 7, 127u)), events)
    }

    @Test
    fun `pitch bend combines LSB then MSB into 14-bit value`() {
        // 0xE0 status, lsb=0x40, msb=0x20 → value = (0x20<<7) | 0x40 = 0x1040 = 4160
        val events = parse(0xE0, 0x40, 0x20)
        assertEquals(listOf(MidiInputEvent.PitchBend(0, 4160u)), events)
    }

    @Test
    fun `pitch bend centre is 8192`() {
        val events = parse(0xE5, 0x00, 0x40)
        assertEquals(listOf(MidiInputEvent.PitchBend(5, 8192u)), events)
    }

    @Test
    fun `pitch bend max is 16383`() {
        val events = parse(0xE0, 0x7F, 0x7F)
        assertEquals(listOf(MidiInputEvent.PitchBend(0, 16383u)), events)
    }

    @Test
    fun `running status applies subsequent data-only bytes`() {
        // Three note-on messages on channel 0 — status byte sent once.
        val events = parse(0x90, 0x3C, 0x40, 0x3D, 0x50, 0x3E, 0x60)
        assertEquals(
            listOf(
                MidiInputEvent.NoteOn(0, 60, 64u),
                MidiInputEvent.NoteOn(0, 61, 80u),
                MidiInputEvent.NoteOn(0, 62, 96u),
            ),
            events,
        )
    }

    @Test
    fun `single-frame SysEx emitted on F7`() {
        val events = parse(0xF0, 0x7E, 0x7F, 0x06, 0x01, 0xF7)
        assertEquals(1, events.size)
        val sysex = events.first() as MidiInputEvent.SysEx
        assertTrue(
            sysex.bytes.contentEquals(byteArrayOf(0x7E, 0x7F, 0x06, 0x01)),
            "expected payload without wrapping F0/F7, got ${sysex.bytes.toList()}",
        )
    }

    @Test
    fun `fragmented SysEx across parse calls`() {
        val parser = MidiMessageParser()
        val events = mutableListOf<MidiInputEvent>()
        parser.parse(byteArrayOf(0xF0.toByte(), 0x01, 0x02), 0, 3) { events.add(it) }
        assertEquals(0, events.size)
        parser.parse(byteArrayOf(0x03, 0x04, 0xF7.toByte()), 0, 3) { events.add(it) }
        assertEquals(1, events.size)
        val sysex = events.first() as MidiInputEvent.SysEx
        assertTrue(sysex.bytes.contentEquals(byteArrayOf(0x01, 0x02, 0x03, 0x04)))
    }

    @Test
    fun `real-time bytes between message bytes are ignored without breaking the message`() {
        // Clock tick (0xF8) interleaved inside a note-on.
        val events = parse(0x90, 0xF8, 0x3C, 0x40)
        assertEquals(listOf(MidiInputEvent.NoteOn(0, 60, 64u)), events)
    }

    @Test
    fun `stray data byte with no running status is silently dropped`() {
        val events = parse(0x3C, 0x40, 0x90, 0x3C, 0x40)
        assertEquals(listOf(MidiInputEvent.NoteOn(0, 60, 64u)), events)
    }

    @Test
    fun `offset and length respected`() {
        val parser = MidiMessageParser()
        val buf = byteArrayOf(0x00, 0x00, 0x90.toByte(), 0x3C, 0x40, 0x00)
        val events = mutableListOf<MidiInputEvent>()
        parser.parse(buf, 2, 3) { events.add(it) }
        assertEquals<List<MidiInputEvent>>(listOf(MidiInputEvent.NoteOn(0, 60, 64u)), events)
    }

    @Test
    fun `program change and aftertouch consume the right number of data bytes`() {
        // 0xC0 program change (1 byte) followed by a note-on, all on the same parse.
        val events = parse(0xC0, 0x05, 0x90, 0x3C, 0x40)
        assertEquals(
            listOf<MidiInputEvent>(
                MidiInputEvent.ProgramChange(channel = 0, program = 5),
                MidiInputEvent.NoteOn(0, 60, 64u),
            ),
            events,
        )
    }
}
