package uk.me.cormack.lighting7.midi

import kotlinx.coroutines.flow.SharedFlow
import uk.me.cormack.lighting7.dmx.PacketRateCounter

/**
 * Transport-layer abstraction for a single connected MIDI device. Mirrors the shape of
 * [uk.me.cormack.lighting7.dmx.DmxController] for the MIDI side: one implementation per
 * transport (Phase 0: [KtMidiController]; future: OSC, Network MIDI).
 *
 * Inbound events are a hot [SharedFlow]. Subscribers attach and detach freely; the controller
 * does not buffer arbitrary history. Outbound feedback is delivered via [sendFeedback], which
 * is conflated per-control and rate-limited inside the controller.
 *
 * Not sealed: test helpers in the `src/test` source set need to supply recording fakes, and
 * Kotlin treats the main / test compilation units as separate modules for sealed membership
 * purposes. Concrete implementations should still be kept to transport classes.
 */
interface MidiController {
    val handle: MidiDeviceHandle
    val input: SharedFlow<MidiInputEvent>

    /**
     * Per-port sliding-window counters for inbound / outbound Control Change traffic.
     * Recorded by the transport implementation; surfaced via [MidiDeviceRegistry.portCcRates]
     * for the perf endpoint. Sysex / NoteOn / NoteOff are not counted — the diagnostic target
     * is fader-flood traffic.
     */
    val inboundCcRate: PacketRateCounter
    val outboundCcRate: PacketRateCounter

    /**
     * Queue a feedback message for transmission. Multiple messages targeting the same
     * [MidiControlKey] are coalesced — only the latest value is actually sent on the next
     * transmission tick. Messages whose encoded bytes match the most recently sent value for
     * the same key are suppressed (delta tracking).
     */
    fun sendFeedback(message: MidiFeedbackMessage)

    /**
     * Forget the last-sent bytes for [key], so the next [sendFeedback] targeting the same key
     * always transmits even if its bytes match what we previously sent. Used to force-reassert
     * state after device-side local handling may have overwritten it (e.g. X-Touch Compact
     * buttons in Momentary LED mode clearing the LED on physical release).
     */
    fun invalidateFeedbackCache(key: MidiControlKey)

    /**
     * Forget the last-sent bytes for *every* key on this device, so the next round of
     * [sendFeedback] transmits in full even where the recomputed value matches what we last
     * sent. The device-wide counterpart of [invalidateFeedbackCache], for the case where the
     * hardware's whole state may have been reset behind us — a power cycle, a device reset
     * button, a replug the registry did not notice, or dropped messages.
     *
     * Called by `SurfaceFeedbackPublisher.sendFullResync` when it is re-arming pickup: that
     * flag already means "the physical position is stale", and a resync that trusted the
     * delta cache would leave every unchanged control where the hardware happens to have it.
     * Delta suppression itself stays — it is load-bearing on the DMX-driven feedback path,
     * where a bound channel can move at frame rate.
     */
    fun invalidateAllFeedback()

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
