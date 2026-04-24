package uk.me.cormack.lighting7.plugins

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import uk.me.cormack.lighting7.models.CuePropertyAssignmentDto
import java.util.concurrent.ConcurrentHashMap

/**
 * Project-scoped view over per-connection [CueEditSessionState]s. A single client's WS
 * connection owns the authoritative session lifecycle (see [CueEditSessionHandler]); this
 * registry lets server-side consumers — control surfaces in particular — observe whether
 * *any* client currently has a cue open for edit in a given project, and react accordingly.
 *
 * Phase 6 of `docs/plans/completed/control-surface-plan.md`: surface fader writes route through
 * `cueEdit.setProperty` when [activeSession] returns non-null for the project, and the
 * [SurfaceFeedbackPublisher] drives motors from the cue's Layer 3 value instead of the
 * composed live value.
 *
 * Conflict resolution for multiple sessions is out of scope here — we just report "some
 * session is active" and let the cue-authoring layer reject conflicting [beginEdit] calls.
 */
class CueEditSessionRegistry {

    /** A handle (identity-equal) paired with its session entry. */
    data class Entry(val projectId: Int, val session: CueEditSessionState)

    sealed class Event {
        /** Registered a brand-new session for [projectId]. */
        data class Started(val projectId: Int, val session: CueEditSessionState) : Event()

        /** Same handle, new mode / snapshot / cueStackId. */
        data class ModeChanged(val projectId: Int, val session: CueEditSessionState) : Event()

        /** Session closed — either via `endEdit` or connection disconnect. */
        data class Ended(val projectId: Int, val cueId: Int) : Event()

        /**
         * A single property assignment was upserted. [value] is the canonical string form
         * that round-trips through [uk.me.cormack.lighting7.fx.Layer3Resolver.parseAssignmentValue].
         */
        data class AssignmentChanged(
            val projectId: Int,
            val cueId: Int,
            val targetType: String,
            val targetKey: String,
            val propertyName: String,
            val value: String,
        ) : Event()

        /** A property assignment was removed. */
        data class AssignmentCleared(
            val projectId: Int,
            val cueId: Int,
            val targetType: String,
            val targetKey: String,
            val propertyName: String,
        ) : Event()

        /**
         * The full assignment list for [cueId] was replaced wholesale. Consumers should drop
         * any cached per-assignment state and refetch. Emitted on `discardChanges` and on
         * operations that rebuild assignments in one go (preset application add).
         */
        data class AssignmentsReloaded(
            val projectId: Int,
            val cueId: Int,
            val assignments: List<CuePropertyAssignmentDto>,
        ) : Event()
    }

    private val sessions = ConcurrentHashMap<Any, Entry>()

    /**
     * Secondary index mirroring [sessions] keyed by projectId so [activeSession] is O(1).
     * Kept in lockstep with [sessions] under [mutationLock] — both maps are updated in a
     * single critical section on register / unregister. The cue-authoring layer rejects
     * overlapping `beginEdit` calls, so N ≤ 1 per project; if two handles do race and land
     * on the same projectId, this index tracks the most-recent registration (the older one
     * still lives in [sessions] but won't be surfaced by [activeSession], matching the
     * prior behaviour's "ConcurrentHashMap iteration order" non-guarantee).
     */
    private val sessionsByProject = ConcurrentHashMap<Int, Entry>()

    private val mutationLock = Any()

    private val _events = MutableSharedFlow<Event>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<Event> = _events.asSharedFlow()

    /**
     * Install the session associated with [handle] (typically the connection's
     * `AtomicReference<CueEditSessionState?>`). Emits [Event.Started] on first registration
     * for the handle, or [Event.ModeChanged] when any tracked field has changed.
     */
    fun register(handle: Any, projectId: Int, session: CueEditSessionState) {
        val entry = Entry(projectId, session)
        val event: Event = synchronized(mutationLock) {
            val previous = sessions[handle]
            if (previous != null &&
                previous.projectId == projectId &&
                previous.session.mode == session.mode &&
                previous.session.cueId == session.cueId &&
                previous.session.cueStackId == session.cueStackId &&
                previous.session.snapshot === session.snapshot
            ) {
                return  // same session, no event, no map churn
            }
            sessions[handle] = entry
            if (previous != null && previous.projectId != projectId) {
                // This handle's project changed — drop the stale secondary-index entry only if
                // we're still the tracked one (another handle may have since overwritten it).
                sessionsByProject.remove(previous.projectId, previous)
            }
            sessionsByProject[projectId] = entry
            if (previous == null) Event.Started(projectId, session)
            else Event.ModeChanged(projectId, session)
        }
        _events.tryEmit(event)
    }

    /** Remove the session for [handle]. Emits [Event.Ended] if an entry was present. */
    fun unregister(handle: Any): Entry? {
        val removed = synchronized(mutationLock) {
            val r = sessions.remove(handle) ?: return null
            // Only drop the secondary-index entry if it still points at this handle's session;
            // a concurrent register for the same project may have already overwritten it.
            sessionsByProject.remove(r.projectId, r)
            r
        }
        _events.tryEmit(Event.Ended(removed.projectId, removed.session.cueId))
        return removed
    }

    /**
     * Any currently-active session matching [projectId]. O(1) lookup via [sessionsByProject];
     * the cue-authoring layer rejects overlapping `beginEdit` calls, so in practice there is
     * at most one session per project at any moment.
     */
    fun activeSession(projectId: Int): Entry? = sessionsByProject[projectId]

    fun notifyAssignmentChanged(
        projectId: Int,
        cueId: Int,
        targetType: String,
        targetKey: String,
        propertyName: String,
        value: String,
    ) {
        _events.tryEmit(Event.AssignmentChanged(projectId, cueId, targetType, targetKey, propertyName, value))
    }

    fun notifyAssignmentCleared(
        projectId: Int,
        cueId: Int,
        targetType: String,
        targetKey: String,
        propertyName: String,
    ) {
        _events.tryEmit(Event.AssignmentCleared(projectId, cueId, targetType, targetKey, propertyName))
    }

    fun notifyAssignmentsReloaded(
        projectId: Int,
        cueId: Int,
        assignments: List<CuePropertyAssignmentDto>,
    ) {
        _events.tryEmit(Event.AssignmentsReloaded(projectId, cueId, assignments))
    }
}
