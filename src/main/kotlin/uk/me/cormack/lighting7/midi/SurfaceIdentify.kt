package uk.me.cormack.lighting7.midi

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.time.Clock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Identify mode (surface-setup plan D14): while a window waits for a touch, the next press, fader
 * move or encoder turn on any attached device is **captured and not dispatched**. One touch per
 * session; a session not answered in [timeoutMs] ends.
 *
 * The input router asks [offer] before it dispatches, so capture and dispatch are one decision on
 * one thread — a capture beside dispatch (what Learn did) fired the control it named. After a
 * capture, the rest of that gesture on the same control — the release, the fader still moving — is
 * swallowed too, until the control has been still for [quietMs]. A fader's touch is never captured
 * or swallowed, so its touch-up still reaches the feedback; nor is a fader at 0, which is at rest.
 */
class SurfaceIdentify(
    private val clock: Clock = Clock.systemUTC(),
    private val timeoutMs: Long = TIMEOUT_MS,
    private val quietMs: Long = QUIET_MS,
) {
    data class Session(
        val sessionId: String,
        /** Only a device of this type is captured; null for any. */
        val deviceTypeKey: String?,
        val deadlineMs: Long,
        val startedMs: Long,
    )

    /** The control part a touch named. */
    data class Capture(
        val deviceTypeKey: String,
        val displayKey: String,
        val controlId: String,
        val part: ControlPart,
    )

    sealed interface Event {
        val sessionId: String

        data class Captured(override val sessionId: String, val capture: Capture) : Event

        /** Ended without a capture: [reason] is `timeout`. A cancel answers its caller and emits nothing. */
        data class Ended(override val sessionId: String, val reason: String) : Event
    }

    private val sessions = ConcurrentHashMap<String, Session>()

    /** `displayKey|controlId` → when the swallowed gesture last moved. */
    private val swallowing = ConcurrentHashMap<String, Long>()

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    private var scope: CoroutineScope? = null

    fun start(scope: CoroutineScope) {
        this.scope = scope
    }

    fun begin(deviceTypeKey: String? = null): Session {
        val now = clock.millis()
        val session = Session(UUID.randomUUID().toString(), deviceTypeKey, now + timeoutMs, now)
        sessions[session.sessionId] = session
        scope?.launch(CoroutineName("SurfaceIdentify-timeout")) {
            delay(timeoutMs)
            expire(clock.millis())
        }
        return session
    }

    /** End a waiting session. False when it had already ended. */
    fun cancel(sessionId: String): Boolean = sessions.remove(sessionId) != null

    fun isWaiting(sessionId: String): Boolean = sessions.containsKey(sessionId)

    fun remainingMs(session: Session): Long = (session.deadlineMs - clock.millis()).coerceAtLeast(0)

    /**
     * Whether [input] on [displayKey] is identify mode's rather than dispatch's: the touch a waiting
     * session captures, or the rest of a captured gesture. True means the router drops it.
     */
    fun offer(displayKey: String, deviceTypeKey: String, input: ControlInputIndex.Input): Boolean {
        val now = clock.millis()
        val controlId = when (input) {
            is ControlInputIndex.Input.Move -> input.control.controlId
            is ControlInputIndex.Input.Press -> input.control.controlId
            is ControlInputIndex.Input.Release -> input.control.controlId
            is ControlInputIndex.Input.Touch, is ControlInputIndex.Input.Layer -> return false
        }
        if (input is ControlInputIndex.Input.Move && input.control is FaderDescriptor && input.value7Bit.toInt() == 0) return false
        val swallowKey = "$displayKey|$controlId"
        swallowing[swallowKey]?.let { last ->
            if (now - last <= quietMs) {
                swallowing[swallowKey] = now
                return true
            }
            swallowing.remove(swallowKey)
        }
        val part = when (input) {
            is ControlInputIndex.Input.Move -> ControlPart.MAIN
            is ControlInputIndex.Input.Press -> input.part
            else -> return false
        }
        if (sessions.isEmpty()) return false
        val session = sessions.values
            .filter { it.deadlineMs > now && (it.deviceTypeKey == null || it.deviceTypeKey == deviceTypeKey) }
            .maxByOrNull { it.startedMs }
            ?: return false
        if (sessions.remove(session.sessionId) == null) return false
        swallowing[swallowKey] = now
        _events.tryEmit(Event.Captured(session.sessionId, Capture(deviceTypeKey, displayKey, controlId, part)))
        return true
    }

    internal fun expire(now: Long) {
        for (session in sessions.values.toList()) {
            if (session.deadlineMs <= now && sessions.remove(session.sessionId) != null) {
                _events.tryEmit(Event.Ended(session.sessionId, "timeout"))
            }
        }
    }

    companion object {
        const val TIMEOUT_MS: Long = 30_000
        const val QUIET_MS: Long = 750
    }
}
