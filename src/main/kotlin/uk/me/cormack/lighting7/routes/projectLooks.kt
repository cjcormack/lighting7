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
import uk.me.cormack.lighting7.models.LayerSource
import uk.me.cormack.lighting7.fx.EffectSpecCoercion
import uk.me.cormack.lighting7.fx.PropertyMaskGroup
import uk.me.cormack.lighting7.fx.canonicalPropertyName
import uk.me.cormack.lighting7.fx.speedMasterUuidOrNull
import uk.me.cormack.lighting7.fx.maskGroupForProperty
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.DEFERRED_TARGET_TYPE
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoCueLayer
import uk.me.cormack.lighting7.models.DaoCueLayers
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
    // GET /projects/{id}/looks?family=COLOUR
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

    // PUT /projects/{id}/looks/{lookId}
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

    // DELETE /projects/{id}/looks/{lookId}?force=true
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

    // POST /projects/{id}/looks/{lookId}/copy
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

    // POST /projects/{id}/looks/{lookId}/toggle
    //
    // The busking-pad path: put this Look on these targets, or take it off again. Addresses a Look
    // as a *bundle* rather than through a layer, which is what a pad wants — and only its
    // **deferred** rows and effects are offered, because the pad supplies the targets and a bound
    // row would land on the wrong fixtures. A bound Look is recalled through a cue layer instead.
    //
    // Now a **programmer layer**: the pad adds one, or removes the one it added. The request and
    // response shapes are unchanged so the desk keeps working across this rewrite, but two things
    // behind them are different and both are improvements.
    //
    // A layer carries the whole Look, so a **bound** row now lands on the fixture it names instead
    // of being filtered out — the old path could only offer deferred rows, because it had nowhere
    // to put a target set. And the instance is tagged `lookId` + `programmerLayerId` rather than
    // having the Look id smuggled through a preset-id field, which is what used to make
    // `captureCurrentState` reconstruct a preset application naming an unrelated `DaoFxPreset`.
    post<ToggleLookResource> { resource ->
        withCurrentProject(
            state,
            resource.parent.projectId,
            { p -> "Cannot toggle looks in project '${p.name}' - only the current project is live" },
        ) { project ->
            val request = call.receive<ToggleLookRequest>()
            if (request.targets.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("At least one target is required"))
                return@withCurrentProject
            }

            val look = transaction(state.database) {
                DaoLook.findById(resource.lookId)
                    ?.takeIf { it.project.id == project.id }
                    ?.let { Triple(it.id.value, it.uuid, it.name) }
            }
            if (look == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(LOOK_NOT_FOUND))
                return@withCurrentProject
            }

            try {
                val (action, effectCount) = state.show.programmerLayerStack.toggle(
                    source = LayerSource.look(look.first, look.second, look.third),
                    targets = request.targets.map { CueTargetDto(it.type, it.key) },
                    beatDivisionOverride = request.beatDivision,
                )
                call.respond(ToggleLookResponse(action, effectCount))
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

/** Body of `POST /looks/{id}/toggle` — apply the Look as a programmer layer, or remove it. */
@Serializable
internal data class ToggleLookRequest(
    val targets: List<CueTargetDto>,
    val beatDivision: Double? = null,
)

@Serializable
internal data class ToggleLookResponse(
    /** `"applied"` or `"removed"`. */
    val action: String,
    val effectCount: Int,
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
    /**
     * True when any **effect** takes its targets from the layer applying this Look rather than
     * naming one.
     *
     * Rows can no longer be deferred at all — session 3 moved that half of the entity out to
     * templates, and sweep item B6 removed the plumbing that still read the discriminator on the row
     * side — so this is only ever about effects, where a deferred target still means "fan over
     * whatever the layer points at". It stays because a Look whose effects are all deferred asserts
     * nothing without a layer's targets, which is worth being able to see in the library.
     */
    val hasDeferredEffects: Boolean,
    val preview: List<String>,
    /** How many cue layers reference this Look. Gates delete. */
    val layerCount: Int,
)

@Serializable
internal data class LookDetails(
    val id: Int,
    val uuid: String,
    val name: String,
    val notes: String? = null,
    val sortOrder: Int,
    val families: List<String>,
    val rows: List<LookRowDto> = emptyList(),
    val effects: List<LookEffectDto> = emptyList(),
    val layerCount: Int,
    val usedByCueIds: List<Int> = emptyList(),
    val usedByCueNames: List<String> = emptyList(),
)

@Serializable
internal data class CreateLookRequest(
    val name: String,
    val notes: String? = null,
    val sortOrder: Int? = null,
    val rows: List<LookRowDto> = emptyList(),
    val effects: List<LookEffectDto> = emptyList(),
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
    lookUsageFor(listOf(lookId))[lookId] ?: LookUsage(0, emptyList(), emptyList())

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

    return ids.associateWith { lookId ->
        val layers = layersByLook[lookId].orEmpty()
        val cues = layers.map { it.cue }.distinctBy { it.id.value }
        LookUsage(
            layerCount = layers.size,
            cueIds = cues.map { it.id.value }.sorted(),
            cueNames = cues.map { it.name }.sorted(),
        )
    }
}

/**
 * The value prefixes a Look row may never start with.
 *
 * Spelled out here rather than imported, because the module that used to own `ref:` —
 * `fx/PaletteRef.kt` — is gone, and the point of [validateLookRows]'s check is to survive exactly
 * that. `tmpl:` is listed for the same reason ahead of time: `fx/TemplateColourSource.kt` owns that
 * grammar today and this guard must not depend on it still existing tomorrow.
 */
private val LOOK_ROW_REFERENCE_PREFIXES = listOf("ref:", "tmpl:")

/**
 * Reject rows a Look must never hold. Returns the problem, or null when every row is acceptable.
 *
 * The reference rejection is what keeps **Looks from nesting**, so resolution can never recurse, and
 * it **must outlive the grammars it names**. Nothing authors a `ref:` any more — the parser, the
 * resolver and every producer retired in session 4 — and `tmpl:` is legal only in an *effect
 * parameter*, never in a value. But this is a *write boundary*, and the value it guards is free text
 * a client supplies. It is deliberately an inlined shape check rather than a call into a shared
 * parser, so that deleting the last reader of either grammar cannot quietly delete the guarantee
 * `FU-LOOK-NESTED` rests on. `LookRoutesTest` pins it.
 */
internal fun validateLookRows(rows: List<LookRowDto>): String? {
    for (row in rows) {
        // **A Look row is always bound.** The deferred half of this entity became a template in
        // session 3, and a deferred row here would now be a second, weaker way to express one: no
        // family constraint, no intent resolution, and invisible to `/templates`. Refusing it at the
        // write boundary is what makes "a Look names its own fixtures" true rather than customary.
        //
        // A Look *effect* may still be deferred — see `validateLookEffects` — because fanning an
        // effect over the layer's targets is a different thing from holding a value for nobody.
        if (row.targetType == DEFERRED_TARGET_TYPE) {
            return "A look row must name its own fixture or group. A value you point at a selection " +
                "is a template — create one in the template library instead."
        }
        if (TargetRef.ofOrNull(row.targetType, row.targetKey) == null) {
            return "Unknown target type '${row.targetType}' — expected 'fixture' or 'group'"
        }
        if (row.propertyName.isBlank()) return "Row property name must not be blank"
        if (row.value.isBlank()) return "Row value must not be blank"
        if (LOOK_ROW_REFERENCE_PREFIXES.any { row.value.trimStart().startsWith(it, ignoreCase = true) }) {
            return "A look row must hold a literal, not a reference — looks do not nest"
        }
    }
    return null
}

/**
 * The same target check for effects. Rows had it and effects did not, which let a write through with
 * an unresolvable `targetType`: [DaoLookEffect.target] and `loadLookSnapshot` both then drop the
 * effect silently on every read, so the operator sees a 201 and an effect that never exists again.
 *
 * The enum-valued fields are checked here for the same reason, and it is the worse failure of the
 * two: an unrecognised `blendMode` reaches `varchar(50)` intact, reads back as itself, and renders
 * in the UI as the layer's blend — while every spawn warns and plays `OVERRIDE`. The value the
 * operator sees and the value the desk plays then disagree permanently, which is what
 * [EffectSpecCoercion.Lenient] exists to *survive*, not what it should have to cover.
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
        EffectSpecCoercion.Strict.problem(
            blendMode = effect.blendMode,
            distribution = effect.distribution,
            elementMode = effect.elementMode,
            elementFilter = effect.elementFilter,
        )?.let { return it }
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
 * Each row is classified against its own target. A row that resolves nowhere simply contributes no
 * family rather than guessing — the library would rather under-bank a broken Look than file it under
 * the wrong attribute.
 */
private fun DaoLook.derivedFamilies(state: State): List<String> {
    val fixtures = state.show.fixtures
    val families = LinkedHashSet<PropertyMaskGroup>()

    for (row in rows) {
        val canonical = canonicalPropertyName(row.propertyName)
        val fixture = when (val target = row.target) {
            is TargetRef.Fixture -> runCatching { fixtures.untypedFixture(target.key) }.getOrNull()
            is TargetRef.Group -> runCatching { fixtures.untypedGroup(target.key) }.getOrNull()
                ?.fixtures?.filterIsInstance<uk.me.cormack.lighting7.fixture.Fixture>()?.firstOrNull()
            // A row with no target contributes no family. It cannot happen for data this version
            // wrote — a Look row is always bound — and a row left by an older database has nothing
            // to classify it against now that the editor hint is gone.
            null -> null
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
 * `"controls"`, plus `"composite"`), the same set [inferPresetCapabilities] switches on.
 * `"controls"`, `"composite"` and anything unrecognised answer null rather than defaulting into
 * BEAM, so an unknown category under-banks rather than mis-banks.
 *
 * `internal` rather than private because the *template* write boundary derives an effect
 * template's family from the same map (fx-templates D4) — `validateTemplateContents`. Two copies
 * would be two answers to "which column of the busk view does this belong in".
 */
internal fun familyForEffectCategory(category: String): PropertyMaskGroup? = when (category.lowercase()) {
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
        targetCount = rowList.map { it.targetKey }.distinct().size,
        hasDeferredEffects = effectList.any { it.isDeferred },
        preview = rowList.groupingBy { it.value }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(LOOK_PREVIEW_SIZE)
            .map { it.key },
        layerCount = resolvedUsage.layerCount,
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
        usedByCueIds = usage.cueIds,
        usedByCueNames = usage.cueNames,
    )
}

