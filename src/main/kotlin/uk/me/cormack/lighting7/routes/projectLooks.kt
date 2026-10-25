package uk.me.cormack.lighting7.routes

import uk.me.cormack.lighting7.models.timingOf
import uk.me.cormack.lighting7.models.storedTiming
import uk.me.cormack.lighting7.fixture.TriggerIndex
import uk.me.cormack.lighting7.fixture.CommandIndex

import io.ktor.http.*
import io.ktor.resources.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.*
import io.ktor.server.routing.Route
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.me.cormack.lighting7.models.LayerSource
import uk.me.cormack.lighting7.fx.FxSpecCoercion
import uk.me.cormack.lighting7.fx.AttributeFamily
import uk.me.cormack.lighting7.fx.canonicalAttributeName
import uk.me.cormack.lighting7.fx.attributeFamilyForFxFamily
import uk.me.cormack.lighting7.fx.FxRegistry
import uk.me.cormack.lighting7.fx.speedMasterUuidOrNull
import uk.me.cormack.lighting7.fx.familyForAttribute
import uk.me.cormack.lighting7.fx.toMaskNames
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.DEFERRED_TARGET_TYPE
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.BuskPadKind
import uk.me.cormack.lighting7.models.DaoCueLayer
import uk.me.cormack.lighting7.models.DaoCueLayers
import uk.me.cormack.lighting7.models.DaoCueSlots
import uk.me.cormack.lighting7.models.DaoLook
import uk.me.cormack.lighting7.models.DaoLookFxEntity
import uk.me.cormack.lighting7.models.DaoLookFx
import uk.me.cormack.lighting7.models.DaoLookRow
import uk.me.cormack.lighting7.models.DaoLookRows
import uk.me.cormack.lighting7.models.DaoLookScenery
import uk.me.cormack.lighting7.models.DaoLookSceneryRow
import uk.me.cormack.lighting7.models.SceneryChangeDto
import uk.me.cormack.lighting7.models.deleteLookScenery
import uk.me.cormack.lighting7.models.lookSceneryOf
import uk.me.cormack.lighting7.models.storedParamsObject
import uk.me.cormack.lighting7.models.toDto
import uk.me.cormack.lighting7.models.DaoLooks
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.LookFxDto
import uk.me.cormack.lighting7.models.LookRowDto
import uk.me.cormack.lighting7.models.FxTargetsRef
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.show.Fixtures
import uk.me.cormack.lighting7.state.State
import java.util.UUID
import java.time.Duration
import uk.me.cormack.lighting7.models.asDuration

/** Error code the client keys the "flatten these into local rows, then delete" recovery flow off. */
internal const val CODE_LOOK_IN_USE = "LOOK_IN_USE"

/** How many distinct literals a summary's preview carries. Enough to recognise, short enough to send. */
private const val LOOK_PREVIEW_SIZE = 8

private const val LOOK_NOT_FOUND = "Look not found"

internal fun Route.routeApiRestProjectLooks(state: State) {
    // GET /projects/{id}/looks?family=COLOUR
    get<ProjectLooksResource> { resource ->
        withProject(state, resource.projectId) { project ->
            val familyFilter = resource.family?.trim()?.takeIf { it.isNotEmpty() }
            val parsedFamily = familyFilter?.let { raw ->
                AttributeFamily.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
            }
            if (familyFilter != null && parsedFamily == null) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(
                        "Unknown family '$familyFilter' — expected one of " +
                            AttributeFamily.entries.joinToString { it.name }
                    ),
                )
                return@withProject
            }
            val looks = transaction(state.database) {
                val all = DaoLook.find { DaoLooks.project eq project.id }
                    .orderBy(DaoLooks.name to SortOrder.ASC)
                    .toList()
                val usage = lookUsageFor(all.map { it.id.value })
                val scenery = lookScenerySummariesFor(all.map { it.id.value })
                all.map { it.toSummaryDto(state, usage[it.id.value], scenery[it.id.value].orEmpty()) }
            }
            // Banked by *derived* family, so the filter is applied after the summaries are built —
            // there is no stored column to query on, by design (§3.1).
            call.respond(
                if (parsedFamily == null) looks else looks.filter { parsedFamily.name in it.families }
            )
        }
    }

    // GET /projects/{id}/looks/{lookId}
    get<ProjectLookResource> { resource ->
        withProject(state, resource.parent.projectId) { project ->
            val details = transaction(state.database) {
                val look = DaoLook.findById(resource.lookId) ?: return@transaction null
                if (look.project.id != project.id) return@transaction null
                look.toDetailsDto(state)
            }
            if (details == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(LOOK_NOT_FOUND))
            } else {
                call.respond(details)
            }
        }
    }

    // POST /projects/{id}/looks
    post<ProjectLooksResource> { resource ->
        withCurrentProject(state, resource.projectId) { project ->
            val request = call.receive<CreateLookRequest>()
            val name = request.name.trim()
            if (name.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Look name must not be blank"))
                return@withCurrentProject
            }
            validateLookRows(request.rows)?.let { problem ->
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(problem))
                return@withCurrentProject
            }
            validateLookFx(request.fx, state.show.fxRegistry)?.let { problem ->
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(problem))
                return@withCurrentProject
            }
            val result = transaction(state.database) {
                val duplicate = DaoLook.find {
                    (DaoLooks.project eq project.id) and (DaoLooks.name eq name)
                }.firstOrNull()
                if (duplicate != null) return@transaction null
                val look = DaoLook.new {
                    this.project = project
                    this.name = name
                    this.notes = request.notes
                }
                writeLookChildren(look, request.rows, request.fx, created = true)
                look.toDetailsDto(state)
            }
            if (result == null) {
                call.respond(
                    HttpStatusCode.Conflict,
                    ErrorResponse("A look named '$name' already exists in this project"),
                )
                return@withCurrentProject
            }
            state.show.fixtures.lookListChanged()
            call.respond(HttpStatusCode.Created, result)
        }
    }

    // PUT /projects/{id}/looks/{lookId}
    //
    // Received as a raw JsonObject so absent and explicitly-null are distinguishable: omitting
    // `rows` leaves the contents alone (metadata-only edit), while sending `[]` empties them.
    put<ProjectLookResource> { resource ->
        withCurrentProject(state, resource.parent.projectId) { project ->
            val body = call.receive<JsonObject>()
            unknownFieldsProblem(body, LOOK_PUT_FIELDS)?.let { problem ->
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(problem))
                return@withCurrentProject
            }
            // Strict: a row or FX field it does not know is an old name (`propertyName`, `size`),
            // refused rather than dropped to its default.
            val rows = if ("rows" in body) {
                try {
                    Json.decodeFromJsonElement<List<LookRowDto>>(body["rows"]!!)
                } catch (e: SerializationException) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("rows: ${e.message}"))
                    return@withCurrentProject
                }
            } else null
            val effects = if ("fx" in body) {
                try {
                    Json.decodeFromJsonElement<List<LookFxDto>>(body["fx"]!!)
                } catch (e: SerializationException) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("fx: ${e.message}"))
                    return@withCurrentProject
                }
            } else null
            val outcome = transaction(state.database) {
                val look = DaoLook.findById(resource.lookId) ?: return@transaction LookWriteOutcome.NotFound
                if (look.project.id != project.id) return@transaction LookWriteOutcome.NotFound

                body["name"].nullableString()?.trim()?.takeIf { it.isNotEmpty() }?.let { newName ->
                    if (newName != look.name) {
                        val clash = DaoLook.find {
                            (DaoLooks.project eq project.id) and (DaoLooks.name eq newName)
                        }.firstOrNull()
                        if (clash != null) return@transaction LookWriteOutcome.NameTaken(newName)
                        look.name = newName
                    }
                }
                if ("notes" in body) look.notes = body["notes"].nullableString()
                val hasRows = "rows" in body
                val hasFx = "fx" in body
                rows?.let { validateLookRows(it) }?.let { return@transaction LookWriteOutcome.Invalid(it) }
                effects?.let { validateLookFx(it, state.show.fxRegistry) }?.let { return@transaction LookWriteOutcome.Invalid(it) }

                if (hasRows || hasFx) writeLookChildren(look, rows, effects)

                LookWriteOutcome.Written(look.toDetailsDto(state), look.uuid, hasRows || hasFx)
            }
            when (outcome) {
                is LookWriteOutcome.NotFound ->
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(LOOK_NOT_FOUND))
                is LookWriteOutcome.NameTaken -> call.respond(
                    HttpStatusCode.Conflict,
                    ErrorResponse("A look named '${outcome.name}' already exists in this project"),
                )
                is LookWriteOutcome.Invalid ->
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(outcome.problem))
                is LookWriteOutcome.Written -> {
                    if (outcome.contentsChanged) {
                        // A contents change republishes the live consumers directly — that one edit
                        // moving every cue that layers this Look, with no cue re-fired, is the
                        // feature's whole point. `lookListChanged` would only drop every cached
                        // expansion, which is a blunter and noisier signal.
                        republishForLookEdit(state, outcome.uuid)
                    } else {
                        state.show.fixtures.lookListChanged()
                    }
                    call.respond(outcome.details)
                }
            }
        }
    }

    // DELETE /projects/{id}/looks/{lookId}?force=true
    delete<ProjectLookResource> { resource ->
        withCurrentProject(state, resource.parent.projectId) { project ->
            when (val outcome = performLookDelete(state, project, resource.lookId, resource.force)) {
                is LookDeleteOutcome.NotFound ->
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(LOOK_NOT_FOUND))
                is LookDeleteOutcome.InUse -> call.respond(
                    HttpStatusCode.Conflict,
                    LookInUseResponse(
                        error = "This look is still used by ${outcome.usage.describe()}",
                        code = CODE_LOOK_IN_USE,
                        layerCount = outcome.usage.layerCount,
                        cueIds = outcome.usage.cueIds,
                        cueNames = outcome.usage.cueNames,
                    ),
                )
                is LookDeleteOutcome.Deleted -> call.respond(HttpStatusCode.NoContent)
            }
        }
    }

    // POST /projects/{id}/looks/{lookId}/copy
    post<CopyLookResource> { resource ->
        withProject(state, resource.parent.projectId) { project ->
            val request = call.receive<CopyLookRequest>()
            if (refusedCopyTarget(state, request.targetProjectId)) return@withProject
            val result = transaction(state.database) {
                val source = DaoLook.findById(resource.lookId) ?: return@transaction null
                if (source.project.id != project.id) return@transaction null
                val target = state.resolveProject(request.targetProjectId.toString())
                    ?: return@transaction null
                val newName = request.newName?.trim()?.takeIf { it.isNotEmpty() } ?: source.name
                val clash = DaoLook.find {
                    (DaoLooks.project eq target.id) and (DaoLooks.name eq newName)
                }.firstOrNull()
                if (clash != null) return@transaction CopyLookOutcome.NameTaken(newName)
                val copy = DaoLook.new {
                    this.project = target
                    this.name = newName
                    this.notes = source.notes
                }
                // Fresh uuids on every child: a copy is a new entity, and reusing the source's
                // uuid would make sync treat the two as one record.
                for (row in source.rows.sortedBy { it.sortOrder }) {
                    DaoLookRow.new {
                        look = copy
                        targetType = row.targetType
                        targetKey = row.targetKey
                        attribute = row.attribute
                        value = row.value
                        fadeDuration = row.fadeDuration
                        elementKey = row.elementKey
                        sortOrder = row.sortOrder
                    }
                }
                for (effect in source.fxInOrder()) {
                    DaoLookFxEntity.new {
                        look = copy
                        targetType = effect.targetType
                        targetKey = effect.targetKey
                        fxType = effect.fxType
                        family = effect.family
                        attribute = effect.attribute
                        cycle = effect.cycle
                        blendMode = effect.blendMode
                        order = effect.order
                        phaseOffset = effect.phaseOffset
                        elementMode = effect.elementMode
                        elementFilter = effect.elementFilter
                        timing = effect.timing
                        parameters = effect.parameters
                        speedMasterUuid = effect.speedMasterUuid
                        rateSpeedMasterUuid = effect.rateSpeedMasterUuid
                        seconds = effect.seconds
                        amount = effect.amount
                        direction = effect.direction
                        runs = effect.runs
                        this.spread = effect.spread
                        this.wings = effect.wings
                        this.segments = effect.segments
                        this.parts = effect.parts
                        sortOrder = effect.sortOrder
                    }
                }
                // Its scenery within the project only: an element belongs to one project's scene.
                if (target.id == project.id) {
                    for (row in lookSceneryOf(source.id)) {
                        DaoLookSceneryRow.new {
                            look = copy
                            element = row.element
                            stateJson = row.stateJson
                            sortOrder = row.sortOrder
                        }
                    }
                }
                CopyLookOutcome.Copied(
                    CopyLookResponse(
                        lookId = copy.id.value,
                        lookName = copy.name,
                        targetProjectId = target.id.value,
                        targetProjectName = target.name,
                        message = "Copied '${source.name}' to '${target.name}' as '${copy.name}'",
                    )
                )
            }
            when (result) {
                null -> call.respond(HttpStatusCode.NotFound, ErrorResponse(LOOK_NOT_FOUND))
                is CopyLookOutcome.NameTaken -> call.respond(
                    HttpStatusCode.Conflict,
                    ErrorResponse("A look named '${result.name}' already exists in the target project"),
                )
                is CopyLookOutcome.Copied -> {
                    state.show.fixtures.lookListChanged()
                    call.respond(result.response)
                }
            }
        }
    }

    // POST /projects/{id}/looks/{lookId}/apply — *Apply as values* (apply-model plan §3.5): the
    // Look's rows as `WEB` literals and its effects as Local effects, as a press followed by Detach
    // would leave them, with no layer ever on the stack. Not a toggle: a second apply writes the same
    // values again. Whole, as a press is (absolute-looks plan P6): the request's targets and mask are
    // read and ignored.
    post<ApplyLookResource> { resource ->
        withCurrentProject(
            state,
            resource.parent.projectId,
            { p -> "Cannot apply looks in project '${p.name}' - only the current project is live" },
        ) { project ->
            val request = call.receive<ApplyLookRequest>()
            val look = transaction(state.database) {
                DaoLook.findById(resource.lookId)?.takeIf { it.project.id == project.id }?.layerSource()
            }
            if (look == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(LOOK_NOT_FOUND))
                return@withCurrentProject
            }
            try {
                call.respond(applyLookAsValues(state, look, request.fadeMs))
            } catch (e: IllegalStateException) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(e.message ?: "Target not found"))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Failed to apply look"))
            }
        }
    }

    // POST /projects/{id}/looks/{lookId}/toggle
    //
    // The tap / cue-slot path: put this Look on as a **programmer layer**, or take it off again. A
    // Look is pressed whole (absolute-looks plan D1, P1): on puts one layer of it on top, naming no
    // targets and no mask, and off takes every layer of it off, whatever is selected. The request's
    // targets and mask are read and ignored (P2, D3). A Look none of whose fixtures is patched lands
    // nothing, as a cue does, and its ring still lights (P4). No solo siblings, and a Look replaces
    // nothing (apply-model D6): the busk page's press goes through `POST /busk/pads/{id}/press`,
    // which resolves the bank's siblings and calls the same `toggle` with them (busk-layout plan D4).
    post<ToggleLookResource> { resource ->
        withCurrentProject(
            state,
            resource.parent.projectId,
            { p -> "Cannot toggle looks in project '${p.name}' - only the current project is live" },
        ) { project ->
            val request = call.receive<ToggleLookRequest>()
            val look = transaction(state.database) {
                DaoLook.findById(resource.lookId)?.takeIf { it.project.id == project.id }?.layerSource()
            }
            if (look == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(LOOK_NOT_FOUND))
                return@withCurrentProject
            }

            try {
                val outcome = state.show.programmerLayerStack.toggle(
                    source = look,
                    targets = emptyList(),
                    cycleOverride = request.cycle,
                    fadeMs = request.fadeMs ?: 0,
                )
                call.respond(ToggleLookResponse(outcome.action, outcome.fxCount))
            } catch (e: IllegalStateException) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(e.message ?: "Target not found"))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Failed to toggle look"))
            }
        }
    }

}

// ─── Toggle wire shapes ──────────────────────────────────────────────────
//
// These lived in `routes/projectFxPresets.kt` as `TogglePresetRequest` / `TogglePresetResponse`,
// kept behind after that file's route function was unmounted precisely so the desk would not have
// to change across the Looks rewrite. Session 4 deleted the file; the shapes moved here and took
// Look names. **Every JSON field name is unchanged**, so this is a Kotlin-side rename only and no
// client had to move with it.
//
// `TogglePresetTarget` did *not* come with them: it was field-for-field identical to
// [CueTargetDto], down to the `TargetRef` constructor and accessor, so it collapsed into it rather
// than being renamed. One target DTO, not two.

/**
 * Body of `POST /looks/{id}/toggle` — put the Look on as a programmer layer, or take it off. A Look
 * is pressed whole (absolute-looks plan P1), so [targets] and [families] are read and ignored: the
 * fields stay because the client sends them, and the server's Json refuses an unknown key.
 */
@Serializable
internal data class ToggleLookRequest(
    val targets: List<CueTargetDto> = emptyList(),
    val cycle: Double? = null,
    val families: List<String>? = null,
    /**
     * The programmer fade, over which a press grows its effects and a release shrinks them (effects
     * plan D13), and which then covers every value too. Absent or 0: values at each row's own fade
     * on the way on and a snap on the way off, effects at once — the press as it was.
     */
    val fadeMs: Long? = null,
)

/** Body of `POST /looks/{id}/apply`: [ToggleLookRequest]'s ignored targets and mask, and a fade for the values. */
@Serializable
internal data class ApplyLookRequest(
    val targets: List<CueTargetDto> = emptyList(),
    val families: List<String>? = null,
    /** Over every value; absent is each row's own fade, as a press lands them. */
    val fadeMs: Long? = null,
)

@Serializable
internal data class ApplyLookResponse(
    /** Programmer values written into Local. */
    val written: Int,
    /** The Local effects started, one per effect. */
    val fxIds: List<Long> = emptyList(),
    /** Always empty: a Look lands whole, under no mask (D3). Kept on the wire until the client stops reading it. */
    val skippedFamilies: List<String> = emptyList(),
)

/**
 * *Apply as values* for a Look — the REST route's and MCP `apply_look`'s `asValues`: the whole Look,
 * no targets and no mask ([uk.me.cormack.lighting7.fx.ProgrammerLayerStack.applyAsValues]).
 */
internal fun applyLookAsValues(
    state: State,
    look: LayerSource,
    fadeMs: Long?,
    cycle: Double? = null,
): ApplyLookResponse {
    val outcome = state.show.programmerLayerStack.applyAsValues(
        source = look,
        targets = emptyList(),
        cycleOverride = cycle,
        fadeMs = fadeMs,
    )
    return ApplyLookResponse(outcome.written, outcome.fxIds)
}

/** The layer source a press of this Look puts on. */
internal fun DaoLook.layerSource(): LayerSource = LayerSource.look(id.value, uuid, name)

/**
 * The targets this Look names itself — every row's and every effect's, distinct, in row order —
 * keeping only those that resolve in the live patch: the fixtures and groups a press of it lands
 * on. A row on a fixture that is no longer patched contributes nothing rather than failing, the
 * `PaletteResolver` rule (an unsupported head contributes nothing, never a default). With no live
 * patch ([fixtures] null, a project that is not running) every named target is kept. Must be called
 * inside a transaction.
 */
internal fun DaoLook.ownTargets(fixtures: Fixtures?): List<CueTargetDto> {
    val named = LinkedHashSet<CueTargetDto>()
    rows.forEach { row -> if (row.target != null) named.add(CueTargetDto(row.targetType, row.targetKey)) }
    effects.forEach { effect -> effect.target?.items?.mapTo(named) { CueTargetDto(it) } }
    if (fixtures == null) return named.toList()
    return named.filter { target ->
        when (TargetRef.ofOrNull(target.type, target.key)) {
            is TargetRef.Fixture -> runCatching { fixtures.untypedGroupableFixture(target.key) }.isSuccess
            is TargetRef.Group -> runCatching { fixtures.untypedGroup(target.key) }.isSuccess
            else -> false
        }
    }
}

/** `Colour`, `Colour + Position` — the family names as an operator reads them, in declaration order. */
internal fun describeFamilies(families: Set<AttributeFamily>): String =
    families.toMaskNames().joinToString(" + ") { it.lowercase().replaceFirstChar(Char::uppercase) }

@Serializable
internal data class ToggleLookResponse(
    /** `"applied"` or `"removed"`. */
    val action: String,
    val fxCount: Int,
    /** Always empty: a Look press takes no mask (D3). Kept on the wire until the client stops reading it. */
    val skippedFamilies: List<String> = emptyList(),
)

// ─── Resources ──────────────────────────────────────────────────────────

@Resource("/{projectId}/looks")
internal data class ProjectLooksResource(val projectId: String, val family: String? = null)

@Resource("/{lookId}")
internal data class ProjectLookResource(
    val parent: ProjectLooksResource,
    val lookId: Int,
    val force: Boolean = false,
)

@Resource("/{lookId}/copy")
internal data class CopyLookResource(val parent: ProjectLooksResource, val lookId: Int)

@Resource("/{lookId}/toggle")
internal data class ToggleLookResource(val parent: ProjectLooksResource, val lookId: Int)

@Resource("/{lookId}/apply")
internal data class ApplyLookResource(val parent: ProjectLooksResource, val lookId: Int)

// ─── DTOs ───────────────────────────────────────────────────────────────

@Serializable
internal data class LookDto(
    val id: Int,
    val uuid: String,
    val name: String,
    val notes: String? = null,
    /**
     * Attribute families this Look touches, **derived** from its rows rather than stored. A Look
     * spanning colour and position reports both — which is the point of not having a type column.
     */
    val families: List<String>,
    val rowCount: Int,
    val fxCount: Int,
    val targetCount: Int,
    /**
     * Always false: a Look effect names its own target (absolute-looks plan D2). Kept on the wire
     * until the client stops reading it.
     */
    val hasDeferredFx: Boolean,
    val preview: List<String>,
    /**
     * The effects this Look runs, by type, in its own order and each once — the library's Preview
     * says *Colour Cycle* rather than only that there are effects. `@EncodeDefault(ALWAYS)` for
     * [scenery]'s reason; the client treats it as optional all the same.
     */
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val fxTypes: List<String> = emptyList(),
    /** How many cue layers reference this Look. Gates delete. */
    val layerCount: Int,
    /**
     * How many busk pages hold a pad for this Look — the library row's "on *n* pages" hint, and the
     * line the delete confirm shows before taking those pads with it. A hint, not a use: it does
     * not gate delete, [layerCount] alone still does.
     *
     * **No default**, for [PaletteDto.buskPageCount]'s reason: a converter that omits defaults would
     * keep a zero off the wire, and a copier that forgets it should fail to compile.
     */
    val buskPageCount: Int,
    /**
     * What this Look shows while live, one entry per element in its own order — the library's
     * Scenery read-out (scenery-programmer plan D10), a summary such as *Moon in · Sofa shown*.
     * `@EncodeDefault(ALWAYS)` so an empty list arrives as "no scenery" rather than as a field an
     * older desk never sent; the client treats it as optional all the same.
     */
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val scenery: List<LookScenerySummaryDto> = emptyList(),
)

/** One element a Look's scenery moves, as the library row summarises it: the element and its states. */
@Serializable
internal data class LookScenerySummaryDto(
    val elementUuid: String,
    val elementName: String,
    val state: JsonObject,
)

@Serializable
internal data class LookDetails(
    val id: Int,
    val uuid: String,
    val name: String,
    val notes: String? = null,
    val families: List<String>,
    val rows: List<LookRowDto> = emptyList(),
    val fx: List<LookFxDto> = emptyList(),
    val layerCount: Int,
    val usedByCueIds: List<Int> = emptyList(),
    val usedByCueNames: List<String> = emptyList(),
    /** What this Look shows while live (stage-view plan session 8); edited by `PUT looks/{id}/scenery`. */
    val scenery: List<SceneryChangeDto> = emptyList(),
)

@Serializable
internal data class CreateLookRequest(
    val name: String,
    val notes: String? = null,
    val rows: List<LookRowDto> = emptyList(),
    val fx: List<LookFxDto> = emptyList(),
)

@Serializable
internal data class LookInUseResponse(
    val error: String,
    val code: String,
    val layerCount: Int,
    val cueIds: List<Int>,
    val cueNames: List<String>,
)

@Serializable
internal data class CopyLookRequest(val targetProjectId: Int, val newName: String? = null)

@Serializable
internal data class CopyLookResponse(
    val lookId: Int,
    val lookName: String,
    val targetProjectId: Int,
    val targetProjectName: String,
    val message: String,
)

private sealed interface LookWriteOutcome {
    data object NotFound : LookWriteOutcome
    data class NameTaken(val name: String) : LookWriteOutcome
    data class Invalid(val problem: String) : LookWriteOutcome
    data class Written(
        val details: LookDetails,
        val uuid: UUID,
        val contentsChanged: Boolean,
    ) : LookWriteOutcome
}

internal sealed interface LookDeleteOutcome {
    data object NotFound : LookDeleteOutcome
    data class InUse(val usage: LookUsage) : LookDeleteOutcome
    /**
     * The busk pages that lost a pad and whether any slot went, so the handler can say so, and the
     * cues whose layers a forced delete took with it — collected before the rows went, because
     * afterwards nothing names the Look to find them by.
     */
    data class Deleted(
        val uuid: UUID,
        val name: String,
        val pageIds: Set<Int>,
        val slotsRemoved: Boolean,
        val layeredCueIds: Set<Int>,
    ) : LookDeleteOutcome
}

/**
 * Delete a Look of [project], refused while a cue layers it unless [force] — which deletes those
 * layers too. The REST delete and the MCP `delete_look`.
 */
internal fun performLookDelete(state: State, project: DaoProject, lookId: Int, force: Boolean): LookDeleteOutcome {
    val outcome = transaction(state.database) {
        val look = DaoLook.findById(lookId) ?: return@transaction LookDeleteOutcome.NotFound
        if (look.project.id != project.id) return@transaction LookDeleteOutcome.NotFound
        val usage = lookUsage(look.id.value)
        if (usage.total > 0 && !force) {
            return@transaction LookDeleteOutcome.InUse(usage)
        }
        val uuid = look.uuid
        val name = look.name
        // No DB-level ON DELETE CASCADE — SQLite doesn't enforce cascades without a
        // per-connection pragma, so children go first, explicitly.
        val layeredCueIds = if (force) {
            val layers = DaoCueLayer.find { DaoCueLayers.look eq look.id }.toList()
            val cueIds = layers.map { it.cue.id.value }.toSet()
            layers.forEach { it.delete() }
            cueIds
        } else {
            emptySet()
        }
        look.rows.forEach { it.delete() }
        look.effects.forEach { it.delete() }
        // Its busk pads and cue slots go with it, unconditionally — a place on a page or
        // a tile is an enrichment, not a use the guard above counts (busk-layout plan D3).
        val pageIds = deleteBuskPadsReferencing(lookId = look.id.value)
        val slots = DaoCueSlots.deleteWhere { DaoCueSlots.look eq look.id }
        deleteLookScenery(look.id)
        look.delete()
        LookDeleteOutcome.Deleted(uuid, name, pageIds, slots > 0, layeredCueIds)
    }
    if (outcome is LookDeleteOutcome.Deleted) {
        // Release the programmer layers naming it, as the template delete does. The
        // guard above counts cue layers only, so a Look lit from a busk pad or a slot
        // deletes without `force` — and its layer would otherwise sit on the stack under
        // a record that no longer exists, asserting nothing, with no pad left to press.
        state.show.programmerLayerStack.release(setOf(outcome.uuid))
        // Keep the operator's include indicator honest — the same reason
        // `clearIncludeTargetForCue` is called on a cue delete.
        state.show.programmerStore.clearIncludeTargetForLook(lookId)
        if (outcome.layeredCueIds.isNotEmpty()) {
            // *Delete anyway* took cue layers with it. A live cue that layered the Look would
            // otherwise keep transmitting its values until re-fired, so rebuild its rows the way a
            // Look edit does (effects it spawned from the Look run until the cue stops, as for an edit) — after the release above, so the programmer is already right when
            // the cue rows republish under it. Dark ones are announced through `cuesRecomposed`.
            republishForLookDelete(state, outcome.uuid, outcome.layeredCueIds)
            // A layer is part of the cue: its count, the cue table and the Prompt Book read it.
            state.show.fixtures.cueListChanged()
            state.show.fixtures.cueStackListChanged()
        }
        state.show.fixtures.lookListChanged()
        if (outcome.pageIds.isNotEmpty()) state.show.fixtures.buskLayoutChanged(outcome.pageIds.toList())
        if (outcome.slotsRemoved) state.show.fixtures.cueSlotListChanged()
    }
    return outcome
}

private sealed interface CopyLookOutcome {
    data class NameTaken(val name: String) : CopyLookOutcome
    data class Copied(val response: CopyLookResponse) : CopyLookOutcome
}

// ─── Usage, validation and mapping ──────────────────────────────────────

/** Every field the Look PUT reads; anything else is refused by name. */
private val LOOK_PUT_FIELDS = setOf("name", "notes", "rows", "fx")

/**
 * How many cue layers reference a Look, and which cues they belong to.
 *
 * A layer references its Look through a real FK column, so this is one plain indexed query. Until
 * session 4 there was a second half — an exact-equality scan over opaque `value` text for rows
 * holding `ref:{uuid}` — inherited from the named-palette era and counted separately as
 * `refRowCount`. It retired with the grammar. The delete guard must keep seeing exactly what `republishForLookEdit`'s
 * [cuesReferencingLook] sees, or a Look reported as "used by nothing" gets deleted out from
 * under a cue that still resolves through it; both are now the same single FK query.
 */
internal data class LookUsage(
    val layerCount: Int,
    val cueIds: List<Int>,
    val cueNames: List<String>,
    /**
     * How many busk pages hold a pad for this Look — a **hint**, not a use. Batched here beside the
     * layer count for the reason [PaletteUsage.buskPageCount] gives, and kept out of [describe]
     * for the same one: a pad does not gate a delete (busk-layout plan D3).
     */
    val buskPageCount: Int = 0,
) {
    val total: Int get() = layerCount

    fun describe(): String {
        if (layerCount == 0) return "nothing"
        val what = "$layerCount cue layer(s)"
        return if (cueNames.isEmpty()) what else "$what in ${cueNames.joinToString(", ")}"
    }
}

/** Must be called inside a transaction. */
internal fun lookUsage(lookId: Int): LookUsage =
    lookUsageFor(listOf(lookId))[lookId] ?: LookUsage(0, emptyList(), emptyList(), 0)

/** Batched form for a list response — one query instead of one per Look. */
internal fun lookUsageFor(lookIds: Collection<Int>): Map<Int, LookUsage> {
    if (lookIds.isEmpty()) return emptyMap()
    val ids = lookIds.toList()
    val layersByLook = DaoCueLayer.find { DaoCueLayers.look inList ids }
        .toList()
        // `look` is nullable now (a layer may name a template instead), but this query already
        // filtered on it, so every row here has one. `mapNotNull`-style defensiveness would only
        // hide a contradiction between the filter and the grouping.
        .groupBy { it.look!!.id.value }
    val buskPages = buskPageCountsFor(BuskPadKind.LOOK, ids)

    return ids.associateWith { lookId ->
        val layers = layersByLook[lookId].orEmpty()
        val cues = layers.map { it.cue }.distinctBy { it.id.value }
        LookUsage(
            layerCount = layers.size,
            cueIds = cues.map { it.id.value }.sorted(),
            cueNames = cues.map { it.name }.sorted(),
            buskPageCount = buskPages[lookId] ?: 0,
        )
    }
}

/**
 * The value prefixes a Look row may never start with.
 *
 * Spelled out here rather than imported, because the module that used to own `ref:` —
 * `fx/PaletteRef.kt` — is gone, and the point of [validateLookRows]'s check is to survive exactly
 * that. `pal:` is listed for the same reason ahead of time: `fx/PaletteColourSource.kt` owns that
 * grammar today and this guard must not depend on it still existing tomorrow. `tmpl:` is the name it
 * had before, which a client may still send.
 */
private val LOOK_ROW_REFERENCE_PREFIXES = listOf("ref:", "tmpl:", "pal:")

/**
 * Reject rows a Look must never hold. Returns the problem, or null when every row is acceptable.
 *
 * The reference rejection is what keeps **Looks from nesting**, so resolution can never recurse, and
 * it **must outlive the grammars it names**. Nothing authors a `ref:` any more — the parser, the
 * resolver and every producer retired in session 4 — and `pal:` is legal only in an *effect
 * parameter*, never in a value. But this is a *write boundary*, and the value it guards is free text
 * a client supplies. It is deliberately an inlined shape check rather than a call into a shared
 * parser, so that deleting the last reader of either grammar cannot quietly delete the guarantee
 * `FU-LOOK-NESTED` rests on. `LookRoutesTest` pins it.
 */
internal fun validateLookRows(rows: List<LookRowDto>): String? {
    for (row in rows) {
        // **A Look row is always bound.** The deferred half of this entity became a template in
        // session 3, and a deferred row here would now be a second, weaker way to express one: no
        // family constraint, no intent resolution, and invisible to `/palettes`. Refusing it at the
        // write boundary is what makes "a Look names its own fixtures" true rather than customary.
        // `validateLookFx` holds a Look's effects to the same rule.
        if (row.targetType == DEFERRED_TARGET_TYPE) {
            return "A look row must name its own fixture or group. A value you point at a selection " +
                "is a palette — create one in the palette library instead."
        }
        if (TargetRef.ofOrNull(row.targetType, row.targetKey) == null) {
            return "Unknown target type '${row.targetType}' — expected 'fixture' or 'group'"
        }
        if (row.attribute.isBlank()) return "Row attribute must not be blank"
        if (row.value.isBlank()) return "Row value must not be blank"
        if (LOOK_ROW_REFERENCE_PREFIXES.any { row.value.trimStart().startsWith(it, ignoreCase = true) }) {
            return "A look row must hold a literal, not a reference — looks do not nest"
        }
    }
    return null
}

/**
 * The same target check for effects, every problem at once. **A Look effect names its own target**
 * (absolute-looks plan D2): a fixture, a group or a selection. A `deferred` one, which ran on
 * whatever applied the Look, is refused by name, and so is a target that does not parse —
 * [DaoLookFxEntity.target] and `loadLookSnapshot` would drop it on every read, so the operator would
 * see a 201 and an effect that never exists again.
 *
 * The enum-valued fields are checked here for the same reason, and it is the worse failure of the
 * two: an unrecognised `blendMode` reaches `varchar(50)` intact, reads back as itself, and renders
 * in the UI as the layer's blend — while every spawn warns and plays `OVERRIDE`. The value the
 * operator sees and the value the desk plays then disagree permanently, which is what
 * [FxSpecCoercion.Lenient] exists to *survive*, not what it should have to cover.
 */
internal fun validateLookFx(effects: List<LookFxDto>, registry: FxRegistry): String? {
    val problems = effects.flatMapIndexed { index, effect ->
        listOfNotNull(
            when {
                effect.targetType == DEFERRED_TARGET_TYPE ->
                    "A Look FX must name its own fixture, group or selection. An FX you point at a " +
                        "selection is an FX palette — create one in the palette library instead."
                FxTargetsRef.ofOrNull(effect.targetType, effect.targetKey) == null ->
                    "Unknown FX target '${effect.targetType}' — expected 'fixture', 'group' or " +
                        "'${FxTargetsRef.Selection.TYPE}' (a list of fixtures and groups)"
                else -> null
            },
            "FX type must not be blank".takeIf { effect.fxType.isBlank() },
            FxSpecCoercion.Strict.problem(
                blendMode = effect.blendMode,
                order = effect.order,
                elementMode = effect.elementMode,
                elementFilter = effect.elementFilter,
                direction = effect.direction,
                amount = effect.amount,
                runs = effect.runs,
                spread = effect.spread,
                wings = effect.wings,
                segments = effect.segments,
                parts = effect.parts,
            ),
            registry.fxWriteProblem(effect.fxType, effect.parameters, effect.family),
        ).map { "fx[$index]: $it" }
    }
    return problems.takeIf { it.isNotEmpty() }?.joinToString("; ")
}

/** [pairs] is each incoming item with the existing one it updates, or null; [unmatched] the existing items left over. */
internal class KeyedPairing<E, I>(val pairs: List<Pair<I, E?>>, val unmatched: List<E>)

/**
 * Pairs each of [incoming] with the existing item of the same key. The n-th incoming item of a key
 * takes the n-th existing one of it in [existing]'s order, so a list that holds one key twice pairs
 * deterministically.
 */
internal fun <E, I, K> pairByKey(
    existing: List<E>,
    incoming: List<I>,
    existingKey: (E) -> K,
    incomingKey: (I) -> K,
): KeyedPairing<E, I> {
    val queues = existing.groupByTo(LinkedHashMap(), existingKey) { it }.mapValues { ArrayDeque(it.value) }
    val pairs = incoming.map { item -> item to queues[incomingKey(item)]?.removeFirstOrNull() }
    val claimed = pairs.mapNotNullTo(HashSet()) { it.second }
    return KeyedPairing(pairs, existing.filterNot { it in claimed })
}

/** A Look row's identity within its Look: what it is on, never what it holds. */
internal data class LookRowKey(val targetType: String, val targetKey: String, val elementKey: String?, val attribute: String)

internal fun lookRowKey(targetType: String, targetKey: String, elementKey: String?, attribute: String) =
    LookRowKey(targetType, targetKey, elementKey, canonicalAttributeName(attribute))

internal val DaoLookRow.rowKey get() = lookRowKey(targetType, targetKey, elementKey, attribute)

private val LookRowDto.rowKey get() = lookRowKey(targetType, targetKey, elementKey, attribute)

/** A Look effect's identity within its Look: the effect, and what it runs on. */
internal data class LookFxKey(val targetType: String, val targetKey: String, val attribute: String?, val fxType: String)

internal fun lookFxKey(targetType: String, targetKey: String, attribute: String?, fxType: String) =
    LookFxKey(targetType, targetKey, attribute?.let(::canonicalAttributeName), fxType)

internal val DaoLookFxEntity.fxKey get() = lookFxKey(targetType, targetKey, attribute, fxType)

private val LookFxDto.fxKey get() = lookFxKey(targetType, targetKey, attribute, fxType)

/** A Look's stored rows, in their order. Sorted in memory: Exposed refuses to order a loaded referrer collection. */
internal fun DaoLook.rowsInOrder(): List<DaoLookRow> = rows.sortedWith(compareBy({ it.sortOrder }, { it.id.value }))

internal fun DaoLook.fxInOrder(): List<DaoLookFxEntity> = effects.sortedWith(compareBy({ it.sortOrder }, { it.id.value }))

/**
 * Make a Look's rows [rows] and its effects [effects]; null leaves that list alone. [created] says
 * the Look has no children yet, so there is nothing to read.
 *
 * A row or effect that survives the write is updated in place rather than recreated, so it keeps
 * its `uuid` and a one-value edit reads as one changed row in a sync diff. A row is matched by
 * [LookRowKey], an effect by [LookFxKey] — see [pairByKey] for a key held twice.
 *
 * Must be called inside a transaction.
 */
internal fun writeLookChildren(
    look: DaoLook,
    rows: List<LookRowDto>?,
    effects: List<LookFxDto>?,
    created: Boolean = false,
) {
    // No row and no effect may name a one-shot trigger (stage-view plan session 9, D15). Thrown, so
    // the write's transaction rolls back whole and the route answers 400 with every problem at once.
    // The index is queries; build it only when some name could be refused at all.
    // Nor a fixture command (fixture optics plan session 7), by the same rules and with its own code.
    val newRows = rows.orEmpty()
    val newFx = effects.orEmpty()
    val mayTrigger = newRows.any { TriggerIndex.mayRefuse(it.attribute) } || newFx.any { TriggerIndex.mayRefuse(it.attribute) }
    // A row is also refused for a level inside a command's band on a property that shares its channel.
    val mayCommand = newRows.any { CommandIndex.mayRefuseRow(it.attribute) } || newFx.any { CommandIndex.mayRefuse(it.attribute) }
    if (mayTrigger || mayCommand) {
        val refs = newRows.mapIndexed { i, r -> TriggerIndex.RowRef(r.targetType, r.elementKey?.let { "${r.targetKey}.$it" } ?: r.targetKey, r.attribute, "rows[$i]", r.value) } +
            newFx.mapIndexed { i, e -> TriggerIndex.RowRef(e.targetType, e.targetKey, e.attribute, "fx[$i]") }
        if (mayTrigger) TriggerIndex.of(look.project).check(refs)
        if (mayCommand) CommandIndex.of(look.project).check(refs)
    }
    if (rows != null) {
        val pairing = pairByKey(if (created) emptyList() else look.rowsInOrder(), rows, { it.rowKey }, { it.rowKey })
        pairing.unmatched.forEach { it.delete() }
        for ((row, stored) in pairing.pairs) {
            if (stored != null) {
                stored.take(row)
            } else {
                DaoLookRow.new {
                    this.look = look
                    targetType = row.targetType
                    targetKey = row.targetKey
                    elementKey = row.elementKey
                    take(row)
                }
            }
        }
    }
    if (effects != null) {
        val incoming = inListOrder(effects, { it.sortOrder }) { effect, i -> effect.copy(sortOrder = i) }.map { it.canonicalTarget() }
        val pairing = pairByKey(if (created) emptyList() else look.fxInOrder(), incoming, { it.fxKey }, { it.fxKey })
        pairing.unmatched.forEach { it.delete() }
        for ((effect, stored) in pairing.pairs) {
            if (stored != null) {
                stored.take(effect)
            } else {
                DaoLookFxEntity.new {
                    this.look = look
                    targetType = effect.targetType
                    targetKey = effect.targetKey
                    take(effect)
                }
            }
        }
    }
}

/** [row] onto this row, but for the key fields [writeLookChildren] matched it on. */
private fun DaoLookRow.take(row: LookRowDto) {
    attribute = row.attribute
    value = row.value
    fadeDuration = row.fadeDurationMs.asDuration()
    sortOrder = row.sortOrder
}

private fun DaoLookFxEntity.take(effect: LookFxDto) {
    fxType = effect.fxType
    family = effect.family
    attribute = effect.attribute
    cycle = effect.cycle
    blendMode = effect.blendMode
    order = effect.order
    phaseOffset = effect.phaseOffset
    elementMode = effect.elementMode
    elementFilter = effect.elementFilter
    timing = storedTiming(effect.timing)
    parameters = effect.parameters
    // Seconds follows no master (D7), so a row on Seconds keeps none beside it.
    speedMasterUuid = if (effect.seconds) null else speedMasterUuidOrNull(effect.speedMasterUuid)
    rateSpeedMasterUuid = if (effect.seconds) null else speedMasterUuidOrNull(effect.rateSpeedMasterUuid)
    seconds = effect.seconds
    amount = effect.amount
    direction = FxSpecCoercion.Names.canonicalDirection(effect.direction)
    runs = effect.runs
    this.spread = effect.spread
    this.wings = effect.wings
    this.segments = effect.segments
    this.parts = effect.parts
    sortOrder = effect.sortOrder
}

/**
 * The attribute families a Look touches, derived from its rows.
 *
 * Each row is classified against its own target. A row that resolves nowhere simply contributes no
 * family rather than guessing — the library would rather under-bank a broken Look than file it under
 * the wrong attribute.
 */
private fun DaoLook.derivedFamilies(state: State): List<String> =
    derivedFamilyGroups(state.show.fixtures).map { it.name }

/**
 * Which attribute families this Look touches, in declaration order: its rows' properties resolved
 * against the live patch ([familyForAttribute]) and its effects' declared categories. Read by
 * `toSummaryDto` for the library's family banking and by the Timeline. Must be called inside a
 * transaction.
 */
internal fun DaoLook.derivedFamilyGroups(fixtures: Fixtures): Set<AttributeFamily> {
    val families = LinkedHashSet<AttributeFamily>()

    for (row in rows) {
        val canonical = canonicalAttributeName(row.attribute)
        // An element row is classified against its cell, not its parent: a pixel bar's parent may
        // declare no properties at all, and the family a cell's colour row belongs to is the cell's.
        // The same reading `lookRowInRemit` takes, so recording and banking agree about one row.
        val elementKey = row.elementKey
        val fixture = when (val target = row.target) {
            is TargetRef.Fixture -> runCatching {
                fixtures.untypedGroupableFixture(elementKey ?: target.key)
            }.getOrNull()
            is TargetRef.Group -> runCatching { fixtures.untypedGroup(target.key) }.getOrNull()
                ?.fixtures?.filterIsInstance<uk.me.cormack.lighting7.fixture.Fixture>()?.firstOrNull()
            // A row with no target contributes no family. It cannot happen for data this version
            // wrote — a Look row is always bound — and a row left by an older database has nothing
            // to classify it against now that the editor hint is gone.
            null -> null
        } ?: continue
        familyForAttribute(fixture, canonical)?.let { families.add(it) }
    }

    // Effects declare a category directly, so they need no fixture to classify.
    for (effect in effects) {
        attributeFamilyForFxFamily(effect.family)?.let { families.add(it) }
    }
    return AttributeFamily.entries.filterTo(LinkedHashSet()) { it in families }
}

/**
 * Every Look's scenery as its library row summarises it, by Look id — one query for a list, as
 * [lookUsageFor] batches the counts. A Look with none is absent. Must be called inside a transaction.
 */
internal fun lookScenerySummariesFor(lookIds: Collection<Int>): Map<Int, List<LookScenerySummaryDto>> {
    if (lookIds.isEmpty()) return emptyMap()
    return DaoLookSceneryRow.find { DaoLookScenery.look inList lookIds.toList() }
        .sortedWith(compareBy({ it.sortOrder }, { it.id.value }))
        .groupBy({ it.look.id.value }) { it.toSummaryDto() }
}

private fun DaoLookSceneryRow.toSummaryDto() = LookScenerySummaryDto(
    elementUuid = element.uuid.toString(),
    elementName = element.name,
    state = storedParamsObject(stateJson),
)

/**
 * Must be called inside a transaction. [scenery] is the batched read of [lookScenerySummariesFor];
 * null reads this Look's own.
 */
internal fun DaoLook.toSummaryDto(
    state: State,
    usage: LookUsage?,
    scenery: List<LookScenerySummaryDto>? = null,
): LookDto {
    val rowList = rows.toList()
    val fxList = effects.toList()
    val resolvedUsage = usage ?: lookUsage(id.value)
    return LookDto(
        id = id.value,
        uuid = uuid.toString(),
        name = name,
        notes = notes,
        families = derivedFamilies(state),
        rowCount = rowList.size,
        fxCount = fxList.size,
        targetCount = rowList.map { it.targetKey }.distinct().size,
        hasDeferredFx = false,
        preview = rowList.groupingBy { it.value }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(LOOK_PREVIEW_SIZE)
            .map { it.key },
        fxTypes = fxList.sortedBy { it.sortOrder }.map { it.fxType }.distinct(),
        layerCount = resolvedUsage.layerCount,
        buskPageCount = resolvedUsage.buskPageCount,
        scenery = scenery ?: lookSceneryOf(id).map { it.toSummaryDto() },
    )
}

/** Must be called inside a transaction. */
internal fun DaoLook.toDetailsDto(state: State): LookDetails {
    val usage = lookUsage(id.value)
    return LookDetails(
        id = id.value,
        uuid = uuid.toString(),
        name = name,
        notes = notes,
        families = derivedFamilies(state),
        // Sorted in memory, not via `orderBy`: `derivedFamilies` above already iterates the
        // referrer collection, and Exposed refuses to order a SizedIterable once it is loaded.
        rows = rows
            .sortedWith(compareBy({ it.sortOrder }, { it.targetKey }, { it.attribute }))
            .map {
                LookRowDto(
                    targetType = it.targetType,
                    targetKey = it.targetKey,
                    attribute = it.attribute,
                    value = it.value,
                    fadeDurationMs = it.fadeDuration?.toMillis(),
                    elementKey = it.elementKey,
                    sortOrder = it.sortOrder,
                )
            },
        fx = fxInOrder()
            .map {
                LookFxDto(
                    targetType = it.targetType,
                    targetKey = it.targetKey,
                    fxType = it.fxType,
                    family = it.family,
                    attribute = it.attribute,
                    cycle = it.cycle,
                    blendMode = it.blendMode,
                    order = it.order,
                    phaseOffset = it.phaseOffset,
                    elementMode = it.elementMode,
                    elementFilter = it.elementFilter,
                    timing = timingOf(it.timing),
                    parameters = it.parameters,
                    speedMasterUuid = it.speedMasterUuid?.toString(),
                    rateSpeedMasterUuid = it.rateSpeedMasterUuid?.toString(),
                    seconds = it.seconds,
                    amount = it.amount,
                    direction = it.direction,
                    runs = it.runs,
                    spread = it.spread,
                    wings = it.wings,
                    segments = it.segments,
                    parts = it.parts,
                    sortOrder = it.sortOrder,
                )
            },
        layerCount = usage.layerCount,
        usedByCueIds = usage.cueIds,
        usedByCueNames = usage.cueNames,
        scenery = lookSceneryOf(id).map { it.toDto() },
    )
}

