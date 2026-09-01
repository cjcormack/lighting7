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
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.me.cormack.lighting7.fx.MasterClock
import uk.me.cormack.lighting7.models.DaoCueAdHocEffect
import uk.me.cormack.lighting7.models.DaoCueLayers
import uk.me.cormack.lighting7.models.DaoCueLayer
import uk.me.cormack.lighting7.models.DaoCueAdHocEffects
import uk.me.cormack.lighting7.models.CODE_SPEED_MASTER_FOLLOWER
import uk.me.cormack.lighting7.models.CODE_SPEED_MASTER_INVALID
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoSpeedMaster
import uk.me.cormack.lighting7.models.DaoSpeedMasters
import uk.me.cormack.lighting7.models.SpeedMasterSettingsError
import uk.me.cormack.lighting7.models.SpeedMasterSource
import uk.me.cormack.lighting7.models.ensureDefaultSpeedMasters
import uk.me.cormack.lighting7.models.normaliseSpeedMasterUsage
import uk.me.cormack.lighting7.models.validateSpeedMasterSettings
import uk.me.cormack.lighting7.state.State
import java.util.UUID

// The delete-guard half of the speed-master error vocabulary. The write-boundary half
// (CODE_SPEED_MASTER_FOLLOWER / _USAGE_TAKEN / _CANNOT_FOLLOW / _INVALID / _UNKNOWN) lives in
// models/speedMasters.kt beside validateSpeedMasterSettings, because the socket needs it too —
// grep both files for the full client-facing set.

/** Error code for deleting the protected global master (index 1). */
internal const val CODE_SPEED_MASTER_PROTECTED = "SPEED_MASTER_PROTECTED"

/** Error code the client keys the "reassign those effects, then delete" recovery flow off. */
internal const val CODE_SPEED_MASTER_IN_USE = "SPEED_MASTER_IN_USE"

internal fun Route.routeApiRestProjectSpeedMasters(state: State) {
    get<ProjectSpeedMastersResource> { resource ->
        withProject(state, resource.projectId) { project ->
            val masters = transaction(state.database) {
                // Seeding here as well as at project create / Show start means the list is
                // never empty for a pre-speed-masters project the operator merely *browses*.
                // Idempotent, so a read-path write is safe.
                ensureDefaultSpeedMasters(project)
                val rows = DaoSpeedMaster.find { DaoSpeedMasters.project eq project.id }
                    .orderBy(DaoSpeedMasters.masterIndex to SortOrder.ASC)
                    .toList()
                val usage = speedMasterUsageFor(project, rows.map { it.uuid })
                rows.map { it.toDto(usage[it.uuid] ?: SpeedMasterUsage.NONE) }
            }
            call.respond(masters)
        }
    }

    get<ProjectSpeedMasterResource> { resource ->
        withProject(state, resource.parent.projectId) { project ->
            val dto = transaction(state.database) {
                val master = DaoSpeedMaster.findById(resource.masterId) ?: return@transaction null
                if (master.project.id != project.id) return@transaction null
                master.toDto(speedMasterUsage(project, master.uuid))
            }
            if (dto == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(SPEED_MASTER_NOT_FOUND))
            } else {
                call.respond(dto)
            }
        }
    }

    post<ProjectSpeedMastersResource> { resource ->
        withProject(state, resource.projectId) { project ->
            val request = call.receive<CreateSpeedMasterRequest>()
            when (val outcome = createSpeedMaster(state, project, request)) {
                is CreateSpeedMasterOutcome.Invalid ->
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(outcome.error, code = outcome.code))

                is CreateSpeedMasterOutcome.Conflict ->
                    call.respond(HttpStatusCode.Conflict, ErrorResponse(outcome.error, code = outcome.code))

                is CreateSpeedMasterOutcome.Created ->
                    call.respond(HttpStatusCode.Created, outcome.dto)
            }
        }
    }

    /**
     * Rename / notes / bpm / usage / follow ratio. The stored bpm is the master's starting
     * default; when the project is live, the write also retunes the running clock so a typed
     * tempo takes effect immediately. (Knob-drag / tap tempo goes over the `speedMasters.*` WS
     * family instead — this route is the "typed a number into a form" path.)
     *
     * Patch semantics: only present keys change. The follow pair moves together — if either
     * `followNum` or `followDen` is present both must be, so unlink is
     * `{"followNum":null,"followDen":null}` and a half-patch is a 400 rather than a silent
     * half-write. A `bpm` on an (effectively) following master is refused (D5's typed-tempo
     * half): a follower's tempo is derived, and its stored default is meaningless while linked.
     */
    put<ProjectSpeedMasterResource> { resource ->
        withProject(state, resource.parent.projectId) { project ->
            val body = call.receive<JsonObject>()
            val requestedBpm = body["bpm"].nullableDouble()
            validateBpm(requestedBpm)?.let { error ->
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(error))
                return@withProject
            }
            if (("followNum" in body) != ("followDen" in body)) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(
                        "followNum and followDen must be sent together (both null unlinks)",
                        code = CODE_SPEED_MASTER_INVALID,
                    ),
                )
                return@withProject
            }

            val result = transaction(state.database) {
                val master = DaoSpeedMaster.findById(resource.masterId)
                    ?: return@transaction UpdateSpeedMasterOutcome.NotFound
                if (master.project.id != project.id) {
                    return@transaction UpdateSpeedMasterOutcome.NotFound
                }

                // Effective post-write values: what the row will hold if this write lands.
                // Carried-forward values come through the same sanitisers every read path
                // uses (`followRatio`; an untouched usage skips re-validation), NOT the raw
                // columns — an imported row is written verbatim by design (see
                // ProjectImporter), and validating its untouched junk here would 400/409
                // every later PUT on it, rename and notes edits included. Only what this
                // request actually sends goes through the write-boundary rules.
                val usagePatched = "usage" in body
                val effectiveUsage = if (usagePatched) {
                    normaliseSpeedMasterUsage(body["usage"].nullableString())
                } else {
                    master.usageCategory
                }
                val storedRatio = master.followRatio
                val followPatched = "followNum" in body
                val effectiveNum = if (followPatched) body["followNum"].nullableInt() else storedRatio?.first
                val effectiveDen = if (followPatched) body["followDen"].nullableInt() else storedRatio?.second

                validateSpeedMasterSettings(
                    project = project,
                    masterIndex = master.masterIndex,
                    usage = if (usagePatched) effectiveUsage else null,
                    followNum = effectiveNum,
                    followDen = effectiveDen,
                    excludeId = master.id.value,
                )?.let { error ->
                    return@transaction when (error) {
                        is SpeedMasterSettingsError.Invalid ->
                            UpdateSpeedMasterOutcome.Invalid(error.message, error.code)

                        is SpeedMasterSettingsError.Conflict ->
                            UpdateSpeedMasterOutcome.Conflict(error.message, error.code)
                    }
                }
                if (requestedBpm != null && effectiveNum != null) {
                    return@transaction UpdateSpeedMasterOutcome.Invalid(
                        "${master.name} follows Master 1 at $effectiveNum/$effectiveDen — " +
                            "unlink it to set its tempo, or retune Master 1 instead",
                        CODE_SPEED_MASTER_FOLLOWER,
                    )
                }

                body["name"].nullableString()?.let { newName ->
                    val trimmed = newName.trim()
                    if (trimmed.isEmpty()) {
                        return@transaction UpdateSpeedMasterOutcome.Invalid(
                            "Speed master name must not be blank", code = null,
                        )
                    }
                    val collision = DaoSpeedMaster.find {
                        (DaoSpeedMasters.project eq project.id) and (DaoSpeedMasters.name eq trimmed)
                    }.firstOrNull()
                    if (collision != null && collision.id != master.id) {
                        return@transaction UpdateSpeedMasterOutcome.Conflict(
                            "A speed master called '$trimmed' already exists", code = null,
                        )
                    }
                    master.name = trimmed
                }
                if ("notes" in body) {
                    master.notes = body["notes"].nullableString()?.trim()?.takeIf { it.isNotEmpty() }
                }
                master.usageCategory = effectiveUsage
                master.followNum = effectiveNum
                master.followDen = effectiveDen
                requestedBpm?.let {
                    master.bpm = it
                    master.source = SpeedMasterSource.MANUAL.name
                }

                UpdateSpeedMasterOutcome.Updated(master.toDto(speedMasterUsage(project, master.uuid)))
            }
            val dto = when (result) {
                UpdateSpeedMasterOutcome.NotFound -> {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(SPEED_MASTER_NOT_FOUND))
                    return@withProject
                }

                is UpdateSpeedMasterOutcome.Invalid -> {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(result.error, code = result.code))
                    return@withProject
                }

                is UpdateSpeedMasterOutcome.Conflict -> {
                    call.respond(HttpStatusCode.Conflict, ErrorResponse(result.error, code = result.code))
                    return@withProject
                }

                is UpdateSpeedMasterOutcome.Updated -> result.dto
            }
            // Guarded like the create/delete sites: a rename in a non-current project must
            // not reload the live show's bank. For a follower link/unlink or ratio change,
            // the reload is also what re-derives the live tempo and streams the move.
            if (state.isCurrentProject(project)) state.show.reloadSpeedMasters()
            // The reload deliberately keeps surviving clocks' live tempo, so a typed bpm
            // must retune the running clock explicitly — and only for the live project.
            requestedBpm?.let {
                state.show.setSpeedMasterBpmIfCurrent(project.id.value, UUID.fromString(dto.uuid), it)
            }
            state.show.fixtures.speedMasterListChanged()
            call.respond(dto)
        }
    }

    /**
     * Refuses to delete master 1 (the global master every unassigned effect resolves to), and
     * refuses while persisted rows still reference the master, unless `?force=true`.
     *
     * A forced delete leaves referencing rows dangling on purpose; a dangling reference
     * resolves to master 1 at apply time, so the failure mode is "runs at the global tempo",
     * never "doesn't run". Live FX instances holding the deleted uuid rebind the same way.
     */
    delete<ProjectSpeedMasterResource> { resource ->
        withProject(state, resource.parent.projectId) { project ->
            val outcome = transaction(state.database) {
                val master = DaoSpeedMaster.findById(resource.masterId)
                    ?: return@transaction SpeedMasterDeleteOutcome.NotFound
                if (master.project.id != project.id) return@transaction SpeedMasterDeleteOutcome.NotFound

                if (master.masterIndex == 1) return@transaction SpeedMasterDeleteOutcome.Protected

                val usage = speedMasterUsage(project, master.uuid)
                if (usage.total > 0 && !resource.force) {
                    return@transaction SpeedMasterDeleteOutcome.InUse(usage)
                }
                master.delete()
                SpeedMasterDeleteOutcome.Deleted
            }

            when (outcome) {
                SpeedMasterDeleteOutcome.NotFound ->
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(SPEED_MASTER_NOT_FOUND))

                SpeedMasterDeleteOutcome.Protected -> call.respond(
                    HttpStatusCode.Conflict,
                    ErrorResponse(
                        "Master 1 is the global master and cannot be deleted",
                        code = CODE_SPEED_MASTER_PROTECTED,
                    ),
                )

                is SpeedMasterDeleteOutcome.InUse -> call.respond(
                    HttpStatusCode.Conflict,
                    SpeedMasterInUseResponse(
                        error = "Speed master is referenced by ${outcome.usage.describe()}",
                        code = CODE_SPEED_MASTER_IN_USE,
                        referenceCount = outcome.usage.total,
                        lookEffectCount = outcome.usage.lookEffects,
                        cueAdHocEffectCount = outcome.usage.cueAdHocEffects,
                        cueLayerCount = outcome.usage.cueLayers,
                        cueIds = outcome.usage.cueIds,
                    ),
                )

                SpeedMasterDeleteOutcome.Deleted -> {
                    if (state.isCurrentProject(project)) state.show.reloadSpeedMasters()
                    state.show.fixtures.speedMasterListChanged()
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }
    }
}

/**
 * What [createSpeedMaster] did. Two rejection cases rather than one string because the REST
 * route maps them to different statuses (400 vs 409) while the AI surface maps both to a
 * failed tool result.
 */
internal sealed interface CreateSpeedMasterOutcome {
    data class Created(val dto: SpeedMasterDto) : CreateSpeedMasterOutcome

    /** Malformed request — blank name, out-of-range bpm, bad usage/ratio. */
    data class Invalid(val error: String, val code: String? = null) : CreateSpeedMasterOutcome

    /** A master of that name — or claiming that usage — already exists in the project. */
    data class Conflict(val error: String, val code: String? = null) : CreateSpeedMasterOutcome
}

/** What the PUT did. Mirrors [CreateSpeedMasterOutcome] so 400 and 409 stay distinguishable. */
private sealed interface UpdateSpeedMasterOutcome {
    data class Updated(val dto: SpeedMasterDto) : UpdateSpeedMasterOutcome
    data object NotFound : UpdateSpeedMasterOutcome
    data class Invalid(val error: String, val code: String?) : UpdateSpeedMasterOutcome
    data class Conflict(val error: String, val code: String?) : UpdateSpeedMasterOutcome
}

/**
 * Append a speed master to [project]'s bank: validate, write the row, and — when the project is
 * the live one — reload the running bank and broadcast the list change.
 *
 * Shared with the AI surface's `create_speed_master` so a master a tool call adds is
 * indistinguishable from one the UI adds: same defaulted name, same duplicate refusal, same
 * bank reload. Everything about a new master is derived (index, default bpm), so the two
 * callers only differ in how they render the outcome.
 */
internal fun createSpeedMaster(
    state: State,
    project: DaoProject,
    request: CreateSpeedMasterRequest,
): CreateSpeedMasterOutcome {
    val trimmedName = request.name?.trim()
    if (trimmedName != null && trimmedName.isEmpty()) {
        return CreateSpeedMasterOutcome.Invalid("Speed master name must not be blank")
    }
    validateBpm(request.bpm)?.let { return CreateSpeedMasterOutcome.Invalid(it) }
    // D5's typed-tempo rule, same as the PUT: a follower's tempo is derived from Master 1, so
    // a bpm sent alongside a ratio would be a 201 telling the caller a tempo that the load
    // sweep immediately overwrites. Refused here so create and update agree.
    if (request.bpm != null && request.followNum != null) {
        return CreateSpeedMasterOutcome.Invalid(
            "A master created with a follow ratio derives its tempo from Master 1 — omit bpm, " +
                "or create it without the ratio",
            CODE_SPEED_MASTER_FOLLOWER,
        )
    }
    val usage = normaliseSpeedMasterUsage(request.usage)

    val outcome = transaction(state.database) {
        ensureDefaultSpeedMasters(project)
        val existing = DaoSpeedMaster.find { DaoSpeedMasters.project eq project.id }.toList()
        val nextIndex = (existing.maxOfOrNull { it.masterIndex } ?: 0) + 1
        val name = trimmedName ?: "Master $nextIndex"
        if (existing.any { it.name == name }) {
            return@transaction CreateSpeedMasterOutcome.Conflict(
                "A speed master called '$name' already exists",
            )
        }
        validateSpeedMasterSettings(
            project = project,
            masterIndex = nextIndex,
            usage = usage,
            followNum = request.followNum,
            followDen = request.followDen,
            excludeId = null,
        )?.let { error ->
            return@transaction when (error) {
                is SpeedMasterSettingsError.Invalid ->
                    CreateSpeedMasterOutcome.Invalid(error.message, error.code)

                is SpeedMasterSettingsError.Conflict ->
                    CreateSpeedMasterOutcome.Conflict(error.message, error.code)
            }
        }
        val master = DaoSpeedMaster.new {
            this.project = project
            masterIndex = nextIndex
            this.name = name
            request.bpm?.let { bpm = it }
            this.notes = request.notes?.trim()?.takeIf { it.isNotEmpty() }
            usageCategory = usage
            followNum = request.followNum
            followDen = request.followDen
        }
        CreateSpeedMasterOutcome.Created(master.toDto(SpeedMasterUsage.NONE))
    }
    if (outcome !is CreateSpeedMasterOutcome.Created) return outcome

    // Only the live show's bank needs reloading, and only when the edited project IS
    // the live one — reloading it after editing some other project's rows is a wasted
    // transaction against an unrelated bank (same guard rationale as
    // Show.setSpeedMasterBpmIfCurrent). The list broadcast stays unconditional, like
    // paletteListChanged.
    if (state.isCurrentProject(project)) state.show.reloadSpeedMasters()
    state.show.fixtures.speedMasterListChanged()
    return outcome
}

private const val SPEED_MASTER_NOT_FOUND = "Speed master not found"

private sealed interface SpeedMasterDeleteOutcome {
    data object NotFound : SpeedMasterDeleteOutcome
    data object Protected : SpeedMasterDeleteOutcome
    data class InUse(val usage: SpeedMasterUsage) : SpeedMasterDeleteOutcome
    data object Deleted : SpeedMasterDeleteOutcome
}

@Resource("/{projectId}/speed-masters")
data class ProjectSpeedMastersResource(val projectId: String)

@Resource("/{masterId}")
data class ProjectSpeedMasterResource(
    val parent: ProjectSpeedMastersResource,
    val masterId: Int,
    val force: Boolean = false,
)

@Serializable
data class SpeedMasterDto(
    val id: Int,
    val uuid: String,
    val masterIndex: Int,
    val name: String,
    /** Stored (starting) tempo. The live tempo streams over the `speedMasters.*` WS family. */
    val bpm: Double,
    /** `MANUAL` / `TAP` — how the tempo was last set. Display only. */
    val source: String,
    val notes: String? = null,
    /** Effect-library category this master is the apply-time default for; null routes nothing. */
    val usage: String? = null,
    /** Follow ratio over master 1 (`bpm = m1 × num/den`); both null = manual tempo. */
    val followNum: Int? = null,
    val followDen: Int? = null,
    /** Persisted rows referencing this master. Gates delete. */
    val referenceCount: Int,
)

@Serializable
data class CreateSpeedMasterRequest(
    /** Defaults to "Master {index}". */
    val name: String? = null,
    val bpm: Double? = null,
    val notes: String? = null,
    /** See [SpeedMasterDto.usage]; validated against the canonical set. */
    val usage: String? = null,
    /** See [SpeedMasterDto.followNum]; both-or-neither, positive. */
    val followNum: Int? = null,
    val followDen: Int? = null,
)

@Serializable
data class SpeedMasterInUseResponse(
    val error: String,
    val code: String,
    val referenceCount: Int,
    /** Effects stored on a Look. Was `presetEffectCount`, over `fx_presets`. */
    val lookEffectCount: Int,
    val cueAdHocEffectCount: Int,
    /** Per-layer speed-master overrides. Was `cuePresetApplicationCount`. */
    val cueLayerCount: Int,
    val cueIds: List<Int>,
)

/** Persisted references to one speed master. Live FX instances are excluded — they rebind to master 1. */
internal data class SpeedMasterUsage(
    /** Effects stored on a **Look** (`DaoLookEffects`). Was `presetEffects`. */
    val lookEffects: Int,
    val cueAdHocEffects: Int,
    /** Per-layer speed-master overrides (`DaoCueLayers`). Was `cuePresetApplications`. */
    val cueLayers: Int,
    val cueIds: List<Int>,
) {
    val total: Int get() = lookEffects + cueAdHocEffects + cueLayers

    fun describe(): String = buildList {
        if (lookEffects > 0) add("$lookEffects look effect${if (lookEffects == 1) "" else "s"}")
        if (cueAdHocEffects > 0) add("$cueAdHocEffects cue effect${if (cueAdHocEffects == 1) "" else "s"}")
        if (cueLayers > 0) add("$cueLayers cue layer${if (cueLayers == 1) "" else "s"}")
    }.joinToString(" and ")

    companion object {
        val NONE = SpeedMasterUsage(0, 0, 0, emptyList())
    }
}

/** Must be called inside a transaction. */
internal fun speedMasterUsage(project: DaoProject, masterUuid: UUID): SpeedMasterUsage =
    speedMasterUsageFor(project, listOf(masterUuid))[masterUuid] ?: SpeedMasterUsage.NONE

/**
 * Batched persisted-reference counts, one pass per source.
 *
 * The ad-hoc and preset-application references are real columns, so the cue tables answer with
 * an `inList`; no project filter is needed there — a master uuid identifies exactly one record,
 * so any row referencing it belongs to that master's project by construction (the palette-usage
 * rationale). Preset references live inside the `fx_presets.effects` JSON blob and are scanned
 * in memory over [project]'s presets, which *does* need the project — the blob can't be indexed.
 *
 * Must be called inside a transaction.
 */
internal fun speedMasterUsageFor(
    project: DaoProject,
    masterUuids: Collection<UUID>,
): Map<UUID, SpeedMasterUsage> {
    if (masterUuids.isEmpty()) return emptyMap()
    val uuidSet = masterUuids.toSet()
    val uuidStrings = uuidSet.associateBy { it.toString() }

    // Both roles count. A master referenced only as a wall-clock *rate* master is just as
    // much in use as one an effect runs on, and counting only the latter would let the
    // delete guard wave through a master that a look still depends on.
    //
    // Reads `DaoLookEffects` through `project.looks`, where it read `project.fxPresets` and their
    // JSON `effects` blob until session 4. Same question, better shape: a look effect's masters are
    // real nullable columns rather than fields inside an opaque blob, so this could be a query
    // rather than a scan — kept as a scan only because the Look count per project is small and the
    // rows are already loaded by the referrer.
    val lookEffectCounts = mutableMapOf<UUID, Int>()
    project.looks.forEach { look ->
        look.effects.forEach { effect ->
            // Compared as UUIDs, not strings. `DaoLookEffects` gave these real `javaUUID` columns;
            // the preset era stored them as strings inside a JSON blob, which is why the old code
            // went through `uuidStrings`.
            val referenced = listOfNotNull(effect.speedMasterUuid, effect.rateSpeedMasterUuid)
                .distinct()
                .filter { it in uuidSet }
            referenced.forEach { lookEffectCounts.merge(it, 1, Int::plus) }
        }
    }

    // A row referencing the same master in both roles counts once — `total` gates the 409,
    // and "2 references" from one row would read as two separate places to go and fix.
    fun <T> Iterable<T>.byMaster(
        speed: (T) -> UUID?,
        rate: (T) -> UUID?,
        cueId: (T) -> Int,
    ): Map<UUID, List<Int>> {
        val out = HashMap<UUID, MutableList<Int>>()
        forEach { row ->
            listOfNotNull(speed(row), rate(row)).distinct()
                .filter { it in uuidSet }
                .forEach { out.getOrPut(it) { mutableListOf() }.add(cueId(row)) }
        }
        return out
    }

    val adHocRows = DaoCueAdHocEffect
        .find {
            (DaoCueAdHocEffects.speedMasterUuid inList uuidSet) or
                (DaoCueAdHocEffects.rateSpeedMasterUuid inList uuidSet)
        }
        .byMaster({ it.speedMasterUuid }, { it.rateSpeedMasterUuid }, { it.cue.id.value })
    val layerRows = DaoCueLayer
        .find {
            (DaoCueLayers.speedMasterUuid inList uuidSet) or
                (DaoCueLayers.rateSpeedMasterUuid inList uuidSet)
        }
        .byMaster({ it.speedMasterUuid }, { it.rateSpeedMasterUuid }, { it.cue.id.value })

    return uuidSet.associateWith { uuid ->
        val adHocCues = adHocRows[uuid] ?: emptyList()
        val layerCues = layerRows[uuid] ?: emptyList()
        SpeedMasterUsage(
            lookEffects = lookEffectCounts[uuid] ?: 0,
            cueAdHocEffects = adHocCues.size,
            cueLayers = layerCues.size,
            cueIds = (adHocCues + layerCues).distinct().sorted(),
        )
    }
}

private fun validateBpm(bpm: Double?): String? {
    if (bpm == null) return null
    if (bpm < MasterClock.MIN_BPM || bpm > MasterClock.MAX_BPM) {
        return "BPM must be between ${MasterClock.MIN_BPM} and ${MasterClock.MAX_BPM}"
    }
    return null
}

/**
 * Must be called inside a transaction. [references] is resolved by the caller so a list can
 * batch it. (Named `references`, not `usage` — that now means the routing category.)
 */
private fun DaoSpeedMaster.toDto(references: SpeedMasterUsage): SpeedMasterDto = SpeedMasterDto(
    id = id.value,
    uuid = uuid.toString(),
    masterIndex = masterIndex,
    name = name,
    bpm = bpm,
    source = source,
    notes = notes,
    usage = usageCategory,
    followNum = followRatio?.first,
    followDen = followRatio?.second,
    referenceCount = references.total,
)
