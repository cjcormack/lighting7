package uk.me.cormack.lighting7.midi

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Minimal [MidiAccessSource] that backs tests of [MidiDeviceRegistry], [DeviceMatcher], and
 * [MidiLearnSessionManager]. Call sites mutate [inputs] / [outputs] between `tick()` calls
 * to simulate hot-plug events; no real backend is ever opened.
 *
 * [openInput] returns a no-op [MidiInputSource] and [openOutput] a no-op [MidiSendTarget],
 * which is sufficient for tests that observe registry / matcher state but don't drive byte
 * traffic — a test that wants the bytes builds its own controller over [RecordingSendTarget].
 */
internal class FakeMidiAccess : MidiAccessSource {
    override val name = "Fake"
    val inputs = CopyOnWriteArrayList<MidiDevicePort>()
    val outputs = CopyOnWriteArrayList<MidiDevicePort>()

    override fun enumerateInputs(): List<MidiDevicePort> = inputs.toList()
    override fun enumerateOutputs(): List<MidiDevicePort> = outputs.toList()

    override suspend fun openInput(portId: String): MidiInputSource = object : MidiInputSource {
        override fun setListener(listener: (ByteArray, Int, Int) -> Unit) {}
        override fun close() {}
    }

    override suspend fun openOutput(portId: String): MidiSendTarget = MidiSendTarget { }
}

/**
 * Outbound byte sink that records what actually reached the wire. Shared, because the
 * transport's delta suppression means "was a message queued" and "was a message sent" are
 * different questions, and both `MidiFeedbackConflationTest` and the publisher's resync test
 * ask the second one.
 */
internal class RecordingSendTarget : MidiSendTarget {
    val sent = CopyOnWriteArrayList<ByteArray>()
    override fun send(bytes: ByteArray) { sent.add(bytes.copyOf()) }
}
