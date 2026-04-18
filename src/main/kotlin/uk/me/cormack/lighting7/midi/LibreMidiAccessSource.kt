package uk.me.cormack.lighting7.midi

import dev.atsushieno.ktmidi.LibreMidiAccess
import dev.atsushieno.ktmidi.MidiAccess
import dev.atsushieno.ktmidi.MidiInput
import dev.atsushieno.ktmidi.MidiOutput
import dev.atsushieno.ktmidi.MidiTransportProtocol
import dev.atsushieno.ktmidi.OnMidiReceivedEventListener

/**
 * Production [MidiAccessSource] backed by ktmidi's [LibreMidiAccess] (native libremidi
 * through the Panama FFM bindings). Wraps the ktmidi-specific types into our transport
 * abstraction so the rest of the control-surface stack stays library-agnostic and unit
 * testable without loading the native binary.
 */
class LibreMidiAccessSource(
    private val access: MidiAccess = LibreMidiAccess.create(MidiTransportProtocol.MIDI1),
) : MidiAccessSource {

    override val name: String get() = access.name

    override fun enumerateInputs(): List<MidiDevicePort> =
        access.inputs.map { it.toDevicePort(PortDirection.INPUT) }

    override fun enumerateOutputs(): List<MidiDevicePort> =
        access.outputs.map { it.toDevicePort(PortDirection.OUTPUT) }

    override suspend fun openInput(portId: String): MidiInputSource =
        KtMidiInputSource(access.openInput(portId))

    override suspend fun openOutput(portId: String): MidiSendTarget {
        val output = access.openOutput(portId)
        return KtMidiSendTarget(output)
    }

    override fun close() {
        // MidiAccess does not define a close() method; individual ports are closed by
        // their controllers. Nothing to release here.
    }

    private fun dev.atsushieno.ktmidi.MidiPortDetails.toDevicePort(direction: PortDirection): MidiDevicePort =
        MidiDevicePort(
            id = id,
            name = name ?: id,
            manufacturer = manufacturer,
            direction = direction,
        )

    private class KtMidiInputSource(private val input: MidiInput) : MidiInputSource {
        override fun setListener(listener: (ByteArray, Int, Int) -> Unit) {
            input.setMessageReceivedListener(
                OnMidiReceivedEventListener { data, start, length, _ ->
                    listener(data, start, length)
                },
            )
        }

        override fun close() {
            input.close()
        }
    }

    private class KtMidiSendTarget(private val output: MidiOutput) : MidiSendTarget {
        override fun send(bytes: ByteArray) {
            output.send(bytes, 0, bytes.size, 0L)
        }
    }
}
