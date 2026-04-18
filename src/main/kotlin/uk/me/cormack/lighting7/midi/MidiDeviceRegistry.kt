package uk.me.cormack.lighting7.midi

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 0 device registry. Owns the [MidiAccessSource], polls for connected MIDI ports on a
 * fixed interval, pairs ports into [MidiDeviceHandle]s, and emits connect/disconnect events.
 *
 * Hot-plug via polling: `LibreMidiAccess` does not expose native device-added/removed events
 * on any platform (see ktmidi's `canDetectStateChanges = false` default), so we diff the
 * enumerated port lists instead. Polling is cheap and the diff is exact.
 *
 * When [autoOpen] is true (the default), a controller is transparently opened for every
 * newly-connected handle and exposed via [controllerFor]. On disconnect the controller is
 * closed. This is the path the [ConsoleEchoListener] and future Phase 1+ subsystems use.
 */
class MidiDeviceRegistry(
    private val access: MidiAccessSource,
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    private val autoOpen: Boolean = true,
) {
    companion object {
        const val DEFAULT_POLL_INTERVAL_MS: Long = 1000L
        private val logger = LoggerFactory.getLogger(MidiDeviceRegistry::class.java)
    }

    sealed class DeviceEvent {
        abstract val handle: MidiDeviceHandle
        data class Connected(override val handle: MidiDeviceHandle) : DeviceEvent()
        data class Disconnected(override val handle: MidiDeviceHandle) : DeviceEvent()
    }

    private val _devices = MutableStateFlow<List<MidiDeviceHandle>>(emptyList())
    val devices: StateFlow<List<MidiDeviceHandle>> = _devices.asStateFlow()

    private val _events = MutableSharedFlow<DeviceEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    val events: SharedFlow<DeviceEvent> = _events.asSharedFlow()

    private val controllers = ConcurrentHashMap<String, MidiController>()
    private var pollJob: Job? = null
    private var scope: CoroutineScope? = null

    fun start(parentScope: CoroutineScope) {
        if (pollJob != null) return
        scope = parentScope
        pollJob = parentScope.launch(CoroutineName("MidiDeviceRegistry-poll")) {
            pollLoop()
        }
    }

    fun close() {
        pollJob?.cancel()
        pollJob = null
        controllers.values.forEach { runCatching { it.close() } }
        controllers.clear()
        access.close()
    }

    /** Returns the open controller for a handle's display key, or null if not open. */
    fun controllerFor(displayKey: String): MidiController? = controllers[displayKey]

    /**
     * Open a handle explicitly. Safe to call when [autoOpen] is false; a no-op if a controller
     * is already open for this handle. Returns the (existing or new) controller.
     */
    suspend fun openController(handle: MidiDeviceHandle): MidiController {
        controllers[handle.displayKey]?.let { return it }
        return doOpen(handle).also { controllers[handle.displayKey] = it }
    }

    private suspend fun pollLoop() {
        while (currentCoroutineContext().isActive) {
            try {
                tick()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                logger.warn("MidiDeviceRegistry poll failed: ${t.message}", t)
            }
            delay(pollIntervalMs)
        }
    }

    // Exposed for testing — runs one diff cycle.
    internal suspend fun tick() {
        val ports = access.enumerateInputs() + access.enumerateOutputs()
        val newHandles = MidiDeviceHandle.pair(ports).sortedBy { it.displayKey }
        val previous = _devices.value

        val previousByKey = previous.associateBy { it.displayKey }
        val newByKey = newHandles.associateBy { it.displayKey }

        val disconnected = previous.filter { it.displayKey !in newByKey }
        val connected = newHandles.filter { it.displayKey !in previousByKey }

        if (disconnected.isEmpty() && connected.isEmpty() && previous == newHandles) return

        _devices.value = newHandles

        for (handle in disconnected) {
            controllers.remove(handle.displayKey)?.let { runCatching { it.close() } }
            _events.emit(DeviceEvent.Disconnected(handle))
        }
        for (handle in connected) {
            if (autoOpen) {
                try {
                    controllers[handle.displayKey] = doOpen(handle)
                } catch (t: Throwable) {
                    logger.warn("Failed to auto-open ${handle.displayKey}: ${t.message}", t)
                    continue
                }
            }
            _events.emit(DeviceEvent.Connected(handle))
        }
    }

    private suspend fun doOpen(handle: MidiDeviceHandle): MidiController = coroutineScope {
        // Open both directions in parallel — native backends can take tens of ms each on USB
        // enumeration, and we don't want to serialise that on every hot-plug event.
        val inputAsync = handle.inputPort?.let { async { access.openInput(it.id) } }
        val outputAsync = handle.outputPort?.let { async { access.openOutput(it.id) } }
        val parentScope = scope ?: GlobalScope
        KtMidiController(handle, outputAsync?.await(), inputAsync?.await(), parentScope = parentScope)
    }
}
