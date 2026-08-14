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
import uk.me.cormack.lighting7.fx.MasterClock
import uk.me.cormack.lighting7.models.DaoCueAdHocEffect
import uk.me.cormack.lighting7.models.DaoCueAdHocEffects
import uk.me.cormack.lighting7.models.DaoCuePresetApplication
import uk.me.cormack.lighting7.models.DaoCuePresetApplications
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoSpeedMaster
import uk.me.cormack.lighting7.models.DaoSpeedMasters
import uk.me.cormack.lighting7.models.SpeedMasterSource
import uk.me.cormack.lighting7.models.ensureDefaultSpeedMasters
import uk.me.cormack.lighting7.state.State
import java.util.UUID

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
            val trimmedName = request.name?.trim()
            if (trimmedName != null && trimmedName.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Speed master name must not be blank"))
                return@withProject
            }
            validateBpm(request.bpm)?.let { error ->
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(error))
                return@withProject
            }

            val result = transaction(state.database) {
                ensureDefaultSpeedMasters(project)
                val existing = DaoSpeedMaster.find { DaoSpeedMasters.project eq project.id }.toList()
                val nextIndex = (existing.maxOfOrNull { it.masterIndex } ?: 0) + 1
                val name = trimmedName ?: "Master $nextIndex"
                if (existing.any { it.name == name }) {
                    return@transaction Pair<SpeedMasterDto?, String?>(
                        null,
                        "A speed master called '$name' already exists",
                    )
                }
                val master = DaoSpeedMaster.new {
                    this.project = project
                    masterIndex = nextIndex
                    this.name = name
                    request.bpm?.let { bpm = it }
                    this.notes = request.notes?.trim()?.takeIf { it.isNotEmpty() }
                }
                Pair<SpeedMasterDto?, String?>(master.toDto(SpeedMasterUsage.NONE), null)
            }
            val (dto, error) = result
            if (error != null) {
                call.respond(HttpStatusCode.Conflict, ErrorResponse(error))
                return@withProject
            }
            // Only the live show's bank needs reloading, and only when the edited project IS
            // the live one — reloading it after editing some other project's rows is a wasted
            // transaction against an unrelated bank (same guard rationale as
            // Show.setSpeedMasterBpmIfCurrent). The list broadcast stays unconditional, like
            // paletteListChanged.
            if (state.isCurrentProject(project)) state.show.reloadSpeedMasters()
            state.show.fixtures.speedMasterListChanged()
            call.respond(HttpStatusCode.Created, dto!!)
        }
    }

    /**
     * Rename / notes / bpm. The stored bpm is the master's starting default; when the project
     * is live, the write also retunes the running clock so a typed tempo takes effect
     * immediately. (Knob-drag / tap tempo goes over the `speedMasters.*` WS family instead —
     * this route is the "typed a number into a form" path.)
     */
    put<ProjectSpeedMasterResource> { resource ->
        withProject(state, resource.parent.projectId) { project ->
            val body = call.receive<JsonObject>()
            val requestedBpm = body["bpm"].nullableDouble()
            validateBpm(requestedBpm)?.let { error ->
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(error))
                return@withProject
            }

            val result = transaction(state.database) {
                val master = DaoSpeedMaster.findById(resource.masterId)
                    ?: return@transaction Pair<SpeedMasterDto?, String?>(null, SPEED_MASTER_NOT_FOUND)
                if (master.project.id != project.id) {
                    return@transaction Pair<SpeedMasterDto?, String?>(null, SPEED_MASTER_NOT_FOUND)
                }

                body["name"].nullableString()?.let { newName ->
                    val trimmed = newName.trim()
                    if (trimmed.isEmpty()) {
                        return@transaction Pair<SpeedMasterDto?, String?>(
                            null, "Speed master name must not be blank",
                        )
                    }
                    val collision = DaoSpeedMaster.find {
                        (DaoSpeedMasters.project eq project.id) and (DaoSpeedMasters.name eq trimmed)
                    }.firstOrNull()
                    if (collision != null && collision.id != master.id) {
                        return@transaction Pair<SpeedMasterDto?, String?>(
                            null, "A speed master called '$trimmed' already exists",
                        )
                    }
                    master.name = trimmed
                }
                if ("notes" in body) {
                    master.notes = body["notes"].nullableString()?.trim()?.takeIf { it.isNotEmpty() }
                }
                requestedBpm?.let {
                    master.bpm = it
                    master.source = SpeedMasterSource.MANUAL.name
                }

                Pair<SpeedMasterDto?, String?>(master.toDto(speedMasterUsage(project, master.uuid)), null)
            }
            val (dto, error) = result
            if (error != null) {
                val code = if (error == SPEED_MASTER_NOT_FOUND) HttpStatusCode.NotFound else HttpStatusCode.Conflict
                call.respond(code, ErrorResponse(error))
                return@withProject
            }
            // Guarded like the create/delete sites: a rename in a non-current project must
            // not reload the live show's bank.
            if (state.isCurrentProject(project)) state.show.reloadSpeedMasters()
            // The reload deliberately keeps surviving clocks' live tempo, so a typed bpm
            // must retune the running clock explicitly — and only for the live project.
            requestedBpm?.let {
                state.show.setSpeedMasterBpmIfCurrent(project.id.value, UUID.fromString(dto!!.uuid), it)
            }
            state.show.fixtures.speedMasterListChanged()
            call.respond(dto!!)
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
                        presetEffectCount = outcome.usage.presetEffects,
                        cueAdHocEffectCount = outcome.usage.cueAdHocEffects,
                        cuePresetApplicationCount = outcome.usage.cuePresetApplications,
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
    /** Persisted rows referencing this master. Gates delete. */
    val referenceCount: Int,
)

@Serializable
data class CreateSpeedMasterRequest(
    /** Defaults to "Master {index}". */
    val name: String? = null,
    val bpm: Double? = null,
    val notes: String? = null,
)

@Serializable
data class SpeedMasterInUseResponse(
    val error: String,
    val code: String,
    val referenceCount: Int,
    val presetEffectCount: Int,
    val cueAdHocEffectCount: Int,
    val cuePresetApplicationCount: Int,
    val cueIds: List<Int>,
)

/** Persisted references to one speed master. Live FX instances are excluded — they rebind to master 1. */
internal data class SpeedMasterUsage(
    val presetEffects: Int,
    val cueAdHocEffects: Int,
    val cuePresetApplications: Int,
    val cueIds: List<Int>,
) {
    val total: Int get() = presetEffects + cueAdHocEffects + cuePresetApplications

    fun describe(): String = buildList {
        if (presetEffects > 0) add("$presetEffects preset effect${if (presetEffects == 1) "" else "s"}")
        if (cueAdHocEffects > 0) add("$cueAdHocEffects cue effect${if (cueAdHocEffects == 1) "" else "s"}")
        if (cuePresetApplications > 0) {
            add("$cuePresetApplications cue preset application${if (cuePresetApplications == 1) "" else "s"}")
        }
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

    val presetEffectCounts = mutableMapOf<UUID, Int>()
    project.fxPresets.forEach { preset ->
        preset.effects.forEach { effect ->
            val uuid = effect.speedMasterUuid?.let { uuidStrings[it] } ?: return@forEach
            presetEffectCounts.merge(uuid, 1, Int::plus)
        }
    }

    val adHocRows = DaoCueAdHocEffect
        .find { DaoCueAdHocEffects.speedMasterUuid inList uuidSet }
        .groupBy({ it.speedMasterUuid!! }, { it.cue.id.value })
    val presetAppRows = DaoCuePresetApplication
        .find { DaoCuePresetApplications.speedMasterUuid inList uuidSet }
        .groupBy({ it.speedMasterUuid!! }, { it.cue.id.value })

    return uuidSet.associateWith { uuid ->
        val adHocCues = adHocRows[uuid] ?: emptyList()
        val presetAppCues = presetAppRows[uuid] ?: emptyList()
        SpeedMasterUsage(
            presetEffects = presetEffectCounts[uuid] ?: 0,
            cueAdHocEffects = adHocCues.size,
            cuePresetApplications = presetAppCues.size,
            cueIds = (adHocCues + presetAppCues).distinct().sorted(),
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

/** Must be called inside a transaction. [usage] is resolved by the caller so a list can batch it. */
private fun DaoSpeedMaster.toDto(usage: SpeedMasterUsage): SpeedMasterDto = SpeedMasterDto(
    id = id.value,
    uuid = uuid.toString(),
    masterIndex = masterIndex,
    name = name,
    bpm = bpm,
    source = source,
    notes = notes,
    referenceCount = usage.total,
)
