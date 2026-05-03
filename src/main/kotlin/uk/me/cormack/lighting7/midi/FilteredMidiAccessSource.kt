package uk.me.cormack.lighting7.midi

class FilteredMidiAccessSource(
    private val inner: MidiAccessSource,
    private val denied: Set<String>,
) : MidiAccessSource {
    override val name: String get() = inner.name
    override fun enumerateInputs(): List<MidiDevicePort> = inner.enumerateInputs().filter { it.name !in denied }
    override fun enumerateOutputs(): List<MidiDevicePort> = inner.enumerateOutputs().filter { it.name !in denied }
    override suspend fun openInput(portId: String): MidiInputSource = inner.openInput(portId)
    override suspend fun openOutput(portId: String): MidiSendTarget = inner.openOutput(portId)
    override fun close() = inner.close()
}
