package uk.me.cormack.lighting7.midi

/**
 * Fallback [MidiAccessSource] used when the platform's native MIDI backend can't be
 * loaded — notably libremidi-panama on Windows, whose generated FFM bindings assume a
 * 64-bit C `long` and crash with a [ClassCastException] on the LLP64 ABI. Lets the rest
 * of the show boot with control surfaces silently disabled instead of taking the whole
 * Ktor module down with the lazy MidiDeviceRegistry init.
 */
class NoOpMidiAccessSource : MidiAccessSource {
    override val name: String = "NoOp"

    override fun enumerateInputs(): List<MidiDevicePort> = emptyList()

    override fun enumerateOutputs(): List<MidiDevicePort> = emptyList()

    override suspend fun openInput(portId: String): MidiInputSource =
        error("NoOpMidiAccessSource has no input ports (asked for $portId)")

    override suspend fun openOutput(portId: String): MidiSendTarget =
        error("NoOpMidiAccessSource has no output ports (asked for $portId)")
}
