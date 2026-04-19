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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Clock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Short-lived MIDI Learn sessions. A client opens one via [begin], any subsequent inbound
 * event from a device whose typeKey matches [LearnFilter.deviceTypeKey] (or from *any*
 * device if no filter is set) that passes the "captureable" predicate captures the control
 * and transitions the session to `captured`. The client then [commit]s or [cancel]s.
 *
 * Wiring: subscribes to [DeviceMatcher.events] to attach / detach per-controller input
 * collectors. The controller stream is resolved via `MidiDeviceRegistry.controllerFor(...)`
 * from the lambda passed into the constructor. When a device detaches the collector is
 * cancelled; new devices get new collectors.
 *
 * Session timeout: 30 s by default; sessions still pending after the deadline transition
 * to [SessionState.TimedOut] and are dropped from the registry.
 *
 * This class does not touch the binding store. The server's WebSocket handler, on
 * [SessionEvent.Captured], will call [ControlSurfaceBindingService.create] if the client
 * commits.
 */
class MidiLearnSessionManager(
    private val deviceMatcher: DeviceMatcher,
    private val controllerLookup: (String) -> MidiController?,
    private val clock: Clock = Clock.systemUTC(),
    private val defaultTimeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    companion object {
        const val DEFAULT_TIMEOUT_MS: Long = 30_000L
        private val logger = LoggerFactory.getLogger(MidiLearnSessionManager::class.java)
    }

    /**
     * Optional filters applied to inbound events. If [deviceTypeKey] is non-null, only events
     * from devices with that matched typeKey are considered. Passing a null [deviceTypeKey]
     * allows capture from *any* attached surface.
     */
    data class LearnFilter(
        val deviceTypeKey: String? = null,
    )

    enum class SessionState { Pending, Captured, Committed, Cancelled, TimedOut }

    data class Session(
        val sessionId: String,
        val projectId: Int,
        val filter: LearnFilter,
        val state: SessionState,
        /** Captured addressing once [state] == [SessionState.Captured]. */
        val captured: Capture? = null,
        /** Epoch-millis deadline after which a Pending session transitions to TimedOut. */
        val deadlineMs: Long,
    )

    /** Resolved address of a captured control. */
    data class Capture(
        val deviceTypeKey: String,
        val controlId: String,
    )

    sealed class SessionEvent {
        abstract val sessionId: String
        data class Started(override val sessionId: String, val session: Session) : SessionEvent()
        data class Captured(override val sessionId: String, val session: Session) : SessionEvent()
        data class Committed(override val sessionId: String, val session: Session) : SessionEvent()
        data class Cancelled(override val sessionId: String, val session: Session) : SessionEvent()
        data class TimedOut(override val sessionId: String, val session: Session) : SessionEvent()
    }

    private val _sessions = MutableStateFlow<Map<String, Session>>(emptyMap())
    val sessions: StateFlow<Map<String, Session>> = _sessions.asStateFlow()

    private val _events = MutableSharedFlow<SessionEvent>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    val events: SharedFlow<SessionEvent> = _events.asSharedFlow()

    private val idCounter = AtomicLong(System.currentTimeMillis())
    private val inputJobs = ConcurrentHashMap<String /* displayKey */, Job>()
    private val deviceKeyByDisplay = ConcurrentHashMap<String, String>()
    private var matcherJob: Job? = null
    private var scope: CoroutineScope? = null

    fun start(parentScope: CoroutineScope) {
        if (matcherJob != null) return
        scope = parentScope
        matcherJob = parentScope.launch(CoroutineName("MidiLearnSessionManager-matcher")) {
            deviceMatcher.events.collect { event -> handleMatcherEvent(event) }
        }
        // Attach to any devices already present when we start up.
        for ((displayKey, attached) in deviceMatcher.attached.value) {
            attachCollector(displayKey, attached.typeKey)
        }
    }

    fun stop() {
        matcherJob?.cancel()
        matcherJob = null
        inputJobs.values.forEach { it.cancel() }
        inputJobs.clear()
        deviceKeyByDisplay.clear()
    }

    /**
     * Open a new learn session. Returns the session record; emits
     * [SessionEvent.Started] after insertion. Sessions are keyed by a freshly-minted UUID.
     */
    fun begin(projectId: Int, filter: LearnFilter = LearnFilter()): Session {
        val sessionId = UUID.randomUUID().toString()
        val deadline = clock.millis() + defaultTimeoutMs
        val session = Session(
            sessionId = sessionId,
            projectId = projectId,
            filter = filter,
            state = SessionState.Pending,
            deadlineMs = deadline,
        )
        _sessions.update { it + (sessionId to session) }
        _events.tryEmit(SessionEvent.Started(sessionId, session))
        scheduleTimeout(sessionId, deadline)
        logger.debug("Learn session {} started for project {} (filter={})", sessionId, projectId, filter)
        return session
    }

    /** Mark a session as cancelled. No-op if unknown or already terminal. */
    fun cancel(sessionId: String): Session? {
        val transitioned = transition(sessionId) { current ->
            if (current.state != SessionState.Pending && current.state != SessionState.Captured) return@transition null
            current.copy(state = SessionState.Cancelled)
        } ?: return null
        _events.tryEmit(SessionEvent.Cancelled(sessionId, transitioned))
        return transitioned
    }

    /**
     * Transition a captured session to [SessionState.Committed]. The caller is responsible
     * for persisting the binding beforehand. Returns the committed session, or null if the
     * session is unknown / not in Captured state.
     */
    fun commit(sessionId: String): Session? {
        val transitioned = transition(sessionId) { current ->
            if (current.state != SessionState.Captured) return@transition null
            current.copy(state = SessionState.Committed)
        } ?: return null
        _events.tryEmit(SessionEvent.Committed(sessionId, transitioned))
        return transitioned
    }

    /** Current snapshot of a session. */
    fun get(sessionId: String): Session? = _sessions.value[sessionId]

    /** Synchronous test hook — drives a single synthetic event into the matcher pipeline. */
    internal fun offerInput(deviceTypeKey: String, event: MidiInputEvent) {
        captureIfPending(deviceTypeKey, event)
    }

    // Exposed for test-only timeout simulation.
    internal fun expireDueSessions(now: Long = clock.millis()) {
        val due = _sessions.value.values.filter {
            it.state == SessionState.Pending && it.deadlineMs <= now
        }
        for (session in due) {
            val transitioned = transition(session.sessionId) { current ->
                if (current.state != SessionState.Pending) return@transition null
                current.copy(state = SessionState.TimedOut)
            } ?: continue
            _events.tryEmit(SessionEvent.TimedOut(transitioned.sessionId, transitioned))
        }
    }

    private fun scheduleTimeout(sessionId: String, deadlineMs: Long) {
        val s = scope ?: return
        s.launch(CoroutineName("MidiLearnTimeout-$sessionId")) {
            val delayMs = (deadlineMs - clock.millis()).coerceAtLeast(0L)
            kotlinx.coroutines.delay(delayMs)
            expireDueSessions(clock.millis())
        }
    }

    private suspend fun handleMatcherEvent(event: DeviceMatcher.SurfaceEvent) {
        when (event) {
            is DeviceMatcher.SurfaceEvent.DeviceAttached -> {
                attachCollector(event.handle.displayKey, event.typeKey)
            }
            is DeviceMatcher.SurfaceEvent.DeviceDetached -> {
                inputJobs.remove(event.handle.displayKey)?.cancel()
                deviceKeyByDisplay.remove(event.handle.displayKey)
            }
            is DeviceMatcher.SurfaceEvent.UnmatchedDeviceConnected -> {
                // No typeKey to route through; unknown-device Learn is handled via an explicit-typeKey flow.
            }
        }
    }

    private fun attachCollector(displayKey: String, deviceTypeKey: String) {
        inputJobs.remove(displayKey)?.cancel()
        deviceKeyByDisplay[displayKey] = deviceTypeKey
        val controller = controllerLookup(displayKey) ?: return
        val s = scope ?: return
        val job = s.launch(CoroutineName("MidiLearn-$displayKey")) {
            controller.input.collect { event -> captureIfPending(deviceTypeKey, event) }
        }
        inputJobs[displayKey] = job
    }

    private fun captureIfPending(deviceTypeKey: String, event: MidiInputEvent) {
        val candidateControlId = extractCapturableControlId(deviceTypeKey, event) ?: return

        // Find the first pending session whose filter matches.
        val session = _sessions.value.values.firstOrNull {
            it.state == SessionState.Pending &&
                (it.filter.deviceTypeKey == null || it.filter.deviceTypeKey == deviceTypeKey)
        } ?: return

        val transitioned = transition(session.sessionId) { current ->
            if (current.state != SessionState.Pending) return@transition null
            current.copy(
                state = SessionState.Captured,
                captured = Capture(deviceTypeKey = deviceTypeKey, controlId = candidateControlId),
            )
        } ?: return
        _events.tryEmit(SessionEvent.Captured(transitioned.sessionId, transitioned))
    }

    /**
     * Extract a stable `controlId` from an inbound [MidiInputEvent] by consulting the device
     * profile registered under [deviceTypeKey]. Returns null if the event is not "captureable"
     * (e.g. a CC with value 0 on a fader that still sits at rest, an unknown CC / note,
     * a Program Change — no descriptor type captures those, since bank buttons are
     * deliberately not user-bindable).
     */
    private fun extractCapturableControlId(deviceTypeKey: String, event: MidiInputEvent): String? {
        val profile = try {
            ControlSurfaceRegistry.allTypes.firstOrNull { it.typeKey == deviceTypeKey }
        } catch (e: Exception) {
            logger.warn("Failed to resolve profile $deviceTypeKey: ${e.message}")
            null
        } ?: return null

        for (control in profile.controls) {
            val match = when (control) {
                is FaderDescriptor -> {
                    if (event is MidiInputEvent.ControlChange &&
                        event.cc == control.cc &&
                        event.channel == control.channel &&
                        event.value.toInt() > 0
                    ) control.controlId else null
                }
                is EncoderDescriptor -> when {
                    event is MidiInputEvent.ControlChange &&
                        event.cc == control.cc &&
                        event.channel == control.channel -> control.controlId
                    event is MidiInputEvent.NoteOn &&
                        control.pushNote != null &&
                        event.note == control.pushNote &&
                        event.channel == control.channel &&
                        event.velocity.toInt() > 0 -> control.controlId
                    else -> null
                }
                is ButtonDescriptor -> {
                    if (event is MidiInputEvent.NoteOn &&
                        event.note == control.note &&
                        event.channel == control.channel &&
                        event.velocity.toInt() > 0
                    ) control.controlId else null
                }
                is BankButtonDescriptor -> {
                    // Bank buttons are not user-bindable; never capture them during Learn.
                    null
                }
            }
            if (match != null) return match
        }
        return null
    }

    private fun transition(sessionId: String, mutate: (Session) -> Session?): Session? {
        var result: Session? = null
        _sessions.update { current ->
            val existing = current[sessionId] ?: return@update current
            val next = mutate(existing) ?: return@update current
            result = next
            current + (sessionId to next)
        }
        return result
    }
}
