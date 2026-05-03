package uk.me.cormack.lighting7.midi

/**
 * Last-ditch fallback so the show boots with control surfaces silently disabled rather than
 * taking the whole Ktor module down with the lazy [MidiDeviceRegistry] init.
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
