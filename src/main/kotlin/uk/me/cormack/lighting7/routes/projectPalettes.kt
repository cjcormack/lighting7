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
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.me.cormack.lighting7.fx.PALETTE_REF_PREFIX
import uk.me.cormack.lighting7.fx.isPaletteRefValue
import uk.me.cormack.lighting7.fx.paletteRefValue
import uk.me.cormack.lighting7.models.DaoCuePropertyAssignment
import uk.me.cormack.lighting7.models.DaoCuePropertyAssignments
import uk.me.cormack.lighting7.models.DaoFxPresetPropertyAssignment
import uk.me.cormack.lighting7.models.DaoFxPresetPropertyAssignments
import uk.me.cormack.lighting7.models.DaoPalette
import uk.me.cormack.lighting7.models.DaoPaletteEntries
import uk.me.cormack.lighting7.models.DaoPaletteEntry
import uk.me.cormack.lighting7.models.DaoPalettes
import uk.me.cormack.lighting7.models.PaletteEntryDto
import uk.me.cormack.lighting7.models.PaletteType
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.state.State
import java.util.UUID

/** Error code the client keys the "make these hard, then delete" recovery flow off. */
internal const val CODE_PALETTE_IN_USE = "PALETTE_IN_USE"

/** How many distinct literals a summary's preview carries. Enough to recognise, short enough to send. */
private const val PALETTE_PREVIEW_SIZE = 8

internal fun Route.routeApiRestProjectPalettes(state: State) {
    get<ProjectPalettesResource> { resource ->
        withProject(state, resource.projectId) { project ->
            val typeFilter = resource.type?.trim()?.takeIf { it.isNotEmpty() }
            val parsedType = typeFilter?.let { raw ->
                PaletteType.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
            }
            if (typeFilter != null && parsedType == null) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(
                        "Unknown palette type '$typeFilter' — expected one of " +
                            PaletteType.entries.joinToString { it.name }
                    ),
                )
                return@withProject
            }

            val palettes = transaction(state.database) {
                val rows = DaoPalette.find { DaoPalettes.project eq project.id }
                    .orderBy(
                        DaoPalettes.type to SortOrder.ASC,
                        DaoPalettes.sortOrder to SortOrder.ASC,
                        DaoPalettes.name to SortOrder.ASC,
                    )
                    .filter { parsedType == null || it.type == parsedType.name }
                val usage = paletteUsageFor(rows.map { it.uuid })
                rows.map { it.toSummaryDto(usage[it.uuid]) }
            }
            call.respond(palettes)
        }
    }

    get<ProjectPaletteResource> { resource ->
        withProject(state, resource.parent.projectId) { project ->
            val dto = transaction(state.database) {
                val palette = DaoPalette.findById(resource.paletteId) ?: return@transaction null
                if (palette.project.id != project.id) return@transaction null
                palette.toDetailsDto()
            }
            if (dto == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Palette not found"))
            } else {
                call.respond(dto)
            }
        }
    }

    post<ProjectPalettesResource> { resource ->
        withProject(state, resource.projectId) { project ->
            val request = call.receive<CreatePaletteRequest>()

            val type = PaletteType.entries.firstOrNull { it.name.equals(request.type.trim(), ignoreCase = true) }
            if (type == null) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(
                        "Unknown palette type '${request.type}' — expected one of " +
                            PaletteType.entries.joinToString { it.name }
                    ),
                )
                return@withProject
            }
            val trimmedName = request.name.trim()
            if (trimmedName.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Palette name must not be blank"))
                return@withProject
            }
            validatePaletteEntries(request.entries)?.let { error ->
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(error))
                return@withProject
            }

            val result = transaction(state.database) {
                val existing = DaoPalette.find {
                    (DaoPalettes.project eq project.id) and
                        (DaoPalettes.type eq type.name) and
                        (DaoPalettes.name eq trimmedName)
                }.firstOrNull()
                if (existing != null) {
                    return@transaction Pair<PaletteDetails?, String?>(
                        null,
                        "A ${type.name} palette called '$trimmedName' already exists",
                    )
                }
                val maxSortOrder = DaoPalette.find {
                    (DaoPalettes.project eq project.id) and (DaoPalettes.type eq type.name)
                }.maxOfOrNull { it.sortOrder } ?: -1

                val palette = DaoPalette.new {
                    this.project = project
                    this.name = trimmedName
                    this.type = type.name
                    this.notes = request.notes?.trim()?.takeIf { it.isNotEmpty() }
                    this.sortOrder = request.sortOrder ?: (maxSortOrder + 1)
                }
                request.entries.forEachIndexed { index, entry ->
                    DaoPaletteEntry.new {
                        this.palette = palette
                        this.targetType = entry.targetType
                        this.targetKey = entry.targetKey
                        this.propertyName = entry.propertyName
                        this.value = entry.value
                        this.sortOrder = if (entry.sortOrder != 0) entry.sortOrder else index
                    }
                }
                Pair<PaletteDetails?, String?>(palette.toDetailsDto(), null)
            }
            val (dto, error) = result
            if (error != null) {
                call.respond(HttpStatusCode.Conflict, ErrorResponse(error))
                return@withProject
            }
            state.show.fixtures.paletteListChanged()
            call.respond(HttpStatusCode.Created, dto!!)
        }
    }

    /**
     * Metadata only — name, notes, sortOrder. A palette's *contents* are written by
     * `POST /api/rest/programmer/record-palette`, which is the console loop: record from the
     * programmer, or Include → edit → Update. There is deliberately no per-entry write route.
     */
    put<ProjectPaletteResource> { resource ->
        withProject(state, resource.parent.projectId) { project ->
            val body = call.receive<JsonObject>()

            val result = transaction(state.database) {
                val palette = DaoPalette.findById(resource.paletteId)
                    ?: return@transaction Pair<PaletteDetails?, String?>(null, PALETTE_NOT_FOUND)
                if (palette.project.id != project.id) {
                    return@transaction Pair<PaletteDetails?, String?>(null, PALETTE_NOT_FOUND)
                }

                body["name"].nullableString()?.let { newName ->
                    val trimmed = newName.trim()
                    if (trimmed.isEmpty()) {
                        return@transaction Pair<PaletteDetails?, String?>(null, "Palette name must not be blank")
                    }
                    val collision = DaoPalette.find {
                        (DaoPalettes.project eq project.id) and
                            (DaoPalettes.type eq palette.type) and
                            (DaoPalettes.name eq trimmed)
                    }.firstOrNull()
                    if (collision != null && collision.id != palette.id) {
                        return@transaction Pair<PaletteDetails?, String?>(
                            null,
                            "A ${palette.type} palette called '$trimmed' already exists",
                        )
                    }
                    palette.name = trimmed
                }
                if ("notes" in body) {
                    palette.notes = body["notes"].nullableString()?.trim()?.takeIf { it.isNotEmpty() }
                }
                body["sortOrder"].nullableInt()?.let { palette.sortOrder = it }

                Pair<PaletteDetails?, String?>(palette.toDetailsDto(), null)
            }
            val (dto, error) = result
            if (error != null) {
                val code = if (error == PALETTE_NOT_FOUND) HttpStatusCode.NotFound else HttpStatusCode.Conflict
                call.respond(code, ErrorResponse(error))
                return@withProject
            }
            // Renaming changes what the sheet's ref badges read, but not what anything resolves
            // to, so no republish here — only the list broadcast.
            state.show.fixtures.paletteListChanged()
            call.respond(dto!!)
        }
    }

    /**
     * Refuses while persisted rows still reference the palette, unless `?force=true`.
     *
     * Cascading would turn every referencing cue row into a dead reference that only surfaces
     * when the cue next fires. The 409 carries the usage so the client can offer "make those
     * hard, then delete". Programmer entries deliberately do **not** block: they are transient
     * live state that Clear disposes of, not authored show content.
     */
    delete<ProjectPaletteResource> { resource ->
        withProject(state, resource.parent.projectId) { project ->
            val outcome = transaction(state.database) {
                val palette = DaoPalette.findById(resource.paletteId)
                    ?: return@transaction PaletteDeleteOutcome.NotFound
                if (palette.project.id != project.id) return@transaction PaletteDeleteOutcome.NotFound

                val usage = paletteUsage(palette.uuid)
                if (usage.total > 0 && !resource.force) {
                    return@transaction PaletteDeleteOutcome.InUse(usage)
                }
                val uuid = palette.uuid
                palette.entries.forEach { it.delete() }
                palette.delete()
                PaletteDeleteOutcome.Deleted(uuid)
            }

            when (outcome) {
                PaletteDeleteOutcome.NotFound ->
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Palette not found"))

                is PaletteDeleteOutcome.InUse -> call.respond(
                    HttpStatusCode.Conflict,
                    PaletteInUseResponse(
                        error = "Palette is referenced by ${outcome.usage.describe()}",
                        code = CODE_PALETTE_IN_USE,
                        referenceCount = outcome.usage.total,
                        cueAssignmentCount = outcome.usage.cueAssignments,
                        presetAssignmentCount = outcome.usage.presetAssignments,
                        cueIds = outcome.usage.cueIds,
                    ),
                )

                is PaletteDeleteOutcome.Deleted -> {
                    // Forced deletion leaves the referencing rows dangling on purpose; they now
                    // report MissingPalette health and are skipped at apply.
                    state.show.fixtures.paletteListChanged()
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }
    }
}

private const val PALETTE_NOT_FOUND = "Palette not found"

private sealed interface PaletteDeleteOutcome {
    data object NotFound : PaletteDeleteOutcome
    data class InUse(val usage: PaletteUsage) : PaletteDeleteOutcome
    data class Deleted(val uuid: UUID) : PaletteDeleteOutcome
}

@Resource("/{projectId}/palettes")
data class ProjectPalettesResource(val projectId: String, val type: String? = null)

@Resource("/{paletteId}")
data class ProjectPaletteResource(
    val parent: ProjectPalettesResource,
    val paletteId: Int,
    val force: Boolean = false,
)

@Serializable
data class PaletteDto(
    val id: Int,
    val uuid: String,
    val name: String,
    val type: String,
    val notes: String? = null,
    val sortOrder: Int,
    /** Stored `(target, property)` rows. */
    val entryCount: Int,
    /** Distinct target keys — drives "covers 12 fixtures". */
    val targetCount: Int,
    /**
     * Up to [PALETTE_PREVIEW_SIZE] distinct literals, most-frequent first, so a tile can render
     * without fetching every palette's detail.
     */
    val preview: List<String>,
    /** Persisted rows referencing this palette. Gates delete. */
    val referenceCount: Int,
)

@Serializable
data class PaletteDetails(
    val id: Int,
    val uuid: String,
    val name: String,
    val type: String,
    val notes: String? = null,
    val sortOrder: Int,
    val entries: List<PaletteEntryDto>,
    val referenceCount: Int,
    /** Cues holding at least one row that references this palette. */
    val referencedByCueIds: List<Int>,
)

@Serializable
data class CreatePaletteRequest(
    val name: String,
    val type: String,
    val notes: String? = null,
    val sortOrder: Int? = null,
    val entries: List<PaletteEntryDto> = emptyList(),
)

@Serializable
data class PaletteInUseResponse(
    val error: String,
    val code: String,
    val referenceCount: Int,
    val cueAssignmentCount: Int,
    val presetAssignmentCount: Int,
    val cueIds: List<Int>,
)

/** Persisted references to one palette. Programmer slots are excluded — see the DELETE doc. */
internal data class PaletteUsage(
    val cueAssignments: Int,
    val presetAssignments: Int,
    val cueIds: List<Int>,
) {
    val total: Int get() = cueAssignments + presetAssignments

    fun describe(): String = buildList {
        if (cueAssignments > 0) add("$cueAssignments cue assignment${if (cueAssignments == 1) "" else "s"}")
        if (presetAssignments > 0) {
            add("$presetAssignments preset assignment${if (presetAssignments == 1) "" else "s"}")
        }
    }.joinToString(" and ")
}

/**
 * Count the persisted rows holding `ref:{paletteUuid}`.
 *
 * No project filter is needed: a palette uuid identifies exactly one record, so any row
 * referencing it belongs to that palette's project by construction. Exact string equality
 * rather than a prefix match — a `LIKE` would be a latent bug the moment a reference form
 * grows a suffix.
 *
 * Must be called inside a transaction.
 */
internal fun paletteUsage(paletteUuid: UUID): PaletteUsage =
    paletteUsageFor(listOf(paletteUuid))[paletteUuid] ?: PaletteUsage(0, 0, emptyList())

/**
 * Batched [paletteUsage]: two queries total rather than two per palette.
 *
 * The list endpoint reports `referenceCount` for every palette, so doing this per row made it
 * O(2N) round-trips — noticeable once a show has a realistic number of palettes, and pure waste
 * since a single `inList` answers the whole page.
 *
 * Must be called inside a transaction.
 */
internal fun paletteUsageFor(paletteUuids: Collection<UUID>): Map<UUID, PaletteUsage> {
    if (paletteUuids.isEmpty()) return emptyMap()
    val byRefValue = paletteUuids.associateBy { paletteRefValue(it) }
    val refValues = byRefValue.keys.toList()

    val cueRows = DaoCuePropertyAssignment
        .find { DaoCuePropertyAssignments.value inList refValues }
        .groupBy({ it.value }, { it.cue.id.value })
    val presetCounts = DaoFxPresetPropertyAssignment
        .find { DaoFxPresetPropertyAssignments.value inList refValues }
        .groupingBy { it.value }
        .eachCount()

    return byRefValue.entries.associate { (refValue, uuid) ->
        val cues = cueRows[refValue] ?: emptyList()
        uuid to PaletteUsage(
            cueAssignments = cues.size,
            presetAssignments = presetCounts[refValue] ?: 0,
            cueIds = cues.distinct().sorted(),
        )
    }
}

/**
 * Reject anything a palette entry must never hold. Returns an error string, or null when valid.
 *
 * The `ref:` rejection is what keeps palettes from nesting, so resolving a reference is a single
 * lookup and can never recurse.
 */
internal fun validatePaletteEntries(entries: List<PaletteEntryDto>): String? {
    for (entry in entries) {
        if (TargetRef.ofOrNull(entry.targetType, entry.targetKey) == null) {
            return "Unknown target type '${entry.targetType}'"
        }
        if (entry.propertyName.isBlank()) return "Palette entry property name must not be blank"
        if (entry.value.isBlank()) return "Palette entry value must not be blank"
        if (isPaletteRefValue(entry.value)) {
            return "Palette entries must hold literal values, not '$PALETTE_REF_PREFIX' references " +
                "(${entry.targetKey}.${entry.propertyName})"
        }
    }
    return null
}

/** Must be called inside a transaction. [usage] is resolved by the caller so a list can batch it. */
private fun DaoPalette.toSummaryDto(usage: PaletteUsage? = null): PaletteDto {
    val rows = entries.toList()
    return PaletteDto(
        id = id.value,
        uuid = uuid.toString(),
        name = name,
        type = type,
        notes = notes,
        sortOrder = sortOrder,
        entryCount = rows.size,
        targetCount = rows.map { it.targetKey }.distinct().size,
        preview = rows.groupingBy { it.value }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(PALETTE_PREVIEW_SIZE)
            .map { it.key },
        referenceCount = (usage ?: paletteUsage(uuid)).total,
    )
}

/** Must be called inside a transaction. */
private fun DaoPalette.toDetailsDto(): PaletteDetails {
    val usage = paletteUsage(uuid)
    return PaletteDetails(
        id = id.value,
        uuid = uuid.toString(),
        name = name,
        type = type,
        notes = notes,
        sortOrder = sortOrder,
        entries = entries
            .orderBy(
                DaoPaletteEntries.sortOrder to SortOrder.ASC,
                DaoPaletteEntries.targetKey to SortOrder.ASC,
                DaoPaletteEntries.propertyName to SortOrder.ASC,
            )
            .map {
                PaletteEntryDto(
                    targetType = it.targetType,
                    targetKey = it.targetKey,
                    propertyName = it.propertyName,
                    value = it.value,
                    sortOrder = it.sortOrder,
                )
            },
        referenceCount = usage.total,
        referencedByCueIds = usage.cueIds,
    )
}
