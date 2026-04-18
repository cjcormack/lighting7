package uk.me.cormack.lighting7.midi

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * Matches [MidiDeviceRegistry] events against the [ControlSurfaceRegistry] and emits
 * higher-level [SurfaceEvent]s.
 *
 * On [MidiDeviceRegistry.DeviceEvent.Connected]:
 *   - If the handle matches a registered `@ControlSurfaceType`, instantiate the profile
 *     and emit [SurfaceEvent.DeviceAttached] plus update [attached].
 *   - Otherwise emit [SurfaceEvent.UnmatchedDeviceConnected] so a MIDI-Learn-style
 *     fallback can be offered.
 *
 * On [MidiDeviceRegistry.DeviceEvent.Disconnected]:
 *   - If the handle was attached, drop it from [attached] and emit
 *     [SurfaceEvent.DeviceDetached]. Unmatched disconnects are silent.
 *
 * Purely a metadata / event layer — inbound routing and outbound feedback live elsewhere.
 */
class DeviceMatcher(
    private val registry: MidiDeviceRegistry,
    private val types: (MidiDeviceHandle) -> ControlSurfaceRegistry.DeviceTypeInfo? = ControlSurfaceRegistry::matchFor,
    private val instantiate: (String) -> ControlSurfaceDevice = ControlSurfaceRegistry::instantiate,
) {
    companion object {
        private val logger = LoggerFactory.getLogger(DeviceMatcher::class.java)
    }

    /** High-level event emitted by the matcher. */
    sealed class SurfaceEvent {
        abstract val handle: MidiDeviceHandle

        data class DeviceAttached(
            override val handle: MidiDeviceHandle,
            val typeKey: String,
            val instance: ControlSurfaceDevice,
        ) : SurfaceEvent()

        data class DeviceDetached(override val handle: MidiDeviceHandle) : SurfaceEvent()

        data class UnmatchedDeviceConnected(override val handle: MidiDeviceHandle) : SurfaceEvent()
    }

    /** A currently-attached profile instance plus its transport handle. */
    data class Attached(
        val handle: MidiDeviceHandle,
        val typeKey: String,
        val instance: ControlSurfaceDevice,
    )

    private val _events = MutableSharedFlow<SurfaceEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    val events: SharedFlow<SurfaceEvent> = _events.asSharedFlow()

    private val _attached = MutableStateFlow<Map<String, Attached>>(emptyMap())

    /** Keyed by `MidiDeviceHandle.displayKey`. */
    val attached: StateFlow<Map<String, Attached>> = _attached.asStateFlow()

    private var collectJob: Job? = null

    fun start(scope: CoroutineScope) {
        if (collectJob != null) return
        collectJob = scope.launch(CoroutineName("DeviceMatcher")) {
            registry.events.collect { handle(it) }
        }
    }

    fun stop() {
        collectJob?.cancel()
        collectJob = null
    }

    // Exposed for testing — drives the handler directly without a Flow subscription.
    internal suspend fun handle(event: MidiDeviceRegistry.DeviceEvent) {
        when (event) {
            is MidiDeviceRegistry.DeviceEvent.Connected -> handleConnected(event.handle)
            is MidiDeviceRegistry.DeviceEvent.Disconnected -> handleDisconnected(event.handle)
        }
    }

    private suspend fun handleConnected(handle: MidiDeviceHandle) {
        val typeInfo = types(handle)
        if (typeInfo == null) {
            logger.info("Unmatched MIDI device connected: ${handle.displayKey} (${handle.displayName})")
            _events.emit(SurfaceEvent.UnmatchedDeviceConnected(handle))
            return
        }
        val instance = try {
            instantiate(typeInfo.typeKey)
        } catch (e: Exception) {
            logger.warn(
                "Failed to instantiate control surface '${typeInfo.typeKey}' for ${handle.displayKey}: ${e.message}",
                e,
            )
            _events.emit(SurfaceEvent.UnmatchedDeviceConnected(handle))
            return
        }
        val attached = Attached(handle, typeInfo.typeKey, instance)
        _attached.update { it + (handle.displayKey to attached) }
        logger.info("Control surface attached: ${typeInfo.typeKey} → ${handle.displayKey}")
        _events.emit(SurfaceEvent.DeviceAttached(handle, typeInfo.typeKey, instance))
    }

    private suspend fun handleDisconnected(handle: MidiDeviceHandle) {
        val previous = _attached.value[handle.displayKey]
        if (previous != null) {
            _attached.update { it - handle.displayKey }
            logger.info("Control surface detached: ${previous.typeKey} ← ${handle.displayKey}")
            _events.emit(SurfaceEvent.DeviceDetached(handle))
        }
    }
}
