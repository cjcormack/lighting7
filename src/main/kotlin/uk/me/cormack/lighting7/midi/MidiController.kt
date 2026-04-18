package uk.me.cormack.lighting7.midi

import kotlinx.coroutines.flow.SharedFlow

/**
 * Transport-layer abstraction for a single connected MIDI device. Mirrors the shape of
 * [uk.me.cormack.lighting7.dmx.DmxController] for the MIDI side: one implementation per
 * transport (Phase 0: [KtMidiController]; future: OSC, Network MIDI).
 *
 * Inbound events are a hot [SharedFlow]. Subscribers attach and detach freely; the controller
 * does not buffer arbitrary history. Outbound feedback is delivered via [sendFeedback], which
 * is conflated per-control and rate-limited inside the controller.
 */
sealed interface MidiController {
    val handle: MidiDeviceHandle
    val input: SharedFlow<MidiInputEvent>

    /**
     * Queue a feedback message for transmission. Multiple messages targeting the same
     * [MidiControlKey] are coalesced — only the latest value is actually sent on the next
     * transmission tick. Messages whose encoded bytes match the most recently sent value for
     * the same key are suppressed (delta tracking).
     */
    fun sendFeedback(message: MidiFeedbackMessage)

    fun close()
}

/**
 * Test-injectable outbound byte sink. Production wraps `MidiOutput.send`; tests record calls.
 */
fun interface MidiSendTarget {
    fun send(bytes: ByteArray)
}

/**
 * Test-injectable inbound byte source. Production wires `MidiInput.setMessageReceivedListener`;
 * tests call [emit] directly to pump synthetic bytes.
 */
interface MidiInputSource {
    fun setListener(listener: (ByteArray, Int, Int) -> Unit)
    fun close()
}

/**
 * Abstraction over the platform MIDI access layer. Production wraps `LibreMidiAccess`;
 * tests provide a fake with mutable input/output lists.
 */
interface MidiAccessSource {
    /** Name of the underlying platform API (e.g. "LibreMidi/CoreMIDI"). */
    val name: String

    /** Current set of input ports. Called repeatedly by the poller. */
    fun enumerateInputs(): List<MidiDevicePort>

    /** Current set of output ports. Called repeatedly by the poller. */
    fun enumerateOutputs(): List<MidiDevicePort>

    /** Open an input port by its [MidiDevicePort.id]. */
    suspend fun openInput(portId: String): MidiInputSource

    /** Open an output port by its [MidiDevicePort.id]. */
    suspend fun openOutput(portId: String): MidiSendTarget

    /** Release any backend resources. Called once at shutdown. */
    fun close() {}
}
