package uk.me.cormack.lighting7.routes

import io.ktor.http.*
import io.ktor.resources.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.resources.*
import io.ktor.server.resources.post
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.fx.ProgrammerOwner
import uk.me.cormack.lighting7.fx.FxEngine
import uk.me.cormack.lighting7.fx.ProgrammerWriter
import uk.me.cormack.lighting7.fx.LocateValueResolver
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.show.LocateManager
import uk.me.cormack.lighting7.state.State

/**
 * REST API for the Locate toggle: point a fixture (or every member of a group) straight
 * ahead and force an open white beam so the physical unit can be identified in the rig.
 *
 * Locate-on asserts the values [LocateValueResolver] computes as sticky programmer
 * entries, batched into a single publish. The programmer sits above effects and cues, so
 * "locate wins" non-destructively: every effect covering the written properties is
 * suppressed for as long as the locate holds — including effects reaching the fixture
 * through *other* groups — and resumes when it releases. Locate-off clears the entries and
 * the properties cascade back to whatever cue/baseline sits underneath. State is tracked
 * in [LocateManager] — in-memory, like the programmer entries themselves.
 */
internal fun Route.routeApiRestLocate(state: State) {
    route("/locate") {
        // Currently-located targets
        get<LocateStateResource> {
            val targets = state.show.locateManager.activeTargets.value
                .map { LocateTargetDto(it.discriminator, it.key) }
            call.respond(LocateStateResponse(targets))
        }

        // Toggle locate for one fixture or group
        post<LocateToggleResource> {
            val request = call.receive<ToggleLocateRequest>()
            val target = TargetRef.ofOrNull(request.type, request.key)
                ?: return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("Unknown target type '${request.type}'"),
                )

            // Resolve existence up-front so an unknown key is a 404, not a half-applied toggle.
            try {
                when (target) {
                    is TargetRef.Fixture -> state.show.fixtures.untypedGroupableFixture(target.key)
                    is TargetRef.Group -> state.show.fixtures.untypedGroup(target.key)
                }
            } catch (e: IllegalStateException) {
                return@post call.respond(
                    HttpStatusCode.NotFound,
                    ErrorResponse(e.message ?: "Target not found"),
                )
            }

            var parkMasked = false
            val outcome = state.show.locateManager.toggle(
                target,
                assert = { t ->
                    val result = applyLocate(state, t)
                    if (t == target) {
                        parkMasked = result.parkMasked
                    }
                    if (result.stale) null else result.writes
                },
                clear = { writes -> clearLocateWrites(state, writes) },
            )
            // Stale-record backstop: a locate whose fixture was rekeyed mid-toggle skips its
            // per-channel clear in [clearLocateWrites], stranding LOCATE entries that would
            // later resurface as ghost values. Once nothing is located at all, no LOCATE
            // entry is legitimate, so sweep the owner. (Single-operator toggles are serial;
            // a concurrent toggle-on racing this sweep would merely need re-toggling.)
            if (!outcome.active && state.show.locateManager.activeTargets.value.isEmpty()) {
                state.show.programmerStore.clearOwner(ProgrammerOwner.LOCATE)
            }
            call.respond(
                ToggleLocateResponse(outcome.active, outcome.writeCount, parkMasked)
            )
        }
    }
}

private data class LocateApplyResult(
    val writes: List<LocateManager.LocateWrite>,
    /** The target no longer resolves — [LocateManager] should drop its bookkeeping entry. */
    val stale: Boolean = false,
    /** Every property locate would have written is parked, so the toggle did nothing. */
    val parkMasked: Boolean = false,
)

/**
 * Resolve and assert the locate values for [target] as one batched programmer publish,
 * returning the bookkeeping records for [LocateManager]. Groups resolve per member —
 * members can be heterogeneous fixture types (different open/white levels) or elements,
 * so a single group-level property write would be wrong.
 *
 * Never throws ([LocateManager.toggle] holds its lock across this): a target that goes
 * stale mid-flight reports itself stale (and asserts nothing), and a failed publish still
 * returns the bookkeeping rows for whatever may have landed.
 *
 * Assignments the publish would skip are dropped before anything else happens —
 * [ProgrammerWriter.publishability] answers with the publish's own guards, so this filter
 * can't disagree with what the write then does. Two reasons it says no: the property has no
 * DMX-backed channels, or park masks every channel it has. Either way the write would
 * achieve nothing, and recording it would strand a bookkeeping row (and, since programmer
 * entries suppress effects, needlessly freeze the effects covering it). A target whose every
 * property is unpublishable therefore resolves to zero writes and reports itself inactive,
 * leaving the rig exactly as it was; [LocateApplyResult.parkMasked] distinguishes
 * "everything is parked" from "nothing to write" for the response.
 */
private fun applyLocate(state: State, target: TargetRef): LocateApplyResult {
    val engine = state.show.fxEngine
    val fixtures = try {
        when (target) {
            is TargetRef.Fixture -> listOf(state.show.fixtures.untypedGroupableFixture(target.key))
            is TargetRef.Group -> state.show.fixtures.untypedGroup(target.key).fixtures
        }
    } catch (_: IllegalStateException) {
        return LocateApplyResult(emptyList(), stale = true)
    }

    val (assignments, unpublishable) = fixtures
        .flatMap { LocateValueResolver.resolve(it) }
        .partition {
            engine.programmer.publishability(it.target, it.propertyName) ==
                ProgrammerWriter.Publishability.PUBLISHABLE
        }
    if (assignments.isEmpty()) {
        val parkMasked = unpublishable.any {
            engine.programmer.publishability(it.target, it.propertyName) ==
                ProgrammerWriter.Publishability.PARK_MASKED
        }
        return LocateApplyResult(emptyList(), parkMasked = parkMasked)
    }

    // "Locate wins" is now non-destructive: the programmer sits above effects, so the
    // entries written below suppress every effect covering them for as long as the locate
    // holds — and releasing the locate lets the effects resume. Nothing is removed.
    // A group locate fans out to members, but the gesture was group-scoped — carry the hint so
    // a later Record can collapse the entries back to a group row instead of emitting one row
    // per member.
    val sourceGroup = (target as? TargetRef.Group)?.key

    val writes = try {
        engine.programmer.writeProperties(
            ProgrammerOwner.LOCATE,
            assignments.map {
                ProgrammerWriter.PropertyWrite(it.target, it.propertyName, it.value, sourceGroup)
            },
            // Momentary owner: don't absorb the sideband — release must reveal what was
            // under it (an unpark hand-down, a raw channel write).
            absorbSideband = false,
        ).zip(assignments) { resolved, assignment ->
            if (resolved.isEmpty()) null
            else LocateManager.LocateWrite(assignment.target.targetKey, assignment.propertyName)
        }.filterNotNull()
    } catch (_: Exception) {
        // The store puts may have landed before the publish failed — record every assignment
        // so toggle-off can still release them (releasing a never-written property is a no-op).
        assignments.map { LocateManager.LocateWrite(it.target.targetKey, it.propertyName) }
    }
    return LocateApplyResult(writes.distinct())
}

/**
 * Release the recorded programmer assertions in one batched publish. Only the [ProgrammerOwner.LOCATE]
 * entries are cleared — a busked level or preset write on the same channels becomes visible
 * again instead of being wiped. Never throws; stale records (rekeyed fixture, rebuilt group)
 * are silently skipped, matching the preset toggle's release semantics.
 */
private fun clearLocateWrites(state: State, writes: List<LocateManager.LocateWrite>) {
    val clears = writes.mapNotNull { write ->
        val fixture = try {
            state.show.fixtures.untypedGroupableFixture(write.targetKey)
        } catch (_: IllegalStateException) {
            return@mapNotNull null
        }
        fixture to write.propertyName
    }
    if (clears.isEmpty()) return
    try {
        state.show.fxEngine.programmer.clearProperties(ProgrammerOwner.LOCATE, clears)
    } catch (_: Exception) {
        // Bookkeeping is already updated; a throw here would strand the manager mid-toggle.
        // Unpublished channels cascade on the next effect tick or cue-layer republish.
    }
}

@Resource("/")
private class LocateStateResource

@Resource("/toggle")
private class LocateToggleResource

@Serializable
internal data class LocateTargetDto(val type: String, val key: String)

@Serializable
internal data class LocateStateResponse(val targets: List<LocateTargetDto>)

@Serializable
internal data class ToggleLocateRequest(val type: String, val key: String)

@Serializable
internal data class ToggleLocateResponse(
    val active: Boolean,
    val writeCount: Int,
    /**
     * True when the toggle came back inactive *because* park masks every property locate
     * would have written. Without it `active = false, writeCount = 0` is indistinguishable
     * from "this target has no DMX-backed properties", and an operator staring at a fixture
     * that refuses to light has no way to tell that it is merely parked.
     */
    val parkMasked: Boolean = false,
)
