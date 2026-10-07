package uk.me.cormack.lighting7.routes

import io.ktor.http.*
import io.ktor.resources.*
import io.ktor.server.request.*
import io.ktor.server.resources.get
import io.ktor.server.resources.put
import io.ktor.server.response.*
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.innerJoin
import uk.me.cormack.lighting7.models.*
import uk.me.cormack.lighting7.show.SceneryResolver
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.state.toSceneryElement

/**
 * Scenery on cues, stacks and Looks over REST (stage-view plan session 8, §3.3): a whole-list `PUT`
 * per owner — `cues/{id}/scenery`, `cue-stacks/{id}/scenery`, `looks/{id}/scenery` — body
 * `{scenery: [{elementUuid, state: {visible?, open?, trimM?}, transitionMs?}]}`, answered with the
 * list as stored. The owner's own read DTO carries the list (`CueDetails.scenery`,
 * `CueStackDetails.scenery`, `LookDto.scenery`), so there is no `GET` per owner. The one `GET` is the
 * other way round: `stage-elements/{id}/scenery`, every owner that moves one element (the
 * scenery-programmer plan's D11) — the Stage popover's and the element form's *Moves with*.
 *
 * Stored data, so ungated by the current project, like the scene document's own routes. Every
 * state is checked against its element's kind and every problem comes back at once
 * ([parseSceneryList]); a refused write touches nothing. A write fires its owner's list-changed
 * event, which is also what moves the live stage (`SceneryService` listens).
 */
internal fun Route.routeApiRestProjectScenery(state: State) {
    get<StageElementSceneryResource> { resource ->
        withProject(state, resource.parent.projectId) { project ->
            val dto = transaction(state.database) {
                DaoStageElement.findById(resource.elementId)?.takeIf { it.project.id == project.id }?.let { elementSceneryOf(it) }
            }
            if (dto == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(STAGE_ELEMENT_NOT_FOUND))
            } else {
                call.respond(dto)
            }
        }
    }

    put<CueSceneryResource> { resource ->
        withProject(state, resource.parent.projectId) { project ->
            val items = sceneryItems(call.receive<JsonObject>()) ?: return@withProject call.respondBadScenery()
            val outcome = transaction(state.database) {
                val cue = DaoCue.findById(resource.cueId)?.takeIf { it.project.id == project.id }
                    ?: return@transaction SceneryOutcome.NotFound("Cue not found")
                if (cue.cueType == CueType.MARKER.name) {
                    return@transaction SceneryOutcome.Invalid(listOf("a MARKER is never live, so it has no scenery"))
                }
                writeScenery(project, items, SceneryOwnerKind.CUE) { writes ->
                    replaceCueScenery(cue, writes)
                    cueSceneryOf(cue.id).map { it.toDto() }
                }
            }
            respondScenery(outcome) {
                state.show.fixtures.cueListChanged()
                // The stack list carries every cue's own changes (`CueStackCueEntry.scenery`).
                state.show.fixtures.cueStackListChanged()
            }
        }
    }

    put<CueStackSceneryResource> { resource ->
        withProject(state, resource.parent.projectId) { project ->
            val items = sceneryItems(call.receive<JsonObject>()) ?: return@withProject call.respondBadScenery()
            val outcome = transaction(state.database) {
                val stack = DaoCueStack.findById(resource.stackId)?.takeIf { it.project.id == project.id }
                    ?: return@transaction SceneryOutcome.NotFound("Cue stack not found")
                if (stack.type == CueStackType.SEPARATOR.name) {
                    return@transaction SceneryOutcome.Invalid(listOf("a separator is never live, so it has no set"))
                }
                writeScenery(project, items, SceneryOwnerKind.STACK) { writes ->
                    replaceStackScenery(stack, writes)
                    stackSceneryOf(stack.id).map { it.toDto() }
                }
            }
            respondScenery(outcome) { state.show.fixtures.cueStackListChanged() }
        }
    }

    put<LookSceneryResource> { resource ->
        withProject(state, resource.parent.projectId) { project ->
            val items = sceneryItems(call.receive<JsonObject>()) ?: return@withProject call.respondBadScenery()
            val outcome = transaction(state.database) {
                val look = DaoLook.findById(resource.lookId)?.takeIf { it.project.id == project.id }
                    ?: return@transaction SceneryOutcome.NotFound("Look not found")
                writeScenery(project, items, SceneryOwnerKind.LOOK) { writes ->
                    replaceLookScenery(look, writes)
                    lookSceneryOf(look.id).map { it.toDto() }
                }
            }
            respondScenery(outcome) { state.show.fixtures.lookListChanged() }
        }
    }
}

internal sealed interface SceneryOutcome {
    data class Written(val scenery: List<SceneryChangeDto>) : SceneryOutcome
    data class Invalid(val problems: List<String>) : SceneryOutcome
    data class NotFound(val message: String) : SceneryOutcome
}

/** The body's `scenery` array, or null when the body has none or it is not an array. */
private fun sceneryItems(body: JsonObject): JsonArray? {
    val raw = body["scenery"]
    return when {
        raw == null || raw is JsonNull -> null
        else -> raw as? JsonArray
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.respondBadScenery() =
    respond(HttpStatusCode.BadRequest, ErrorResponse("scenery is required: an array of {elementUuid, state, transitionMs?}"))

/**
 * Check [items] against the project's elements and, only when every one passes, [store] them.
 * Must run inside a transaction.
 */
internal fun writeScenery(
    project: DaoProject,
    items: List<kotlinx.serialization.json.JsonElement>,
    owner: SceneryOwnerKind,
    store: (List<SceneryWrite>) -> List<SceneryChangeDto>,
): SceneryOutcome {
    val problems = mutableListOf<String>()
    val writes = parseSceneryList(items, sceneryElementsOf(project), owner, "scenery", problems)
    if (problems.isNotEmpty()) return SceneryOutcome.Invalid(problems)
    return SceneryOutcome.Written(store(writes))
}

private suspend fun io.ktor.server.routing.RoutingContext.respondScenery(outcome: SceneryOutcome, changed: () -> Unit) {
    when (outcome) {
        is SceneryOutcome.Written -> {
            changed()
            call.respond(outcome.scenery)
        }
        is SceneryOutcome.Invalid -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(outcome.problems.joinToString("; ")))
        is SceneryOutcome.NotFound -> call.respond(HttpStatusCode.NotFound, ErrorResponse(outcome.message))
    }
}

@Resource("/{elementId}/scenery")
internal data class StageElementSceneryResource(val parent: ProjectStageElementsResource, val elementId: Int)

@Resource("/{cueId}/scenery")
internal data class CueSceneryResource(val parent: ProjectCuesResource, val cueId: Int)

@Resource("/{stackId}/scenery")
internal data class CueStackSceneryResource(val parent: ProjectCueStacksResource, val stackId: Int)

@Resource("/{lookId}/scenery")
internal data class LookSceneryResource(val parent: ProjectLooksResource, val lookId: Int)

/**
 * What [cue]'s card shows hatched under its own changes: every state the cue does not set itself
 * but shows anyway at the cue — tracked from the last earlier cue of its stack that set it, or held
 * by the stack's set ([SceneryResolver.trackedAt]). One entry per element, named by where its newest
 * tracked state came from. Must run inside a transaction.
 */
internal fun trackedSceneryAt(cue: DaoCue): List<TrackedSceneryDto> {
    val stack = cue.cueStack
    // Read for every cue of a cue list, so a stack with no scenery at all costs two small queries
    // and nothing more: its set, and its cues' changes through one join.
    val set = stackSceneryOf(stack.id)
    val stackRows = DaoCueSceneryRow.wrapRows(
        (DaoCueScenery innerJoin DaoCues).select(DaoCueScenery.columns).where { DaoCues.cueStack eq stack.id },
    ).toList()
    if (set.isEmpty() && stackRows.isEmpty()) return emptyList()
    val cues = DaoCue.find { DaoCues.cueStack eq stack.id }
        .sortedWith(compareBy({ it.sortOrder }, { it.id.value }))
        .filter { it.cueType == CueType.STANDARD.name || it.id == cue.id }
    val rows = stackRows
        .sortedWith(compareBy({ it.sortOrder }, { it.id.value }))
        .groupBy { it.cue.id.value }
    val elements = (set.map { it.element } + rows.values.flatten().map { it.element })
        .distinctBy { it.id.value }
        .map { it.toSceneryElement() }
    val labels = cues.associate { it.id.value to (it.cueNumber?.takeIf { n -> n.isNotBlank() } ?: it.name) }
    val tracked = SceneryResolver.trackedAt(
        elements,
        set.map { SceneryResolver.Change(it.element.id.value, decodeSceneryState(it.stateJson)) },
        cues.map { c ->
            SceneryResolver.Cue(
                c.id.value,
                labels.getValue(c.id.value),
                rows[c.id.value].orEmpty().map { SceneryResolver.CueChange(it.element.id.value, decodeSceneryState(it.stateJson), 0) },
            )
        },
        cue.id.value,
    )
    val order = cues.withIndex().associate { (i, c) -> c.id.value to i }
    return tracked.map { (r, sources) ->
        // The newest source among the tracked states names the entry: the latest cue, else the set.
        val newest = sources.values.filterIsInstance<SceneryResolver.Source.CueRow>().maxByOrNull { order[it.cueId] ?: -1 }
        TrackedSceneryDto(
            elementUuid = r.element.uuid.toString(),
            elementName = r.element.name,
            state = sceneryStateObject(SceneryResolver.only(r.state, sources.keys)),
            fromCueId = newest?.cueId,
            fromCueLabel = newest?.cueLabel,
            fromSet = newest == null,
        )
    }
}

/**
 * A sweep took scenery rows off their owners — an element delete (`deleteSceneryForElements`): announce
 * every list that carries them, the cue list, the stack list (its `cues[].scenery` and each stack's
 * set, scenery-programmer plan D13/D14) and the Look list, so no window keeps editing a row for an
 * element that is gone (the desk refuses a whole-list `PUT` naming one).
 */
internal fun sceneryOwnersChanged(state: State) {
    state.show.fixtures.cueListChanged()
    state.show.fixtures.cueStackListChanged()
    state.show.fixtures.lookListChanged()
}

/**
 * Every owner that moves one element (scenery-programmer plan D11): the cues that change it, the
 * stacks whose set holds it, and the Looks that show it while live — `GET
 * stage-elements/{id}/scenery`, the Stage popover's and the element form's *Moves with*. Each entry
 * is the owner's own stored row, never what it tracks: a cue that only inherits the piece from an
 * earlier one is not listed. Cues in show order (their stack's place, then theirs), sets in stack
 * order, Looks by name. Must run inside a transaction.
 */
internal fun elementSceneryOf(element: DaoStageElement): ElementSceneryDto {
    val cueRows = DaoCueSceneryRow.find { DaoCueScenery.element eq element.id }.toList()
        .sortedWith(compareBy({ it.cue.cueStack.sortOrder }, { it.cue.cueStack.id.value }, { it.cue.sortOrder }, { it.cue.id.value }))
    val setRows = DaoCueStackSceneryRow.find { DaoCueStackScenery.element eq element.id }.toList()
        .sortedWith(compareBy({ it.stack.sortOrder }, { it.stack.id.value }))
    val lookRows = DaoLookSceneryRow.find { DaoLookScenery.element eq element.id }.toList()
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.look.name })
    return ElementSceneryDto(
        cues = cueRows.map { row ->
            ElementCueSceneryDto(
                stackId = row.cue.cueStack.id.value,
                cueId = row.cue.id.value,
                // The cue's number where it has one, else its name — `trackedSceneryAt`'s label.
                label = row.cue.cueNumber?.takeIf { it.isNotBlank() } ?: row.cue.name,
                state = storedParamsObject(row.stateJson),
                transitionMs = row.transition?.toMillis(),
            )
        },
        sets = setRows.map { row ->
            ElementSetSceneryDto(stackId = row.stack.id.value, name = row.stack.name, state = storedParamsObject(row.stateJson))
        },
        looks = lookRows.map { row ->
            ElementLookSceneryDto(lookId = row.look.id.value, name = row.look.name, state = storedParamsObject(row.stateJson))
        },
    )
}

/** `GET stage-elements/{id}/scenery`: what moves one element, by owner (D11). */
@Serializable
data class ElementSceneryDto(
    val cues: List<ElementCueSceneryDto>,
    val sets: List<ElementSetSceneryDto>,
    val looks: List<ElementLookSceneryDto>,
)

/** A cue that moves the element on GO, on its own clock ([transitionMs]; null moves with the cue's fade). */
@Serializable
data class ElementCueSceneryDto(
    val stackId: Int,
    val cueId: Int,
    val label: String,
    val state: JsonObject,
    val transitionMs: Long?,
)

/** A stack whose set holds the element while it is live. */
@Serializable
data class ElementSetSceneryDto(val stackId: Int, val name: String, val state: JsonObject)

/** A Look that shows the element while live. */
@Serializable
data class ElementLookSceneryDto(val lookId: Int, val name: String, val state: JsonObject)
