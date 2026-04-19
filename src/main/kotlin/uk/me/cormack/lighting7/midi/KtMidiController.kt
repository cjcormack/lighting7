package uk.me.cormack.lighting7.midi

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ticker
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Transport implementation backed by an abstracted [MidiSendTarget] / [MidiInputSource]
 * pair. Production wires these to ktmidi's [MidiOutput] / [MidiInput]; tests inject doubles.
 *
 * Concurrency shape mirrors
 * [uk.me.cormack.lighting7.dmx.ArtNetController]:
 * - one dedicated single-thread context per device for the transmission loop
 * - per-[MidiControlKey] conflated channel for outbound queueing (last-write-wins)
 * - a [Channel.CONFLATED] `pendingSignal` acts as the "there's work" flag; on each tick the
 *   loop drains only when this flag is set, so idle ticks do no work
 * - delta suppression against `lastSentBytes` before calling [MidiSendTarget.send]
 * - consecutive-error backoff
 */
@OptIn(DelicateCoroutinesApi::class, ObsoleteCoroutinesApi::class, ExperimentalCoroutinesApi::class)
class KtMidiController internal constructor(
    override val handle: MidiDeviceHandle,
    private val sendTarget: MidiSendTarget?,
    private val inputSource: MidiInputSource?,
    private val transmitIntervalMs: Long = DEFAULT_TRANSMIT_INTERVAL_MS,
    parentScope: CoroutineScope = GlobalScope,
    // Invoked once when the transmission loop gives up; registry uses this to surface a
    // Disconnected event when libremidi's enumeration still lists a physically-gone device.
    private val onTransmissionGaveUp: (() -> Unit)? = null,
) : MidiController {

    companion object {
        /** 60 Hz — matches the feel of the ArtNet 25 ms loop, slightly faster to keep LED response crisp. */
        const val DEFAULT_TRANSMIT_INTERVAL_MS: Long = 17L
    }

    private val _input = MutableSharedFlow<MidiInputEvent>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val input: SharedFlow<MidiInputEvent> = _input.asSharedFlow()

    private val parser = MidiMessageParser()

    private val perKeyChannels = ConcurrentHashMap<MidiControlKey, Channel<MidiFeedbackMessage>>()
    private val lastSentBytes = ConcurrentHashMap<MidiControlKey, ByteArray>()
    private val pendingSignal = Channel<Unit>(Channel.CONFLATED)

    private val threadContext = newSingleThreadContext("MidiThread-${handle.displayKey}")
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val transmitJob: Job
    private var closed = false

    init {
        inputSource?.setListener { bytes, offset, length ->
            parser.parse(bytes, offset, length) { event ->
                _input.tryEmit(event)
            }
        }

        transmitJob = if (sendTarget != null) {
            scope.launch(threadContext) { runTransmissionLoop(sendTarget) }
        } else {
            Job().apply { complete() }
        }
    }

    override fun sendFeedback(message: MidiFeedbackMessage) {
        if (closed || sendTarget == null) return
        val key = message.controlKey
        val channel = perKeyChannels.computeIfAbsent(key) { Channel(Channel.CONFLATED) }
        channel.trySend(message)
        pendingSignal.trySend(Unit)
    }

    override fun close() {
        if (closed) return
        closed = true
        pendingSignal.close()
        perKeyChannels.values.forEach { it.close() }
        inputSource?.close()
        scope.cancel()
        threadContext.close()
    }

    private suspend fun runTransmissionLoop(target: MidiSendTarget) {
        // Default initialDelayMillis == delayMillis, so a long transmitIntervalMs in tests
        // means the first tick is also far in the future — tests drive drains via flushForTest().
        val tick = ticker(delayMillis = transmitIntervalMs)
        var consecutiveErrors = 0
        try {
            while (currentCoroutineContext().isActive) {
                val result = tick.receiveCatching()
                if (result.isClosed) break
                try {
                    if (pendingSignal.tryReceive().isSuccess) {
                        drainAndSend(target)
                    }
                    consecutiveErrors = 0
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    consecutiveErrors++
                    if (consecutiveErrors == 1) t.printStackTrace()
                    if (consecutiveErrors > 20) {
                        System.err.println("KtMidiController[${handle.displayKey}] giving up after $consecutiveErrors consecutive errors")
                        onTransmissionGaveUp?.invoke()
                        break
                    }
                    delay(transmitIntervalMs)
                }
            }
        } finally {
            tick.cancel()
        }
    }

    private fun drainAndSend(target: MidiSendTarget) {
        val pending = HashMap<MidiControlKey, MidiFeedbackMessage>()
        for ((key, channel) in perKeyChannels) {
            var latest: MidiFeedbackMessage? = null
            while (true) {
                val result = channel.tryReceive()
                if (result.isSuccess) latest = result.getOrNull()
                else break
            }
            if (latest != null) pending[key] = latest
        }

        for ((key, message) in pending) {
            val bytes = message.encode()
            val previous = lastSentBytes[key]
            if (previous != null && previous.contentEquals(bytes)) continue
            target.send(bytes)
            lastSentBytes[key] = bytes
        }
    }

    // Test seam — drains deterministically without waiting on the real ticker.
    internal fun flushForTest() {
        val target = sendTarget ?: return
        drainAndSend(target)
    }
}
