package uk.me.cormack.lighting7.routes

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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.me.cormack.lighting7.fx.PropertyMaskGroup
import uk.me.cormack.lighting7.fx.canonicalPropertyName
import uk.me.cormack.lighting7.fx.toPaletteColours
import uk.me.cormack.lighting7.fx.speedMasterUuidOrNull
import uk.me.cormack.lighting7.fx.isPaletteRefValue
import uk.me.cormack.lighting7.fx.paletteRefValue
import uk.me.cormack.lighting7.fx.maskGroupForProperty
import uk.me.cormack.lighting7.models.DEFERRED_TARGET_TYPE
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoCueLayer
import uk.me.cormack.lighting7.models.DaoCueLayers
import uk.me.cormack.lighting7.models.DaoCuePropertyAssignment
import uk.me.cormack.lighting7.models.DaoCuePropertyAssignments
import uk.me.cormack.lighting7.models.DaoLook
import uk.me.cormack.lighting7.models.DaoLookEffect
import uk.me.cormack.lighting7.models.DaoLookEffects
import uk.me.cormack.lighting7.models.DaoLookRow
import uk.me.cormack.lighting7.models.DaoLookRows
import uk.me.cormack.lighting7.models.DaoLooks
import uk.me.cormack.lighting7.models.LookEffectDto
import uk.me.cormack.lighting7.models.LookRowDto
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.state.State
import java.util.UUID

/** Error code the client keys the "flatten these into local rows, then delete" recovery flow off. */
internal const val CODE_LOOK_IN_USE = "LOOK_IN_USE"

/** How many distinct literals a summary's preview carries. Enough to recognise, short enough to send. */
private const val LOOK_PREVIEW_SIZE = 8

private const val LOOK_NOT_FOUND = "Look not found"

internal fun Route.routeApiRestProjectLooks(state: State) {
    // GET /project/{id}/looks?family=COLOUR
    get<ProjectLooksResource> { resource ->
        withProject(state, resource.projectId) { project ->
            val familyFilter = resource.family?.trim()?.takeIf { it.isNotEmpty() }
            val parsedFamily = familyFilter?.let { raw ->
                PropertyMaskGroup.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
            }
            if (familyFilter != null && parsedFamily == null) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(
                        "Unknown family '$familyFilter' — expected one of " +
                            PropertyMaskGroup.entries.joinToString { it.name }
                    ),
                )
                return@withProject
            }
            val looks = transaction(state.database) {
                val all = DaoLook.find { DaoLooks.project eq project.id }
                    .orderBy(DaoLooks.sortOrder to SortOrder.ASC, DaoLooks.name to SortOrder.ASC)
                    .toList()
                val usage = lookUsageFor(all.map { it.id.value })
                all.map { it.toSummaryDto(state, usage[it.id.value]) }
            }
            // Banked by *derived* family, so the filter is applied after the summaries are built —
            // there is no stored column to query on, by design (§3.1).
            call.respond(
                if (parsedFamily == null) looks else looks.filter { parsedFamily.name in it.families }
            )
        }
    }

    // GET /project/{id}/looks/{lookId}
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

    // POST /project/{id}/looks
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
            validateLookEffects(request.effects)?.let { problem ->
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
                    this.sortOrder = request.sortOrder
                        ?: ((DaoLook.find { DaoLooks.project eq project.id }
                            .maxOfOrNull { it.sortOrder } ?: -1) + 1)
                    this.editorFixtureType = request.editorFixtureType
                    this.palette = request.palette
                }
                createLookChildren(look, request.rows, request.effects)
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

    // PUT /project/{id}/looks/{lookId}
    //
    // Received as a raw JsonObject so absent and explicitly-null are distinguishable: omitting
    // `rows` leaves the contents alone (metadata-only edit), while sending `[]` empties them.
    put<ProjectLookResource> { resource ->
        withCurrentProject(state, resource.parent.projectId) { project ->
            val body = call.receive<JsonObject>()
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
                body["sortOrder"].nullableInt()?.let { look.sortOrder = it }
                if ("editorFixtureType" in body) {
                    look.editorFixtureType = body["editorFixtureType"].nullableString()
                }
                if ("palette" in body) {
                    look.palette = lookJson.decodeFromJsonElement<List<String>>(body["palette"]!!)
                }

                val hasRows = "rows" in body
                val hasEffects = "effects" in body
                val rows = if (hasRows) {
                    lookJson.decodeFromJsonElement<List<LookRowDto>>(body["rows"]!!)
                } else emptyList()
                if (hasRows) {
                    validateLookRows(rows)?.let { return@transaction LookWriteOutcome.Invalid(it) }
                }
                val effects = if (hasEffects) {
                    lookJson.decodeFromJsonElement<List<LookEffectDto>>(body["effects"]!!)
                } else emptyList()
                if (hasEffects) {
                    validateLookEffects(effects)?.let { return@transaction LookWriteOutcome.Invalid(it) }
                }

                if (hasRows) look.rows.forEach { it.delete() }
                if (hasEffects) look.effects.forEach { it.delete() }
                if (hasRows || hasEffects) createLookChildren(look, rows, effects)

                LookWriteOutcome.Written(look.toDetailsDto(state), look.uuid, hasRows || hasEffects)
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

    // DELETE /project/{id}/looks/{lookId}?force=true
    delete<ProjectLookResource> { resource ->
        withCurrentProject(state, resource.parent.projectId) { project ->
            val outcome = transaction(state.database) {
                val look = DaoLook.findById(resource.lookId) ?: return@transaction LookDeleteOutcome.NotFound
                if (look.project.id != project.id) return@transaction LookDeleteOutcome.NotFound
                val usage = lookUsage(look.id.value)
                if (usage.total > 0 && !resource.force) {
                    return@transaction LookDeleteOutcome.InUse(usage)
                }
                val uuid = look.uuid
                // No DB-level ON DELETE CASCADE — SQLite doesn't enforce cascades without a
                // per-connection pragma, so children go first, explicitly.
                if (resource.force) look.let { l -> DaoCueLayer.find { DaoCueLayers.look eq l.id }.forEach { it.delete() } }
                look.rows.forEach { it.delete() }
                look.effects.forEach { it.delete() }
                look.delete()
                LookDeleteOutcome.Deleted(uuid)
            }
            when (outcome) {
                is LookDeleteOutcome.NotFound ->
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(LOOK_NOT_FOUND))
                is LookDeleteOutcome.InUse -> call.respond(
                    HttpStatusCode.Conflict,
                    LookInUseResponse(
                        error = "This look is still used by ${outcome.usage.describe()}",
                        code = CODE_LOOK_IN_USE,
                        layerCount = outcome.usage.layerCount,
                        refRowCount = outcome.usage.refRowCount,
                        cueIds = outcome.usage.cueIds,
                        cueNames = outcome.usage.cueNames,
                    ),
                )
                is LookDeleteOutcome.Deleted -> {
                    // Keep the operator's include indicator honest — the same reason
                    // `clearIncludeTargetForCue` is called on a cue delete.
                    state.show.programmerStore.clearIncludeTargetForLook(resource.lookId)
                    state.show.fixtures.lookListChanged()
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }
    }

    // POST /project/{id}/looks/{lookId}/copy
    post<CopyLookResource> { resource ->
        withProject(state, resource.parent.projectId) { project ->
            val request = call.receive<CopyLookRequest>()
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
                    this.sortOrder = (DaoLook.find { DaoLooks.project eq target.id }
                        .maxOfOrNull { it.sortOrder } ?: -1) + 1
                    this.editorFixtureType = source.editorFixtureType
                    this.palette = source.palette
                }
                // Fresh uuids on every child: a copy is a new entity, and reusing the source's
                // uuid would make sync treat the two as one record.
                for (row in source.rows.sortedBy { it.sortOrder }) {
                    DaoLookRow.new {
                        look = copy
                        targetType = row.targetType
                        targetKey = row.targetKey
                        propertyName = row.propertyName
                        value = row.value
                        fadeDurationMs = row.fadeDurationMs
                        elementKey = row.elementKey
                        sortOrder = row.sortOrder
                    }
                }
                for (effect in source.effects.sortedBy { it.sortOrder }) {
                    DaoLookEffect.new {
                        look = copy
                        targetType = effect.targetType
                        targetKey = effect.targetKey
                        effectType = effect.effectType
                        category = effect.category
                        propertyName = effect.propertyName
                        beatDivision = effect.beatDivision
                        blendMode = effect.blendMode
                        distribution = effect.distribution
                        phaseOffset = effect.phaseOffset
                        elementMode = effect.elementMode
                        elementFilter = effect.elementFilter
                        stepTiming = effect.stepTiming
                        parameters = effect.parameters
                        speedMasterUuid = effect.speedMasterUuid
                        rateSpeedMasterUuid = effect.rateSpeedMasterUuid
                        sortOrder = effect.sortOrder
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

    // POST /project/{id}/looks/{lookId}/toggle
    //
    // The busking-pad path: put this Look on these targets, or take it off again. Addresses a Look
    // as a *bundle* rather than through a layer, which is what a pad wants — and only its
    // **deferred** rows and effects are offered, because the pad supplies the targets and a bound
    // row would land on the wrong fixtures. A bound Look is recalled through a cue layer instead.
    //
    // Note this stamps `FxInstance.presetId = lookId` (via `togglePresetOnTargets`, which keys its
    // toggle bookkeeping on that field), exactly as the AI's `apply_look` already does. Harmless
    // while nothing composes from preset applications, but it means `captureCurrentState` would
    // reconstruct a `CuePresetApplicationDto` naming whatever `DaoFxPreset` shares the number. It
    // goes when the pads become programmer layers.
    post<ToggleLookResource> { resource ->
        withProject(state, resource.parent.projectId) { project ->
            val request = call.receive<TogglePresetRequest>()
            if (request.targets.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("At least one target is required"))
                return@withProject
            }

            val lookData = transaction(state.database) {
                val look = DaoLook.findById(resource.lookId) ?: return@transaction null
                if (look.project.id != project.id) return@transaction null
                loadLookToggleData(resource.lookId)
            }
            if (lookData == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(LOOK_NOT_FOUND))
                return@withProject
            }

            try {
                call.respond(
                    togglePresetOnTargets(
                        state,
                        resource.lookId,
                        lookData.effects,
                        lookData.propertyAssignments,
                        request.targets,
                        request.beatDivision,
                        presetPalette = lookData.palette,
                    )
                )
            } catch (e: IllegalStateException) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(e.message ?: "Target not found"))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Failed to toggle look"))
            }
        }
    }

    // POST /project/{id}/looks/preview — the Look editor's "live preview" toggle.
    //
    // Whole-desired-state, so it needs no stored Look at all and delegates straight to the preview
    // slot the preset editor used: the request carries the rows, the colour list and the targets,
    // and an empty one collapses to a clear. That is why this is route plumbing and not a second
    // implementation.
    post<LookPreviewResource> { resource ->
        withCurrentProject(
            state,
            resource.parent.projectId,
            { p -> "Cannot preview looks in project '${p.name}' - only the current project can be previewed" },
        ) {
            val request = call.receive<PresetPreviewRequest>()
            call.respond(applyPresetPreview(state, resource.parent.projectId, request))
        }
    }

    // DELETE /project/{id}/looks/preview
    delete<LookPreviewResource> { resource ->
        withCurrentProject(
            state,
            resource.parent.projectId,
            { p -> "Cannot clear preview in project '${p.name}' - only the current project can be previewed" },
        ) {
            clearPresetPreview(state, resource.parent.projectId)
            call.respond(HttpStatusCode.OK)
        }
    }
}

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

/**
 * Unambiguous against [ProjectLookResource] despite sitting at the same depth: nothing serves
 * `POST /{lookId}`, and `lookId` is an `Int` that `"preview"` cannot parse as.
 */
@Resource("/preview")
internal data class LookPreviewResource(val parent: ProjectLooksResource)

// ─── DTOs ───────────────────────────────────────────────────────────────

@Serializable
internal data class LookDto(
    val id: Int,
    val uuid: String,
    val name: String,
    val notes: String? = null,
    val sortOrder: Int,
    /**
     * Attribute families this Look touches, **derived** from its rows rather than stored. A Look
     * spanning colour and position reports both — which is the point of not having a type column.
     */
    val families: List<String>,
    val rowCount: Int,
    val effectCount: Int,
    val targetCount: Int,
    /** True when any row or effect is deferred, i.e. takes its targets from the layer. */
    val hasDeferredRows: Boolean,
    val editorFixtureType: String? = null,
    val preview: List<String>,
    /** How many cue layers reference this Look. Gates delete together with [refRowCount]. */
    val layerCount: Int,
    /**
     * How many cue rows still hold a `ref:{uuid}` naming this Look. Counted separately because it
     * is a different reference mechanism on its way out, but it gates delete just the same.
     */
    val refRowCount: Int = 0,
)

@Serializable
internal data class LookDetails(
    val id: Int,
    val uuid: String,
    val name: String,
    val notes: String? = null,
    val sortOrder: Int,
    val families: List<String>,
    val editorFixtureType: String? = null,
    val palette: List<String> = emptyList(),
    val rows: List<LookRowDto> = emptyList(),
    val effects: List<LookEffectDto> = emptyList(),
    val layerCount: Int,
    val refRowCount: Int = 0,
    val usedByCueIds: List<Int> = emptyList(),
    val usedByCueNames: List<String> = emptyList(),
)

@Serializable
internal data class CreateLookRequest(
    val name: String,
    val notes: String? = null,
    val sortOrder: Int? = null,
    val editorFixtureType: String? = null,
    val palette: List<String> = emptyList(),
    val rows: List<LookRowDto> = emptyList(),
    val effects: List<LookEffectDto> = emptyList(),
)

@Serializable
internal data class LookInUseResponse(
    val error: String,
    val code: String,
    val layerCount: Int,
    val refRowCount: Int = 0,
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

private sealed interface LookDeleteOutcome {
    data object NotFound : LookDeleteOutcome
    data class InUse(val usage: LookUsage) : LookDeleteOutcome
    data class Deleted(val uuid: UUID) : LookDeleteOutcome
}

private sealed interface CopyLookOutcome {
    data class NameTaken(val name: String) : CopyLookOutcome
    data class Copied(val response: CopyLookResponse) : CopyLookOutcome
}

// ─── Usage, validation and mapping ──────────────────────────────────────

private val lookJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

/**
 * How many cue layers reference a Look, and which cues they belong to.
 *
 * A layer references its Look through a real FK column, so that half is a plain indexed query
 * rather than the palette era's exact-equality scan over opaque `value` text. The scan survives for
 * [refRowCount] only: until the `ref:` grammar is retired, a cue row may still name a Look by uuid,
 * and `republishForLookEdit`'s [activeCuesReferencingLook] counts those. The delete guard has to see
 * the same references the republish does, or a Look reported as "used by nothing" is deleted out
 * from under a cue that still resolves through it.
 */
internal data class LookUsage(
    val layerCount: Int,
    val refRowCount: Int,
    val cueIds: List<Int>,
    val cueNames: List<String>,
) {
    val total: Int get() = layerCount + refRowCount

    fun describe(): String {
        val parts = buildList {
            if (layerCount > 0) add("$layerCount cue layer(s)")
            if (refRowCount > 0) add("$refRowCount cue row reference(s)")
        }
        if (parts.isEmpty()) return "nothing"
        val what = parts.joinToString(" and ")
        return if (cueNames.isEmpty()) what else "$what in ${cueNames.joinToString(", ")}"
    }
}

/** Must be called inside a transaction. */
internal fun lookUsage(lookId: Int): LookUsage =
    lookUsageFor(listOf(lookId))[lookId] ?: LookUsage(0, 0, emptyList(), emptyList())

/** Batched form for a list response — one query instead of one per Look. */
internal fun lookUsageFor(lookIds: Collection<Int>): Map<Int, LookUsage> {
    if (lookIds.isEmpty()) return emptyMap()
    val ids = lookIds.toList()
    val looks = DaoLook.find { DaoLooks.id inList ids }.associateBy { it.id.value }
    val layersByLook = DaoCueLayer.find { DaoCueLayers.look inList ids }
        .toList()
        .groupBy { it.look.id.value }

    // The `ref:{uuid}` half. Batched by building the value set once rather than one query per Look.
    val refValueToLook = looks.values.associate { paletteRefValue(it.uuid) to it.id.value }
    val refCuesByLook = HashMap<Int, MutableList<DaoCue>>()
    if (refValueToLook.isNotEmpty()) {
        DaoCuePropertyAssignment
            .find { DaoCuePropertyAssignments.value inList refValueToLook.keys.toList() }
            .forEach { row ->
                refValueToLook[row.value]?.let { refCuesByLook.getOrPut(it) { mutableListOf() }.add(row.cue) }
            }
    }

    return ids.associateWith { lookId ->
        val layers = layersByLook[lookId].orEmpty()
        val refRows = refCuesByLook[lookId].orEmpty()
        val cues = (layers.map { it.cue } + refRows).distinctBy { it.id.value }
        LookUsage(
            layerCount = layers.size,
            refRowCount = refRows.size,
            cueIds = cues.map { it.id.value }.sorted(),
            cueNames = cues.map { it.name }.sorted(),
        )
    }
}

/**
 * Reject rows a Look must never hold. Returns the problem, or null when every row is acceptable.
 *
 * The `ref:` rejection is what keeps **Looks from nesting**, so resolving a reference can never
 * recurse — the same write-boundary guarantee `validatePaletteEntries` gave palettes.
 */
internal fun validateLookRows(rows: List<LookRowDto>): String? {
    for (row in rows) {
        if (row.targetType != DEFERRED_TARGET_TYPE && TargetRef.ofOrNull(row.targetType, row.targetKey) == null) {
            return "Unknown target type '${row.targetType}' — expected 'fixture', 'group' or '$DEFERRED_TARGET_TYPE'"
        }
        if (row.propertyName.isBlank()) return "Row property name must not be blank"
        if (row.value.isBlank()) return "Row value must not be blank"
        if (isPaletteRefValue(row.value)) {
            return "A look row must hold a literal, not a reference — looks do not nest"
        }
    }
    return null
}

/**
 * The same target check for effects. Rows had it and effects did not, which let a write through with
 * an unresolvable `targetType`: [DaoLookEffect.target] and `loadLookSnapshot` both then drop the
 * effect silently on every read, so the operator sees a 201 and an effect that never exists again.
 */
internal fun validateLookEffects(effects: List<LookEffectDto>): String? {
    for (effect in effects) {
        if (effect.targetType != DEFERRED_TARGET_TYPE &&
            TargetRef.ofOrNull(effect.targetType, effect.targetKey) == null
        ) {
            return "Unknown effect target type '${effect.targetType}' — " +
                "expected 'fixture', 'group' or '$DEFERRED_TARGET_TYPE'"
        }
        if (effect.effectType.isBlank()) return "Effect type must not be blank"
    }
    return null
}

/** Must be called inside a transaction. */
private fun createLookChildren(
    look: DaoLook,
    rows: List<LookRowDto>,
    effects: List<LookEffectDto>,
) {
    for (row in rows) {
        DaoLookRow.new {
            this.look = look
            targetType = row.targetType
            targetKey = row.targetKey
            propertyName = row.propertyName
            value = row.value
            fadeDurationMs = row.fadeDurationMs
            elementKey = row.elementKey
            sortOrder = row.sortOrder
        }
    }
    for (effect in effects) {
        DaoLookEffect.new {
            this.look = look
            targetType = effect.targetType
            targetKey = effect.targetKey
            effectType = effect.effectType
            category = effect.category
            propertyName = effect.propertyName
            beatDivision = effect.beatDivision
            blendMode = effect.blendMode
            distribution = effect.distribution
            phaseOffset = effect.phaseOffset
            elementMode = effect.elementMode
            elementFilter = effect.elementFilter
            stepTiming = effect.stepTiming
            parameters = effect.parameters
            speedMasterUuid = speedMasterUuidOrNull(effect.speedMasterUuid)
            rateSpeedMasterUuid = speedMasterUuidOrNull(effect.rateSpeedMasterUuid)
            sortOrder = effect.sortOrder
        }
    }
}

/**
 * The attribute families a Look touches, derived from its rows.
 *
 * A bound row is classified against its own target; a deferred row has no fixture to ask, so it
 * falls back to the editor hint's synthetic fixture where one exists. A row that resolves nowhere
 * simply contributes no family rather than guessing — the library would rather under-bank a broken
 * Look than file it under the wrong attribute.
 */
private fun DaoLook.derivedFamilies(state: State): List<String> {
    val fixtures = state.show.fixtures
    val families = LinkedHashSet<PropertyMaskGroup>()

    // A deferred row names no fixture, so it is classified against any *patched* fixture of the
    // editor type. That is a real fixture rather than a synthetic one — the backend has no
    // synthetic-fixture builder, only the editor does — so a Look whose declared type isn't
    // patched anywhere contributes no family rather than guessing.
    val deferredReference by lazy(LazyThreadSafetyMode.NONE) {
        editorFixtureType?.let { type -> fixtures.fixtures.firstOrNull { it.typeKey == type } }
    }

    for (row in rows) {
        val canonical = canonicalPropertyName(row.propertyName)
        val fixture = when (val target = row.target) {
            is TargetRef.Fixture -> runCatching { fixtures.untypedFixture(target.key) }.getOrNull()
            is TargetRef.Group -> runCatching { fixtures.untypedGroup(target.key) }.getOrNull()
                ?.fixtures?.filterIsInstance<uk.me.cormack.lighting7.fixture.Fixture>()?.firstOrNull()
            null -> deferredReference
        } ?: continue
        maskGroupForProperty(fixture, canonical)?.let { families.add(it) }
    }

    // Effects declare a category directly, so they need no fixture to classify.
    for (effect in effects) {
        familyForEffectCategory(effect.category)?.let { families.add(it) }
    }
    return PropertyMaskGroup.entries.filter { it in families }.map { it.name }
}

/**
 * The attribute family an effect's declared [category] belongs to.
 *
 * The category vocabulary is the effect library's own (`"dimmer"` / `"colour"` / `"position"` /
 * `"controls"`), the same set [inferPresetCapabilities] switches on. `"controls"` and anything
 * unrecognised answer null rather than defaulting into BEAM, so an unknown category under-banks
 * rather than mis-banks.
 */
private fun familyForEffectCategory(category: String): PropertyMaskGroup? = when (category.lowercase()) {
    "dimmer" -> PropertyMaskGroup.INTENSITY
    "colour", "color" -> PropertyMaskGroup.COLOUR
    "position" -> PropertyMaskGroup.POSITION
    "beam" -> PropertyMaskGroup.BEAM
    else -> null
}

/** Must be called inside a transaction. */
private fun DaoLook.toSummaryDto(state: State, usage: LookUsage?): LookDto {
    val rowList = rows.toList()
    val effectList = effects.toList()
    val resolvedUsage = usage ?: lookUsage(id.value)
    return LookDto(
        id = id.value,
        uuid = uuid.toString(),
        name = name,
        notes = notes,
        sortOrder = sortOrder,
        families = derivedFamilies(state),
        rowCount = rowList.size,
        effectCount = effectList.size,
        targetCount = rowList.filterNot { it.isDeferred }.map { it.targetKey }.distinct().size,
        hasDeferredRows = rowList.any { it.isDeferred } || effectList.any { it.isDeferred },
        editorFixtureType = editorFixtureType,
        preview = rowList.groupingBy { it.value }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(LOOK_PREVIEW_SIZE)
            .map { it.key },
        layerCount = resolvedUsage.layerCount,
        refRowCount = resolvedUsage.refRowCount,
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
        sortOrder = sortOrder,
        families = derivedFamilies(state),
        editorFixtureType = editorFixtureType,
        palette = palette,
        // Sorted in memory, not via `orderBy`: `derivedFamilies` above already iterates the
        // referrer collection, and Exposed refuses to order a SizedIterable once it is loaded.
        rows = rows
            .sortedWith(compareBy({ it.sortOrder }, { it.targetKey }, { it.propertyName }))
            .map {
                LookRowDto(
                    targetType = it.targetType,
                    targetKey = it.targetKey,
                    propertyName = it.propertyName,
                    value = it.value,
                    fadeDurationMs = it.fadeDurationMs,
                    elementKey = it.elementKey,
                    sortOrder = it.sortOrder,
                )
            },
        effects = effects
            .sortedBy { it.sortOrder }
            .map {
                LookEffectDto(
                    targetType = it.targetType,
                    targetKey = it.targetKey,
                    effectType = it.effectType,
                    category = it.category,
                    propertyName = it.propertyName,
                    beatDivision = it.beatDivision,
                    blendMode = it.blendMode,
                    distribution = it.distribution,
                    phaseOffset = it.phaseOffset,
                    elementMode = it.elementMode,
                    elementFilter = it.elementFilter,
                    stepTiming = it.stepTiming,
                    parameters = it.parameters,
                    speedMasterUuid = it.speedMasterUuid?.toString(),
                    rateSpeedMasterUuid = it.rateSpeedMasterUuid?.toString(),
                    sortOrder = it.sortOrder,
                )
            },
        layerCount = usage.layerCount,
        refRowCount = usage.refRowCount,
        usedByCueIds = usage.cueIds,
        usedByCueNames = usage.cueNames,
    )
}

/**
 * A Look's contents in the shape the toggle / preview surfaces consume.
 *
 * Those surfaces predate layers and address a bundle directly ("put this on these targets"), which
 * is still what a busking pad wants. Rather than duplicate their logic, this adapts a Look into the
 * generic effect-spec and property-assignment shapes `togglePresetOnTargets` already takes.
 *
 * Only **deferred** rows and effects are offered: the toggle surface supplies the targets, so a
 * bound row — which names its own — would be applied to the wrong fixtures. A bound Look is
 * therefore recalled through a cue layer or the programmer, not through toggle.
 */
internal data class LookToggleData(
    val effects: List<uk.me.cormack.lighting7.models.FxPresetEffectDto>,
    val propertyAssignments: List<uk.me.cormack.lighting7.models.FxPresetPropertyAssignmentDto>,
    val palette: List<uk.me.cormack.lighting7.fx.ExtendedColour>,
)

/** Must be called inside a transaction. Null when no such Look exists. */
internal fun loadLookToggleData(lookId: Int): LookToggleData? {
    val look = DaoLook.findById(lookId) ?: return null
    return LookToggleData(
        effects = look.effects
            .sortedBy { it.sortOrder }
            .filter { it.isDeferred }
            .map { e ->
                uk.me.cormack.lighting7.models.FxPresetEffectDto(
                    effectType = e.effectType,
                    category = e.category,
                    propertyName = e.propertyName,
                    beatDivision = e.beatDivision,
                    blendMode = e.blendMode,
                    distribution = e.distribution,
                    phaseOffset = e.phaseOffset,
                    elementMode = e.elementMode,
                    elementFilter = e.elementFilter,
                    stepTiming = e.stepTiming,
                    parameters = e.parameters,
                    speedMasterUuid = e.speedMasterUuid?.toString(),
                    rateSpeedMasterUuid = e.rateSpeedMasterUuid?.toString(),
                )
            },
        propertyAssignments = look.rows
            .sortedBy { it.sortOrder }
            .filter { it.isDeferred }
            .map { r ->
                uk.me.cormack.lighting7.models.FxPresetPropertyAssignmentDto(
                    propertyName = r.propertyName,
                    value = r.value,
                    fadeDurationMs = r.fadeDurationMs,
                    sortOrder = r.sortOrder,
                    elementKey = r.elementKey,
                )
            },
        palette = look.palette.toPaletteColours(),
    )
}
