package uk.me.cormack.lighting7.midi

import dev.atsushieno.ktmidi.JvmMidiAccess
import dev.atsushieno.ktmidi.LibreMidiAccess
import dev.atsushieno.ktmidi.MidiAccess
import dev.atsushieno.ktmidi.MidiInput
import dev.atsushieno.ktmidi.MidiOutput
import dev.atsushieno.ktmidi.MidiTransportProtocol
import dev.atsushieno.ktmidi.OnMidiReceivedEventListener

class KtmidiAccessSource(
    private val access: MidiAccess,
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

// Windows has no usable libremidi-panama binary (no arm64 .dll; the x64 .dll trips an LLP64
// ABI bug). Fall back to javax.sound.midi there, filtering out the four pure-software
// pseudo-devices the JVM and Windows always enumerate — Microsoft MIDI Mapper is a
// system-wide router that's always held open (auto-open fails with "already in use"); the
// others are software synths / sequencers that would burn a MidiThread-* per device for no
// purpose since they're never control surfaces.
fun createPlatformKtmidiAccessSource(): MidiAccessSource {
    val os = System.getProperty("os.name")?.lowercase().orEmpty()
    return if (os.contains("windows")) {
        FilteredMidiAccessSource(KtmidiAccessSource(JvmMidiAccess()), denied = WINDOWS_PSEUDO_DEVICES)
    } else {
        KtmidiAccessSource(LibreMidiAccess.create(MidiTransportProtocol.MIDI1))
    }
}

private val WINDOWS_PSEUDO_DEVICES = setOf(
    "Microsoft MIDI Mapper",
    "Microsoft GS Wavetable Synth",
    "Real Time Sequencer",
    "Gervill",
)
