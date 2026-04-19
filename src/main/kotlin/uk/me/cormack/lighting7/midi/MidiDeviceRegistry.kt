package uk.me.cormack.lighting7.midi

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 0 device registry. Owns the [MidiAccessSource], polls for connected MIDI ports on a
 * fixed interval, pairs ports into [MidiDeviceHandle]s, and emits connect/disconnect events.
 * Polling diffs the enumerated port lists because libremidi does not expose native
 * device-added/removed events.
 */
class MidiDeviceRegistry(
    access: MidiAccessSource,
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    private val autoOpen: Boolean = true,
) {
    @Volatile
    private var access: MidiAccessSource = access

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

    /** Debug-only: suppresses unchanged enum-summary spam in the poll loop. */
    @Volatile
    private var lastEnumSummary: String? = null

    // Serialises rescan and tick — a rescan that swaps `access` mid-tick would invalidate
    // port IDs already captured for an in-flight doOpen, since each LibreMidiAccessSource
    // stamps its own instance-ID into them.
    private val accessMutex = Mutex()

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
     * Replace the underlying [MidiAccessSource] and run an immediate enumeration pass.
     * Called from the CoreMIDI4J notification listener on macOS hot-plug / unplug events.
     */
    suspend fun rescan(newAccess: MidiAccessSource) {
        accessMutex.withLock {
            val old = access
            access = newAccess
            runCatching { old.close() }
            tickLocked()
        }
    }

    /**
     * Open a handle explicitly. Safe to call when [autoOpen] is false; a no-op if a controller
     * is already open for this handle. Returns the (existing or new) controller.
     */
    suspend fun openController(handle: MidiDeviceHandle): MidiController {
        controllers[handle.displayKey]?.let { return it }
        return accessMutex.withLock {
            controllers[handle.displayKey]?.let { return@withLock it }
            doOpen(handle, access).also { controllers[handle.displayKey] = it }
        }
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
        accessMutex.withLock { tickLocked() }
    }

    private suspend fun tickLocked() {
        val snapshotAccess = access
        val ports = snapshotAccess.enumerateInputs() + snapshotAccess.enumerateOutputs()
        val newHandles = MidiDeviceHandle.pair(ports).sortedBy { it.displayKey }
        val previous = _devices.value
        if (logger.isDebugEnabled) {
            val jvmNames = runCatching {
                javax.sound.midi.MidiSystem.getMidiDeviceInfo().map { it.name }
            }.getOrDefault(emptyList())
            val coreMidi4j = runCatching {
                val loaded = uk.co.xfactorylibrarians.coremidi4j.CoreMidiDeviceProvider.isLibraryLoaded()
                val infos = uk.co.xfactorylibrarians.coremidi4j.CoreMidiDeviceProvider.getMidiDeviceInfo()
                "loaded=$loaded devices=${infos.joinToString { it.name }}"
            }.getOrElse { "error: ${it.message}" }
            val summary = "libremidi ports=${ports.size} handles=[${newHandles.joinToString { it.displayKey }}] | " +
                "javax.sound.midi=[${jvmNames.joinToString()}] | coremidi4j=$coreMidi4j"
            if (summary != lastEnumSummary) {
                lastEnumSummary = summary
                logger.debug("midi-enum: {}", summary)
            }
        }

        val previousByKey = previous.associateBy { it.displayKey }
        val newByKey = newHandles.associateBy { it.displayKey }

        val disconnected = previous.filter { it.displayKey !in newByKey }
        val connected = newHandles.filter { it.displayKey !in previousByKey }

        if (disconnected.isEmpty() && connected.isEmpty() && previous == newHandles) return

        _devices.value = newHandles

        for (handle in disconnected) removeController(handle)
        for (handle in connected) {
            if (autoOpen) {
                try {
                    controllers[handle.displayKey] = doOpen(handle, snapshotAccess)
                } catch (t: Throwable) {
                    logger.warn("Failed to auto-open ${handle.displayKey}: ${t.message}", t)
                    continue
                }
            }
            _events.emit(DeviceEvent.Connected(handle))
        }
    }

    private suspend fun doOpen(handle: MidiDeviceHandle, forAccess: MidiAccessSource): MidiController = coroutineScope {
        // Open both directions in parallel — native backends can take tens of ms each on USB
        // enumeration. [forAccess] pins to the access source that enumerated [handle], so a
        // concurrent rescan swapping `access` mid-open can't produce a port-ID mismatch.
        val inputAsync = handle.inputPort?.let { async { forAccess.openInput(it.id) } }
        val outputAsync = handle.outputPort?.let { async { forAccess.openOutput(it.id) } }
        val parentScope = scope ?: GlobalScope
        KtMidiController(
            handle = handle,
            sendTarget = outputAsync?.await(),
            inputSource = inputAsync?.await(),
            parentScope = parentScope,
            onTransmissionGaveUp = { markDisconnected(handle) },
        )
    }

    /**
     * Called from a controller's transmission loop when it gives up after repeated send
     * failures — the physical device has gone away but libremidi's enumeration still lists
     * the port. Surfaces a Disconnected event on the registry's scope.
     */
    private fun markDisconnected(handle: MidiDeviceHandle) {
        val s = scope ?: return
        s.launch {
            val current = _devices.value
            if (current.none { it.displayKey == handle.displayKey }) return@launch
            _devices.value = current.filterNot { it.displayKey == handle.displayKey }
            removeController(handle)
        }
    }

    private suspend fun removeController(handle: MidiDeviceHandle) {
        controllers.remove(handle.displayKey)?.let { runCatching { it.close() } }
        _events.emit(DeviceEvent.Disconnected(handle))
    }
}
