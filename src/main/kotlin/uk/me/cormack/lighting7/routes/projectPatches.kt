package uk.me.cormack.lighting7.routes

import io.ktor.http.*
import io.ktor.resources.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.resources.delete
import io.ktor.server.response.*
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import uk.me.cormack.lighting7.fixture.FixtureKind
import uk.me.cormack.lighting7.fixture.FixtureTypeRegistry
import uk.me.cormack.lighting7.models.*
import uk.me.cormack.lighting7.show.DbFixtureLoader
import uk.me.cormack.lighting7.show.Fixtures
import uk.me.cormack.lighting7.state.State
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

internal fun Route.routeApiRestProjectPatches(state: State) {
    // GET /{projectId}/patches - List all patches for a project
    get<ProjectPatchesResource> { resource ->
        withProject(state, resource.projectId) { project ->
            val patches = transaction(state.database) {
                DaoFixturePatch.find { DaoFixturePatches.project eq project.id }
                    .orderBy(DaoFixturePatches.sortOrder to SortOrder.ASC)
                    .map { it.toDto() }
            }
            call.respond(patches)
        }
    }

    // GET /{projectId}/patches/{patchId} - Get a single patch
    get<ProjectPatchResource> { resource ->
        withProject(state, resource.parent.projectId) { project ->
            val dto = transaction(state.database) {
                val patch = DaoFixturePatch.findById(resource.patchId) ?: return@transaction null
                if (patch.project.id != project.id) return@transaction null
                patch.toDto()
            }
            if (dto == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Patch not found"))
            } else {
                call.respond(dto)
            }
        }
    }

    // POST /{projectId}/patches - Create a single patch
    post<ProjectPatchesResource> { resource ->
        withProject(state, resource.projectId) { project ->
            val request = call.receive<CreatePatchRequest>()

            // Validate fixture type
            val typeInfo = FixtureTypeRegistry.allTypes.find { it.typeKey == request.fixtureTypeKey }
            if (typeInfo == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Unknown fixture type: ${request.fixtureTypeKey}"))
                return@withProject
            }

            val channelCount = typeInfo.channelCount
            if (channelCount == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Fixture type ${request.fixtureTypeKey} has no channel count"))
                return@withProject
            }

            // Validate channels fit
            val lastChannel = request.startChannel + channelCount - 1
            if (lastChannel > 512) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(
                    "Fixture extends to channel $lastChannel (max 512). Max start channel: ${512 - channelCount + 1}"
                ))
                return@withProject
            }

            val stageError = validateStageMetadata(
                stageX = request.stageX,
                stageY = request.stageY,
                stageZ = request.stageZ,
                baseYawDeg = request.baseYawDeg,
                basePitchDeg = request.basePitchDeg,
                beamAngleDeg = request.beamAngleDeg,
            )
            if (stageError != null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(stageError))
                return@withProject
            }
            val normalisedGelCode = normaliseGelCode(request.gelCode)
            val normalisedKindOverride = try {
                normaliseKindOverride(request.kindOverride)
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Invalid kindOverride"))
                return@withProject
            }

            val result = transaction(state.database) {
                val rigging = request.riggingUuid?.let { resolveRiggingForProject(project, it) }
                if (request.riggingUuid != null && rigging == null) {
                    return@transaction Pair<FixturePatchDto?, String?>(null, "Rigging ${request.riggingUuid} not found")
                }

                // Find or create universe config
                val universeConfig = DaoUniverseConfig.find {
                    (DaoUniverseConfigs.project eq project.id) and
                        (DaoUniverseConfigs.subnet eq 0) and
                        (DaoUniverseConfigs.universe eq request.universe)
                }.firstOrNull() ?: DaoUniverseConfig.new {
                    this.project = project
                    this.subnet = 0
                    this.universe = request.universe
                    this.controllerType = "ARTNET"
                    this.address = request.address
                }

                // Check for channel overlap
                val overlap = DaoFixturePatch.find {
                    DaoFixturePatches.universeConfig eq universeConfig.id
                }.firstOrNull { existing ->
                    val existingType = FixtureTypeRegistry.allTypes.find { it.typeKey == existing.fixtureTypeKey }
                    val existingChannelCount = existingType?.channelCount ?: 1
                    val existingEnd = existing.startChannel + existingChannelCount - 1
                    request.startChannel <= existingEnd && lastChannel >= existing.startChannel
                }
                if (overlap != null) {
                    return@transaction Pair<FixturePatchDto?, String?>(
                        null, "Channel overlap with fixture '${overlap.displayName}' (${overlap.key})"
                    )
                }

                // Check key uniqueness
                val existingKey = DaoFixturePatch.find {
                    (DaoFixturePatches.project eq project.id) and (DaoFixturePatches.key eq request.key)
                }.firstOrNull()
                if (existingKey != null) {
                    return@transaction Pair<FixturePatchDto?, String?>(null, "Duplicate key: ${request.key}")
                }

                val maxSortOrder = DaoFixturePatch.find { DaoFixturePatches.project eq project.id }
                    .maxOfOrNull { it.sortOrder } ?: -1

                val patch = DaoFixturePatch.new {
                    this.project = project
                    this.universeConfig = universeConfig
                    this.rigging = rigging
                    this.fixtureTypeKey = request.fixtureTypeKey
                    this.key = request.key
                    this.displayName = request.name
                    this.startChannel = request.startChannel
                    this.sortOrder = maxSortOrder + 1
                    this.stageX = request.stageX
                    this.stageY = request.stageY
                    this.stageZ = request.stageZ
                    this.baseYawDeg = request.baseYawDeg
                    this.basePitchDeg = request.basePitchDeg
                    this.beamAngleDeg = request.beamAngleDeg
                    this.gelCode = normalisedGelCode
                    this.kindOverride = normalisedKindOverride
                }

                // Assign to group if specified
                request.groupName?.takeIf { it.isNotBlank() }?.let { groupName ->
                    val group = findOrCreateGroup(project, groupName)
                    assignPatchToGroup(patch, group)
                }

                Pair<FixturePatchDto?, String?>(patch.toDto(), null)
            }

            val (patchDto, error) = result
            if (error != null) {
                call.respond(HttpStatusCode.Conflict, ErrorResponse(error))
                return@withProject
            }

            if (state.isCurrentProject(project)) {
                DbFixtureLoader.loadFixtures(project.id.value, state.show.fixtures, state.database, parkSource = state.show.parkManager)
            }
            state.show.fixtures.patchListChanged()

            call.respond(HttpStatusCode.Created, patchDto!!)
        }
    }

    // PUT /{projectId}/patches/{patchId} — partial update.
    // Keys absent from the JSON body are left unchanged; for the nullable
    // stage-metadata fields, an explicit JSON null clears the value.
    //
    // The runtime Fixtures registry is rebuilt via [DbFixtureLoader] only when
    // a key the loader actually reads (typeKey, displayName, startChannel,
    // group membership, …) was touched. Metadata-only edits (stageX/Y, gel,
    // beam, rigging position) skip the rebuild — Phase 2's drag-to-place UI
    // PUTs once per ~300 ms drag flush, and a full rebuild per flush would
    // tear down and recreate every controller and fixture for no benefit.
    put<ProjectPatchResource> { resource ->
        withProject(state, resource.parent.projectId) { project ->
            val body = call.receive<JsonObject>()

            // Range-check stage metadata before opening the transaction so a bad
            // request fails with a 400 instead of rolling back a write.
            val stageError = validateStageMetadata(
                stageX = body["stageX"].nullableDouble(),
                stageY = body["stageY"].nullableDouble(),
                stageZ = body["stageZ"].nullableDouble(),
                baseYawDeg = body["baseYawDeg"].nullableDouble(),
                basePitchDeg = body["basePitchDeg"].nullableDouble(),
                beamAngleDeg = body["beamAngleDeg"].nullableInt(),
            )
            if (stageError != null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(stageError))
                return@withProject
            }

            val normalisedKindOverride: String? = if ("kindOverride" in body) {
                try {
                    normaliseKindOverride(body["kindOverride"].nullableString())
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Invalid kindOverride"))
                    return@withProject
                }
            } else null

            val result = transaction(state.database) {
                val patch = DaoFixturePatch.findById(resource.patchId)
                    ?: return@transaction Pair<FixturePatchDto?, String?>(null, "Patch not found")

                if (patch.project.id != project.id) {
                    return@transaction Pair<FixturePatchDto?, String?>(null, "Patch not found")
                }

                body["displayName"].nullableString()?.let { patch.displayName = it }
                body["key"].nullableString()?.let { newKey ->
                    val existing = DaoFixturePatch.find {
                        (DaoFixturePatches.project eq project.id) and (DaoFixturePatches.key eq newKey)
                    }.firstOrNull()
                    if (existing != null && existing.id != patch.id) {
                        return@transaction Pair<FixturePatchDto?, String?>(null, "Key '$newKey' already exists")
                    }
                    patch.key = newKey
                }
                body["startChannel"].nullableInt()?.let { patch.startChannel = it }

                if ("stageX" in body) patch.stageX = body["stageX"].nullableDouble()
                if ("stageY" in body) patch.stageY = body["stageY"].nullableDouble()
                if ("stageZ" in body) patch.stageZ = body["stageZ"].nullableDouble()
                if ("baseYawDeg" in body) patch.baseYawDeg = body["baseYawDeg"].nullableDouble()
                if ("basePitchDeg" in body) patch.basePitchDeg = body["basePitchDeg"].nullableDouble()
                if ("beamAngleDeg" in body) patch.beamAngleDeg = body["beamAngleDeg"].nullableInt()
                if ("riggingUuid" in body) {
                    val uuidStr = body["riggingUuid"].nullableString()
                    if (uuidStr == null) {
                        patch.rigging = null
                    } else {
                        val rigging = resolveRiggingForProject(project, uuidStr)
                            ?: return@transaction Pair<FixturePatchDto?, String?>(null, "Rigging $uuidStr not found")
                        patch.rigging = rigging
                    }
                }
                if ("gelCode" in body) {
                    patch.gelCode = normaliseGelCode(body["gelCode"].nullableString())
                }
                if ("kindOverride" in body) {
                    patch.kindOverride = normalisedKindOverride
                }

                body["removeFromGroupId"].nullableInt()?.let { groupId ->
                    DaoFixtureGroupMember.find {
                        (DaoFixtureGroupMembers.fixturePatch eq patch.id) and
                            (DaoFixtureGroupMembers.group eq groupId)
                    }.forEach { it.delete() }
                }
                body["addToGroup"].nullableString()?.let { groupName ->
                    val group = findOrCreateGroup(project, groupName)
                    val alreadyMember = DaoFixtureGroupMember.find {
                        (DaoFixtureGroupMembers.fixturePatch eq patch.id) and
                            (DaoFixtureGroupMembers.group eq group.id)
                    }.firstOrNull()
                    if (alreadyMember == null) {
                        val maxOrder = group.members.maxOfOrNull { it.sortOrder } ?: -1
                        DaoFixtureGroupMember.new {
                            this.group = group
                            this.fixturePatch = patch
                            this.sortOrder = maxOrder + 1
                        }
                    }
                }

                Pair<FixturePatchDto?, String?>(patch.toDto(), null)
            }

            val (patchDto, error) = result
            if (error != null) {
                val code = if (error == "Patch not found") HttpStatusCode.NotFound else HttpStatusCode.Conflict
                call.respond(code, ErrorResponse(error))
                return@withProject
            }

            val touchedRebuildKey = body.keys.any { it !in METADATA_ONLY_PUT_KEYS }
            if (touchedRebuildKey && state.isCurrentProject(project)) {
                DbFixtureLoader.loadFixtures(project.id.value, state.show.fixtures, state.database, parkSource = state.show.parkManager)
            } else if (state.isCurrentProject(project)) {
                // Metadata-only edits skip the rebuild, so refresh the cache directly.
                state.show.fixtures.setPatchMetadata(
                    patchDto!!.key,
                    Fixtures.FixturePatchMetadata(gelCode = patchDto.gelCode),
                )
            }
            state.show.fixtures.patchListChanged()

            call.respond(patchDto!!)
        }
    }

    // DELETE /{projectId}/patches/{patchId} - Delete a patch
    delete<ProjectPatchResource> { resource ->
        withProject(state, resource.parent.projectId) { project ->
            val deleted = transaction(state.database) {
                val patch = DaoFixturePatch.findById(resource.patchId) ?: return@transaction false
                if (patch.project.id != project.id) return@transaction false

                // Remove from any groups first
                DaoFixtureGroupMember.find { DaoFixtureGroupMembers.fixturePatch eq patch.id }
                    .forEach { it.delete() }

                patch.delete()
                true
            }

            if (!deleted) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Patch not found"))
                return@withProject
            }

            if (state.isCurrentProject(project)) {
                DbFixtureLoader.loadFixtures(project.id.value, state.show.fixtures, state.database, parkSource = state.show.parkManager)
            }
            state.show.fixtures.patchListChanged()

            call.respond(HttpStatusCode.NoContent)
        }
    }
}

// Resources
@Resource("/{projectId}/patches")
data class ProjectPatchesResource(val projectId: String)

@Resource("/{patchId}")
data class ProjectPatchResource(val parent: ProjectPatchesResource, val patchId: Int)

// DTOs
@Serializable
data class FixturePatchDto(
    val id: Int,
    val key: String,
    val displayName: String,
    val fixtureTypeKey: String,
    val startChannel: Int,
    val channelCount: Int?,
    val manufacturer: String?,
    val model: String?,
    val modeName: String?,
    val universe: Int,
    val subnet: Int,
    val sortOrder: Int,
    val groups: List<FixturePatchGroupRef>,
    val stageX: Double? = null,
    val stageY: Double? = null,
    val stageZ: Double? = null,
    val baseYawDeg: Double? = null,
    val basePitchDeg: Double? = null,
    val riggingUuid: String? = null,
    val worldPositionX: Double? = null,
    val worldPositionY: Double? = null,
    val worldPositionZ: Double? = null,
    val beamAngleDeg: Int? = null,
    val gelCode: String? = null,
    val kindOverride: String? = null,
)

@Serializable
data class FixturePatchGroupRef(
    val id: Int,
    val name: String,
)

@Serializable
data class CreatePatchRequest(
    val universe: Int,
    val fixtureTypeKey: String,
    val key: String,
    val name: String,
    val startChannel: Int,
    val address: String? = null,
    val groupName: String? = null,
    val stageX: Double? = null,
    val stageY: Double? = null,
    val stageZ: Double? = null,
    val baseYawDeg: Double? = null,
    val basePitchDeg: Double? = null,
    val riggingUuid: String? = null,
    val beamAngleDeg: Int? = null,
    val gelCode: String? = null,
    val kindOverride: String? = null,
)

/**
 * PUT body keys that are pure patch metadata — present on `fixture_patches`
 * but not consumed by [DbFixtureLoader] when constructing runtime fixtures
 * (the loader reads them into its `PatchData` projection only to surface them
 * via REST). A PUT that only touches these keys can skip the rebuild. Adding a
 * key to this set is only safe if the loader ignores it during fixture
 * instantiation.
 */
private val METADATA_ONLY_PUT_KEYS = setOf(
    "stageX",
    "stageY",
    "stageZ",
    "baseYawDeg",
    "basePitchDeg",
    "riggingUuid",
    "beamAngleDeg",
    "gelCode",
    "kindOverride",
)

// Helpers
private fun DaoFixturePatch.toDto(): FixturePatchDto {
    val typeInfo = FixtureTypeRegistry.typeInfoForKey(fixtureTypeKey)
    val groupRefs = DaoFixtureGroupMember.find { DaoFixtureGroupMembers.fixturePatch eq this@toDto.id }
        .map { FixturePatchGroupRef(id = it.group.id.value, name = it.group.name) }
    val world = resolveWorldPosition(this)
    return FixturePatchDto(
        id = id.value,
        key = key,
        displayName = displayName,
        fixtureTypeKey = fixtureTypeKey,
        startChannel = startChannel,
        channelCount = typeInfo?.channelCount,
        manufacturer = typeInfo?.manufacturer,
        model = typeInfo?.model,
        modeName = typeInfo?.modeName,
        universe = universeConfig.universe,
        subnet = universeConfig.subnet,
        sortOrder = sortOrder,
        groups = groupRefs,
        stageX = stageX,
        stageY = stageY,
        stageZ = stageZ,
        baseYawDeg = baseYawDeg,
        basePitchDeg = basePitchDeg,
        riggingUuid = rigging?.uuid?.toString(),
        worldPositionX = world?.first,
        worldPositionY = world?.second,
        worldPositionZ = world?.third,
        beamAngleDeg = beamAngleDeg,
        gelCode = gelCode,
        kindOverride = kindOverride,
    )
}

/**
 * Compose the patch's stage_x/y/z (offsets in the rigging's local frame) with the
 * rigging's pose to produce world coordinates. When no rigging is set, the offsets
 * are already world coordinates. Returns null if neither rigging nor any of the
 * stage_x/y/z fields are populated.
 *
 * Z-up convention: yaw rotates about Z, pitch about X, roll about Y. Intrinsic
 * order is yaw → pitch → roll, which expands to the matrix product
 * `R_z(yaw) · R_x(pitch) · R_y(roll) · v` (axes Z-X-Y, applied right-to-left).
 * Typical truss rigs use yaw only; the all-null fast path below skips the
 * trig entirely, since most fixtures have an unset pose.
 */
private fun resolveWorldPosition(patch: DaoFixturePatch): Triple<Double, Double, Double>? {
    val rig = patch.rigging
    val ox = patch.stageX
    val oy = patch.stageY
    val oz = patch.stageZ
    if (rig == null) {
        return if (ox == null && oy == null && oz == null) null
        else Triple(ox ?: 0.0, oy ?: 0.0, oz ?: 0.0)
    }
    val rx = rig.positionX ?: 0.0
    val ry = rig.positionY ?: 0.0
    val rz = rig.positionZ ?: 0.0
    val lx = ox ?: 0.0
    val ly = oy ?: 0.0
    val lz = oz ?: 0.0

    // Common case: rigging is just a translated origin (no orientation). Skip trig.
    if (rig.yawDeg == null && rig.pitchDeg == null && rig.rollDeg == null) {
        return Triple(rx + lx, ry + ly, rz + lz)
    }

    val yaw = Math.toRadians(rig.yawDeg ?: 0.0)
    val pitch = Math.toRadians(rig.pitchDeg ?: 0.0)
    val roll = Math.toRadians(rig.rollDeg ?: 0.0)
    val (rollX, rollY, rollZ) = run {
        val cr = cos(roll); val sr = sin(roll)
        Triple(cr * lx + sr * lz, ly, -sr * lx + cr * lz)
    }
    val (pitchX, pitchY, pitchZ) = run {
        val cp = cos(pitch); val sp = sin(pitch)
        Triple(rollX, cp * rollY - sp * rollZ, sp * rollY + cp * rollZ)
    }
    val (yawX, yawY, yawZ) = run {
        val cy = cos(yaw); val sy = sin(yaw)
        Triple(cy * pitchX - sy * pitchY, sy * pitchX + cy * pitchY, pitchZ)
    }
    return Triple(rx + yawX, ry + yawY, rz + yawZ)
}

private fun resolveRiggingForProject(project: DaoProject, uuidStr: String): DaoRigging? {
    val parsedUuid = runCatching { UUID.fromString(uuidStr) }.getOrNull() ?: return null
    val rigging = DaoRigging.find { DaoRiggings.uuid eq parsedUuid }.firstOrNull() ?: return null
    if (rigging.project.id != project.id) return null
    return rigging
}

/**
 * Range-check the numeric stage-metadata fields, returning the first error message or null.
 * Coordinates are FOH-relative metres (see `docs/fixtures-engineering.md`).
 *
 * Coordinate bounds are intentionally loose (±500 m) — large enough for any real venue,
 * tight enough to catch unit mistakes (mm, pixels). String-field normalisation lives in
 * [normaliseRiggingPosition] / [normaliseGelCode].
 */
private fun validateStageMetadata(
    stageX: Double?,
    stageY: Double?,
    stageZ: Double?,
    baseYawDeg: Double?,
    basePitchDeg: Double?,
    beamAngleDeg: Int?,
): String? {
    checkStageCoord("stageX", stageX)?.let { return it }
    checkStageCoord("stageY", stageY)?.let { return it }
    checkStageCoord("stageZ", stageZ)?.let { return it }
    // Yaw/pitch allow a full ±360°/±180° range so a UI can normalise either way without
    // tripping a 400. Renderers should reduce mod 360.
    checkAngle("baseYawDeg", baseYawDeg, -360.0, 360.0)?.let { return it }
    checkAngle("basePitchDeg", basePitchDeg, -180.0, 180.0)?.let { return it }
    if (beamAngleDeg != null && (beamAngleDeg < 2 || beamAngleDeg > 120)) {
        return "beamAngleDeg must be between 2 and 120"
    }
    return null
}

private fun normaliseGelCode(raw: String?): String? {
    val trimmed = raw?.trim() ?: return null
    if (trimmed.isEmpty()) return null
    return trimmed.take(20)
}

/**
 * Validate a `kindOverride` request value: must be either `null`/empty (clear) or
 * the name of a [FixtureKind]. Throws [IllegalArgumentException] for anything else
 * so the caller can map to a 400 response.
 */
private fun normaliseKindOverride(raw: String?): String? {
    val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return try {
        FixtureKind.valueOf(trimmed).name
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException(
            "kindOverride must be one of ${FixtureKind.entries.joinToString(", ") { it.name }}"
        )
    }
}

/**
 * Find or create a fixture group by name within a project.
 */
private fun findOrCreateGroup(project: DaoProject, groupName: String): DaoFixtureGroup {
    return DaoFixtureGroup.find {
        (DaoFixtureGroups.project eq project.id) and (DaoFixtureGroups.name eq groupName)
    }.firstOrNull() ?: DaoFixtureGroup.new {
        this.project = project
        this.name = groupName
    }
}

/**
 * Add a patch to a group, removing from any previous group first.
 */
private fun assignPatchToGroup(patch: DaoFixturePatch, group: DaoFixtureGroup) {
    // Remove from current group(s)
    DaoFixtureGroupMember.find { DaoFixtureGroupMembers.fixturePatch eq patch.id }
        .forEach { it.delete() }

    val maxOrder = group.members.maxOfOrNull { it.sortOrder } ?: -1
    DaoFixtureGroupMember.new {
        this.group = group
        this.fixturePatch = patch
        this.sortOrder = maxOrder + 1
    }
}

/**
 * Remove a patch from all groups.
 */
private fun removePatchFromGroups(patch: DaoFixturePatch) {
    DaoFixtureGroupMember.find { DaoFixtureGroupMembers.fixturePatch eq patch.id }
        .forEach { it.delete() }
}
