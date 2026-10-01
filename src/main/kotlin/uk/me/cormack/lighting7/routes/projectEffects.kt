package uk.me.cormack.lighting7.routes

import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.request.receive
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.me.cormack.lighting7.mcp.requireEffectsAccess
import uk.me.cormack.lighting7.models.CueEventDto
import uk.me.cormack.lighting7.models.CueType
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoFixturePatch
import uk.me.cormack.lighting7.models.cueEventPatchesOf
import uk.me.cormack.lighting7.models.cueEventsOf
import uk.me.cormack.lighting7.models.parseCueEventList
import uk.me.cormack.lighting7.models.replaceCueEvents
import uk.me.cormack.lighting7.models.toDto
import uk.me.cormack.lighting7.models.toIsoUtc
import uk.me.cormack.lighting7.plugins.toMessage
import uk.me.cormack.lighting7.state.EffectsService
import uk.me.cormack.lighting7.state.State

/**
 * One-shot effects over REST (stage-view plan session 9, §3.3, P2):
 *
 * - `POST /projects/{id}/effects/arm` `{on, seconds?}` — arm the desk (for [seconds], default 60) or
 *   disarm it; answers the arm as `effects.armed` carries it.
 * - `POST /projects/{id}/patches/{pid}/fire` `{trigger, rehearse?}` — fire one tube now. 409
 *   `TRIGGER_NOT_ARMED` unarmed, 409 `TRIGGER_SPENT` on a spent tube (nothing is sent), 400
 *   `TRIGGER_UNKNOWN` for a trigger the fixture does not have. `rehearse` — or a blind programmer —
 *   rehearses: announced, drawn, never sent, no arm needed.
 * - `POST /projects/{id}/patches/{pid}/reload` `{trigger?}` — mark one tube (or every tube) loaded.
 * - `PUT /projects/{id}/cues/{cid}/events` `{events: [{patchId, trigger, offsetMs}]}` — a cue's whole
 *   event list, every problem at once.
 *
 * Arm, fire and reload are **the current project's** (its cannons are the ones patched) and, on the
 * public listener, refused unless an admin has allowed them ([requireEffectsAccess]) — both roles
 * may use them on the desk's own listener. They are REST rather than WS commands so the socket gains
 * no operation (P2). The event list is stored data, ungated by the current project like scenery.
 */
internal fun Route.routeApiRestProjectEffects(state: State) {
    post<EffectsArmResource> { resource ->
        call.requireEffectsAccess(state)
        withCurrentProject(state, resource.parent.projectId, "Only the current project's cannons can be armed") { _ ->
            val request = call.receive<ArmRequest>()
            val armed = if (request.on) state.effectsService.arm(request.seconds) else state.effectsService.disarm("disarmed")
            call.respond(armed.toDto())
        }
    }

    post<PatchFireResource> { resource ->
        call.requireEffectsAccess(state)
        withCurrentProject(state, resource.parent.parent.projectId, "Only the current project's cannons can be fired") { project ->
            val request = call.receive<FireRequest>()
            val key = patchKeyOf(state, project.id.value, resource.parent.patchId)
                ?: return@withCurrentProject call.respond(HttpStatusCode.NotFound, ErrorResponse("Patch not found"))
            when (val outcome = state.effectsService.fire(key, request.trigger, EffectsService.Source.PANEL, rehearse = request.rehearse)) {
                is EffectsService.FireOutcome.Done -> call.respond(FireResponse(outcome.fired.toMessage().let {
                    FiredDto(it.fixture, it.fixtureName, it.trigger, it.label, it.at, it.rehearsed)
                }))
                EffectsService.FireOutcome.Unarmed -> call.respond(
                    HttpStatusCode.Conflict,
                    ErrorResponse("The desk is not armed: arm it first, or rehearse the fire", "TRIGGER_NOT_ARMED"),
                )
                is EffectsService.FireOutcome.Spent -> call.respond(
                    HttpStatusCode.Conflict,
                    ErrorResponse("That tube is spent (fired ${outcome.spentAt.toIsoUtc()}): nothing was sent — reload it first", "TRIGGER_SPENT"),
                )
                is EffectsService.FireOutcome.Unknown -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(outcome.message, "TRIGGER_UNKNOWN"))
            }
        }
    }

    post<PatchReloadResource> { resource ->
        call.requireEffectsAccess(state)
        withCurrentProject(state, resource.parent.parent.projectId, "Only the current project's cannons can be reloaded") { project ->
            val request = call.receive<ReloadRequest>()
            val key = patchKeyOf(state, project.id.value, resource.parent.patchId)
                ?: return@withCurrentProject call.respond(HttpStatusCode.NotFound, ErrorResponse("Patch not found"))
            call.respond(state.effectsService.reload(key, request.trigger).toDto())
        }
    }

    put<CueEventsResource> { resource ->
        withProject(state, resource.parent.projectId) { project ->
            val body = call.receive<JsonObject>()
            val items = (body["events"]?.takeIf { it !is JsonNull } as? JsonArray)
                ?: return@withProject call.respond(HttpStatusCode.BadRequest, ErrorResponse("events is required: an array of {patchId, trigger, offsetMs}"))
            val outcome: Pair<List<CueEventDto>?, Pair<HttpStatusCode, String>?> = transaction(state.database) {
                val cue = DaoCue.findById(resource.cueId)?.takeIf { it.project.id == project.id }
                    ?: return@transaction null to (HttpStatusCode.NotFound to "Cue not found")
                if (cue.cueType == CueType.MARKER.name) {
                    return@transaction null to (HttpStatusCode.BadRequest to "a MARKER is never gone to, so it fires nothing")
                }
                val problems = mutableListOf<String>()
                val writes = parseCueEventList(items, cueEventPatchesOf(project), "events", problems)
                if (problems.isNotEmpty()) return@transaction null to (HttpStatusCode.BadRequest to problems.joinToString("; "))
                replaceCueEvents(cue, writes)
                cueEventsOf(cue.id).map { it.toDto() } to null
            }
            val (events, error) = outcome
            if (events == null) {
                val (status, message) = error!!
                call.respond(status, ErrorResponse(message))
                return@withProject
            }
            state.show.fixtures.cueListChanged()
            call.respond(events)
        }
    }
}

private fun patchKeyOf(state: State, projectId: Int, patchId: Int): String? = transaction(state.database) {
    DaoFixturePatch.findById(patchId)?.takeIf { it.project.id.value == projectId }?.key
}

@Resource("/{projectId}/effects")
internal data class ProjectEffectsResource(val projectId: String)

@Resource("/arm")
internal data class EffectsArmResource(val parent: ProjectEffectsResource)

@Resource("/fire")
internal data class PatchFireResource(val parent: ProjectPatchResource)

@Resource("/reload")
internal data class PatchReloadResource(val parent: ProjectPatchResource)

@Resource("/{cueId}/events")
internal data class CueEventsResource(val parent: ProjectCuesResource, val cueId: Int)

@Serializable
data class ArmRequest(val on: Boolean, val seconds: Long? = null)

@Serializable
data class FireRequest(val trigger: String, val rehearse: Boolean = false)

@Serializable
data class ReloadRequest(val trigger: String? = null)

@Serializable
data class FiredDto(
    val fixture: String,
    val fixtureName: String,
    val trigger: String,
    val label: String,
    val at: String,
    val rehearsed: Boolean,
)

@Serializable
data class FireResponse(val fired: FiredDto)

/** The arm and the spent tubes over REST — `effects.armed`'s fields, without the frame's type. */
@Serializable
data class EffectsStateDto(
    val armed: Boolean,
    val armedUntil: String? = null,
    val remainingMs: Long? = null,
    val rehearsal: Boolean = false,
    val spent: List<uk.me.cormack.lighting7.plugins.SpentTubeDto> = emptyList(),
)

internal fun EffectsService.ArmState.toDto(): EffectsStateDto = toMessage().let {
    EffectsStateDto(it.armed, it.armedUntil, it.remainingMs, it.rehearsal, it.spent)
}
