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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.me.cormack.lighting7.fixture.FixtureKind
import uk.me.cormack.lighting7.fixture.FixtureTypeRegistry
import uk.me.cormack.lighting7.fixture.lantern.LanternFocus
import uk.me.cormack.lighting7.fixture.lantern.ShutterBlade
import uk.me.cormack.lighting7.fixture.lantern.focus
import uk.me.cormack.lighting7.fixture.media.FittedMedia
import uk.me.cormack.lighting7.fixture.media.fittedMedia
import uk.me.cormack.lighting7.models.*
import uk.me.cormack.lighting7.show.DbFixtureLoader
import uk.me.cormack.lighting7.show.Fixtures
import uk.me.cormack.lighting7.state.State
import java.util.UUID

internal fun Route.routeApiRestProjectPatches(state: State) {
    // GET /{projectId}/patches - List all patches for a project
    get<ProjectPatchesResource> { resource ->
        withProject(state, resource.projectId) { project ->
            val patches = transaction(state.database) {
                val rows = DaoFixturePatch.find { DaoFixturePatches.project eq project.id }
                    .orderBy(DaoFixturePatches.sortOrder to SortOrder.ASC)
                    .toList()
                val placementsByPatch = extraPlacementsByPatch(rows.map { it.id })
                rows.map { it.toDto(placementsByPatch[it.id.value].orEmpty()) }
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
                lengthM = request.lengthM,
                baseRollDeg = request.baseRollDeg,
            )
            if (stageError != null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(stageError))
                return@withProject
            }
            if (request.lengthM != null) {
                fixedLengthRefusal(typeInfo.typeKey)?.let {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(it))
                    return@withProject
                }
            }
            headNumberRangeError(request.headNumber)?.let {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(it))
                return@withProject
            }
            val normalisedGelCode = normaliseGelCode(request.gelCode)
            val normalisedKindOverride = try {
                normaliseKindOverride(request.kindOverride)
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Invalid kindOverride"))
                return@withProject
            }
            val requestedFocus = request.focus()
            val focusRangeProblems = requestedFocus.rangeProblems()
            if (focusRangeProblems.isNotEmpty()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(focusRangeProblems.joinToString("; ")))
                return@withProject
            }
            // The kind is derived from a lantern named, and the focus checked against the type.
            val focusWrite = resolvePatchFocus(
                typeKey = typeInfo.typeKey,
                stored = LanternFocus(),
                storedKindOverride = null,
                sent = LanternFocus.KEYS.toSet(),
                parsed = requestedFocus,
                kindOverrideSent = true,
                kindOverride = normalisedKindOverride,
            ).getOrElse {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Invalid focus"))
                return@withProject
            }
            // Fitted media: its shape, then the type's loadable settings, every problem at once.
            val requestedMedia = FittedMedia.parse(request.media).getOrElse {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Invalid media"))
                return@withProject
            }
            patchMediaRefusal(typeInfo.typeKey, requestedMedia, patchMediaSent = true, placements = null)?.let {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(it))
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
                request.headNumber?.let { n ->
                    headNumberHolder(project, n, exceptPatchId = null)?.let {
                        return@transaction Pair<FixturePatchDto?, String?>(null, headNumberTaken(n, it))
                    }
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
                    this.headNumber = request.headNumber
                    this.startChannel = request.startChannel
                    this.sortOrder = maxSortOrder + 1
                    this.stageX = request.stageX
                    this.stageY = request.stageY
                    this.stageZ = request.stageZ
                    this.baseYawDeg = request.baseYawDeg
                    this.basePitchDeg = request.basePitchDeg
                    this.baseRollDeg = request.baseRollDeg
                    this.beamAngleDeg = request.beamAngleDeg
                    this.gelCode = normalisedGelCode
                    this.kindOverride = focusWrite.kindOverride
                    this.lengthM = request.lengthM
                    this.stageHidden = request.stageHidden
                    this.infrastructure = request.infrastructure
                }
                if (!focusWrite.focus.isEmpty) patch.focus = focusWrite.focus
                if (requestedMedia != null) patch.fittedMedia = requestedMedia

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
                DbFixtureLoader.loadFixtures(project.id.value, state.show.fixtures, state.database, parkSource = state.show.outputSource)
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
                lengthM = body["lengthM"].nullableDouble(),
                baseRollDeg = body["baseRollDeg"].nullableDouble(),
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

            val placementInputs: List<PlacementInput>? = if ("extraPlacements" in body) {
                parseExtraPlacements(body["extraPlacements"]).getOrElse {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Invalid extraPlacements"))
                    return@withProject
                }
            } else null

            val headNumber: Int? = if ("headNumber" in body) {
                parseHeadNumber(body["headNumber"]).getOrElse {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Invalid headNumber"))
                    return@withProject
                }
            } else null

            val parsedFocus = LanternFocus.parse(body).getOrElse {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Invalid focus"))
                return@withProject
            }
            // Absent keeps the stored media, null clears it — read only when sent.
            val parsedMedia: FittedMedia? = if ("media" in body) {
                FittedMedia.parse(body["media"]).getOrElse {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Invalid media"))
                    return@withProject
                }
            } else null

            var sweptCellTiles = 0
            var infrastructureFlipped = false
            // Set when the refusal is the request's fault rather than a conflict with stored state.
            var refusedAsBadRequest = false
            val result = transaction(state.database) {
                val patch = DaoFixturePatch.findById(resource.patchId)
                    ?: return@transaction Pair<FixturePatchDto?, String?>(null, "Patch not found")

                if (patch.project.id != project.id) {
                    return@transaction Pair<FixturePatchDto?, String?>(null, "Patch not found")
                }

                // A length on a fixed-length type, the patch's own or a segment's. Checked here, where
                // the patch's type is known, and before the first write below for the rigging's reason.
                val setsLength = body["lengthM"].nullableDouble() != null ||
                    placementInputs.orEmpty().any { it.lengthM != null }
                if (setsLength) {
                    fixedLengthRefusal(patch.fixtureTypeKey)?.let {
                        refusedAsBadRequest = true
                        return@transaction Pair<FixturePatchDto?, String?>(null, it)
                    }
                }

                // The lantern, its focus and the kind it derives, as this write leaves them — and every
                // placement's focus against them. The request's fault when refused, so a 400; before
                // the first write below for the rigging's reason.
                val focusWrite = resolvePatchFocus(
                    patch.fixtureTypeKey, patch.focus, patch.kindOverride, body, parsedFocus, normalisedKindOverride,
                ).getOrElse {
                    refusedAsBadRequest = true
                    return@transaction Pair<FixturePatchDto?, String?>(null, it.message)
                }
                placementInputs?.let { inputs ->
                    placementFocusRefusal(patch.fixtureTypeKey, focusWrite.kindOverride, focusWrite.focus.lanternType, inputs)
                }?.let {
                    refusedAsBadRequest = true
                    return@transaction Pair<FixturePatchDto?, String?>(null, it)
                }
                // The patch's media and every placement's, against the type's loadable settings.
                patchMediaRefusal(patch.fixtureTypeKey, parsedMedia, "media" in body, placementInputs)?.let {
                    refusedAsBadRequest = true
                    return@transaction Pair<FixturePatchDto?, String?>(null, it)
                }

                // Another head's number: a conflict with stored state, so a 409. Before the first
                // write below for the rigging's reason — a normal return commits.
                if (headNumber != null) {
                    headNumberHolder(project, headNumber, exceptPatchId = patch.id.value)?.let {
                        return@transaction Pair<FixturePatchDto?, String?>(null, headNumberTaken(headNumber, it))
                    }
                }

                // Resolved before the first write below, so an unknown rigging refuses the whole
                // PUT rather than committing the fields above it (a normal return commits).
                val placementRiggings = placementInputs?.let { inputs ->
                    resolvePlacementRiggings(project, inputs).getOrElse {
                        return@transaction Pair<FixturePatchDto?, String?>(null, it.message)
                    }
                }

                body["displayName"].nullableString()?.let { patch.displayName = it }
                if ("headNumber" in body) patch.headNumber = headNumber
                body["key"].nullableString()?.let { newKey ->
                    val existing = DaoFixturePatch.find {
                        (DaoFixturePatches.project eq project.id) and (DaoFixturePatches.key eq newKey)
                    }.firstOrNull()
                    if (existing != null && existing.id != patch.id) {
                        return@transaction Pair<FixturePatchDto?, String?>(null, "Key '$newKey' already exists")
                    }
                    // A cell tile on the busk rig stores an element key that embeds this key, and
                    // the desk never parses one — so a rename sweeps the patch's cell tiles rather
                    // than leaving keys the next rig write would refuse (`deleteBuskRigCellTilesOf`).
                    if (newKey != patch.key) sweptCellTiles += deleteBuskRigCellTilesOf(patch.id.value)
                    patch.key = newKey
                }
                body["startChannel"].nullableInt()?.let { patch.startChannel = it }

                if ("stageX" in body) patch.stageX = body["stageX"].nullableDouble()
                if ("stageY" in body) patch.stageY = body["stageY"].nullableDouble()
                if ("stageZ" in body) patch.stageZ = body["stageZ"].nullableDouble()
                if ("baseYawDeg" in body) patch.baseYawDeg = body["baseYawDeg"].nullableDouble()
                if ("basePitchDeg" in body) patch.basePitchDeg = body["basePitchDeg"].nullableDouble()
                if ("baseRollDeg" in body) patch.baseRollDeg = body["baseRollDeg"].nullableDouble()
                if ("beamAngleDeg" in body) patch.beamAngleDeg = body["beamAngleDeg"].nullableInt()
                if ("lengthM" in body) patch.lengthM = body["lengthM"].nullableDouble()
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
                if ("kindOverride" in body || body.keys.any { it in LanternFocus.KEYS }) {
                    patch.kindOverride = focusWrite.kindOverride
                    patch.focus = focusWrite.focus
                    if (placementInputs == null) {
                        clearStaleInheritedZooms(patch.fixtureTypeKey, focusWrite.kindOverride, focusWrite.focus.lanternType, extraPlacementsOf(patch))
                    }
                }
                if ("media" in body) patch.fittedMedia = parsedMedia
                // Non-nullable column: an explicit JSON null is read as "show it".
                if ("stageHidden" in body) {
                    patch.stageHidden = body["stageHidden"].nullableBoolean() ?: false
                }
                // Also non-nullable. Metadata to the loader (it builds no fixture differently), but the
                // flag rides the live fixture list (`GET /fixtures`), so a flip must also announce
                // `fixturesChanged` — see [PUT_KEYS_WITHOUT_REBUILD] and the tail of this handler.
                if ("infrastructure" in body) {
                    val next = body["infrastructure"].nullableBoolean() ?: false
                    if (next != patch.infrastructure) infrastructureFlipped = true
                    patch.infrastructure = next
                }
                if (placementInputs != null && placementRiggings != null) {
                    applyExtraPlacements(patch, placementInputs, placementRiggings)
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
                val code = when {
                    error == "Patch not found" -> HttpStatusCode.NotFound
                    refusedAsBadRequest -> HttpStatusCode.BadRequest
                    else -> HttpStatusCode.Conflict
                }
                call.respond(code, ErrorResponse(error))
                return@withProject
            }

            val touchedRebuildKey = body.keys.any { it !in PUT_KEYS_WITHOUT_REBUILD }
            if (touchedRebuildKey && state.isCurrentProject(project)) {
                DbFixtureLoader.loadFixtures(project.id.value, state.show.fixtures, state.database, parkSource = state.show.outputSource)
            } else if (state.isCurrentProject(project)) {
                // Metadata-only edits skip the rebuild, so refresh the cache directly.
                state.show.fixtures.setPatchMetadata(
                    patchDto!!.key,
                    Fixtures.FixturePatchMetadata(
                        gelCode = patchDto.gelCode, infrastructure = patchDto.infrastructure, media = patchDto.media,
                    ),
                )
                // A rebuild would have announced this itself; without one, the fixture list's
                // contents still changed, and every window has to drop (or regain) the fixture.
                if (infrastructureFlipped) state.show.fixtures.announceFixturesChanged()
            }
            state.show.fixtures.patchListChanged()
            if (sweptCellTiles > 0) state.show.fixtures.buskRigChanged()

            call.respond(patchDto!!)
        }
    }

    // PUT /{projectId}/patches/placements — bulk placement update.
    //
    // One transaction, one broadcast. The frontend's align/distribute/nudge and
    // array-along-truss operations move many fixtures as a single user action; the
    // per-patch PUT above would mean N requests, N `patchListChanged` broadcasts
    // and 2N client refetches for one click.
    //
    // **Every key must be in [METADATA_ONLY_PUT_KEYS].** That single rule is what
    // makes this route both safe and fast:
    //   - it guarantees `DbFixtureLoader.loadFixtures` is never needed, *by
    //     construction* rather than by someone remembering to check;
    //   - it keeps `key`, `startChannel` and group membership — whose uniqueness
    //     and channel-overlap validation is inherently per-patch — out of the bulk
    //     path entirely. `headNumber` is the one unique key it does carry, and it is
    //     checked against the batch's final state rather than per entry, which is
    //     what makes a renumber (the patch list's Set over N heads) one atomic write.
    // A body carrying anything else is a 400, not a silent partial apply.
    put<ProjectPatchPlacementsResource> { resource ->
        withProject(state, resource.parent.projectId) { project ->
            val request = call.receive<BulkPlacementRequest>()

            if (request.updates.isEmpty()) {
                call.respond(BulkPlacementResponse(updated = emptyList(), failed = emptyList()))
                return@withProject
            }
            if (request.updates.size > MAX_BULK_PLACEMENTS) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("At most $MAX_BULK_PLACEMENTS placements per request"),
                )
                return@withProject
            }

            // Pre-validate everything OUTSIDE the transaction, same rationale as the
            // single PUT: a bad request should 400 rather than roll back a write.
            val prepared = mutableListOf<Pair<Int, JsonObject>>()
            val failures = mutableListOf<BulkPlacementFailure>()
            val placementInputsById = mutableMapOf<Int, List<PlacementInput>>()
            // Every entry's focus fields, range-checked; the entry's keys say which it carries.
            val focusById = mutableMapOf<Int, LanternFocus>()
            // Only the entries that carry the key — a null value is an explicit clear.
            val headNumbersById = mutableMapOf<Int, Int?>()
            // Likewise: only the entries that carry `media`, a null clearing it.
            val mediaById = mutableMapOf<Int, FittedMedia?>()
            for (entry in request.updates) {
                val patchId = entry["patchId"].nullableInt()
                if (patchId == null) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("Every entry must carry an integer patchId"),
                    )
                    return@withProject
                }
                val disallowed = entry.keys.filter { it != "patchId" && it !in METADATA_ONLY_PUT_KEYS }
                if (disallowed.isNotEmpty()) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(
                            "Keys not allowed on the bulk placement route: ${disallowed.sorted().joinToString(", ")}",
                        ),
                    )
                    return@withProject
                }
                val stageError = validateStageMetadata(
                    stageX = entry["stageX"].nullableDouble(),
                    stageY = entry["stageY"].nullableDouble(),
                    stageZ = entry["stageZ"].nullableDouble(),
                    baseYawDeg = entry["baseYawDeg"].nullableDouble(),
                    basePitchDeg = entry["basePitchDeg"].nullableDouble(),
                    beamAngleDeg = entry["beamAngleDeg"].nullableInt(),
                    lengthM = entry["lengthM"].nullableDouble(),
                    baseRollDeg = entry["baseRollDeg"].nullableDouble(),
                )
                if (stageError != null) {
                    if (request.atomic) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("patch $patchId: $stageError"))
                        return@withProject
                    }
                    failures.add(BulkPlacementFailure(patchId, stageError))
                    continue
                }
                if ("headNumber" in entry) {
                    val parsed = parseHeadNumber(entry["headNumber"])
                    val headError = parsed.exceptionOrNull()?.message
                    if (headError != null) {
                        if (request.atomic) {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("patch $patchId: $headError"))
                            return@withProject
                        }
                        failures.add(BulkPlacementFailure(patchId, headError))
                        continue
                    }
                    headNumbersById[patchId] = parsed.getOrThrow()
                }
                val focusParsed = LanternFocus.parse(entry)
                val focusError = focusParsed.exceptionOrNull()?.message
                if (focusError != null) {
                    if (request.atomic) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("patch $patchId: $focusError"))
                        return@withProject
                    }
                    failures.add(BulkPlacementFailure(patchId, focusError))
                    continue
                }
                focusById[patchId] = focusParsed.getOrThrow()
                if ("media" in entry) {
                    val parsed = FittedMedia.parse(entry["media"])
                    val mediaError = parsed.exceptionOrNull()?.message
                    if (mediaError != null) {
                        if (request.atomic) {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("patch $patchId: $mediaError"))
                            return@withProject
                        }
                        failures.add(BulkPlacementFailure(patchId, mediaError))
                        continue
                    }
                    mediaById[patchId] = parsed.getOrThrow()
                }
                if ("extraPlacements" in entry) {
                    val parsed = parseExtraPlacements(entry["extraPlacements"])
                    val placementError = parsed.exceptionOrNull()?.message
                    if (placementError != null) {
                        if (request.atomic) {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("patch $patchId: $placementError"))
                            return@withProject
                        }
                        failures.add(BulkPlacementFailure(patchId, placementError))
                        continue
                    }
                    placementInputsById[patchId] = parsed.getOrThrow()
                }
                prepared.add(patchId to entry)
            }

            val outcome = transaction(state.database) {
                // One query for every patch, indexed by id — not findById in a loop.
                val ids = prepared.map { it.first }
                val byId = DaoFixturePatch.find {
                    (DaoFixturePatches.project eq project.id) and (DaoFixturePatches.id inList ids)
                }.associateBy { it.id.value }

                // Resolve each DISTINCT rigging once; resolveRiggingForProject does a
                // find() per call, and a 40-fixture hang would repeat it 40 times. That
                // includes the riggings a paired dimmer's other lanterns hang on.
                val riggingUuids = (
                    prepared.mapNotNull { (_, e) ->
                        if ("riggingUuid" in e) e["riggingUuid"].nullableString() else null
                    } + placementInputsById.values.flatten().mapNotNull { it.riggingUuid }
                ).distinct()
                val riggingByUuid = riggingUuids.associateWith { resolveRiggingForProject(project, it) }

                // SECOND validation pass, and it has to come before the FIRST mutation.
                //
                // The checks below can only be made once the rows have been read, so
                // they can't join the pre-transaction pass. They must still all run
                // before anything is assigned, because `return@transaction` is a NORMAL
                // return and Exposed commits on a normal return — aborting from inside
                // the mutation loop would flush every entry already assigned while the
                // response said 400 and no `patchListChanged()` was broadcast. The
                // client rolls its optimistic write back on a 400, so that half-applied
                // batch would sit in the database unseen by every client until some
                // unrelated refetch surfaced it.
                //
                // Validating first means an atomic abort returns with a clean entity
                // cache, so the commit that follows writes nothing.
                val focusWrites = mutableMapOf<Int, PatchFocusWrite>()
                val placementRiggingsById = mutableMapOf<Int, Map<String, DaoRigging>>()
                val fatal = mutableListOf<BulkPlacementFailure>()
                for ((patchId, entry) in prepared) {
                    val target = byId[patchId]
                    if (target == null) {
                        fatal.add(BulkPlacementFailure(patchId, "Patch not found in this project"))
                        continue
                    }
                    val setsLength = entry["lengthM"].nullableDouble() != null ||
                        placementInputsById[patchId].orEmpty().any { it.lengthM != null }
                    if (setsLength) {
                        val refusal = fixedLengthRefusal(target.fixtureTypeKey)
                        if (refusal != null) {
                            fatal.add(BulkPlacementFailure(patchId, refusal))
                            continue
                        }
                    }
                    if ("riggingUuid" in entry) {
                        val uuidStr = entry["riggingUuid"].nullableString()
                        if (uuidStr != null && riggingByUuid[uuidStr] == null) {
                            fatal.add(BulkPlacementFailure(patchId, "Rigging $uuidStr not found"))
                            continue
                        }
                    }
                    val normalisedKind = if ("kindOverride" in entry) {
                        try {
                            normaliseKindOverride(entry["kindOverride"].nullableString())
                        } catch (e: IllegalArgumentException) {
                            fatal.add(BulkPlacementFailure(patchId, e.message ?: "Invalid kindOverride"))
                            continue
                        }
                    } else null
                    val focusWrite = resolvePatchFocus(
                        target.fixtureTypeKey, target.focus, target.kindOverride, entry,
                        focusById[patchId] ?: LanternFocus(), normalisedKind,
                    ).getOrElse {
                        fatal.add(BulkPlacementFailure(patchId, it.message ?: "Invalid focus"))
                        continue
                    }
                    placementInputsById[patchId]?.let { inputs ->
                        placementFocusRefusal(target.fixtureTypeKey, focusWrite.kindOverride, focusWrite.focus.lanternType, inputs)
                    }?.let {
                        fatal.add(BulkPlacementFailure(patchId, it))
                        continue
                    }
                    patchMediaRefusal(target.fixtureTypeKey, mediaById[patchId], patchId in mediaById, placementInputsById[patchId])?.let {
                        fatal.add(BulkPlacementFailure(patchId, it))
                        continue
                    }
                    focusWrites[patchId] = focusWrite
                    placementInputsById[patchId]?.let { inputs ->
                        val resolved = placementRiggingsFrom(riggingByUuid, inputs)
                        val placementError = resolved.exceptionOrNull()?.message
                        if (placementError != null) {
                            fatal.add(BulkPlacementFailure(patchId, placementError))
                            continue
                        }
                        placementRiggingsById[patchId] = resolved.getOrThrow()
                    }
                }
                // Head numbers are unique in the project, so they are checked against the rig as it
                // will stand once the whole batch lands — which is what lets one request swap two
                // heads' numbers, where two single PUTs would each refuse the other's.
                if (headNumbersById.isNotEmpty()) {
                    // `failures` holds the pre-pass's refusals (non-atomic only): an entry whose
                    // number parsed but whose placements did not is not written either.
                    val refused = (fatal + failures).map { it.patchId }.toSet()
                    fatal += headNumberClashes(project, headNumbersById, rejected = refused)
                }
                if (request.atomic && fatal.isNotEmpty()) {
                    return@transaction BulkOutcome(null, fatal.first().error, emptyList(), emptyList())
                }
                failures.addAll(fatal)
                val rejected = fatal.map { it.patchId }.toSet()

                val written = mutableListOf<DaoFixturePatch>()
                val warnings = mutableListOf<String>()
                for ((patchId, entry) in prepared) {
                    if (patchId in rejected) continue
                    val patch = byId[patchId] ?: continue

                    if ("riggingUuid" in entry) {
                        val uuidStr = entry["riggingUuid"].nullableString()
                        patch.rigging = if (uuidStr == null) null else riggingByUuid[uuidStr]
                    }
                    if ("stageX" in entry) patch.stageX = entry["stageX"].nullableDouble()
                    if ("stageY" in entry) patch.stageY = entry["stageY"].nullableDouble()
                    if ("stageZ" in entry) patch.stageZ = entry["stageZ"].nullableDouble()
                    if ("baseYawDeg" in entry) patch.baseYawDeg = entry["baseYawDeg"].nullableDouble()
                    if ("basePitchDeg" in entry) patch.basePitchDeg = entry["basePitchDeg"].nullableDouble()
                    if ("baseRollDeg" in entry) patch.baseRollDeg = entry["baseRollDeg"].nullableDouble()
                    if ("beamAngleDeg" in entry) patch.beamAngleDeg = entry["beamAngleDeg"].nullableInt()
                    if ("lengthM" in entry) patch.lengthM = entry["lengthM"].nullableDouble()
                    if (patchId in headNumbersById) patch.headNumber = headNumbersById[patchId]
                    if ("gelCode" in entry) patch.gelCode = normaliseGelCode(entry["gelCode"].nullableString())
                    // Already resolved (and validated) in the pass above: the kind a lantern derives, too.
                    if ("kindOverride" in entry || entry.keys.any { it in LanternFocus.KEYS }) {
                        focusWrites[patchId]?.let { w ->
                            patch.kindOverride = w.kindOverride
                            patch.focus = w.focus
                            if (patchId !in placementInputsById) {
                                clearStaleInheritedZooms(patch.fixtureTypeKey, w.kindOverride, w.focus.lanternType, extraPlacementsOf(patch))
                            }
                        }
                    }
                    if ("stageHidden" in entry) {
                        patch.stageHidden = entry["stageHidden"].nullableBoolean() ?: false
                    }
                    if (patchId in mediaById) patch.fittedMedia = mediaById[patchId]
                    placementInputsById[patchId]?.let { inputs ->
                        applyExtraPlacements(patch, inputs, placementRiggingsById.getValue(patchId))
                    }

                    // The server is the only party that authoritatively knows the bar's
                    // length at write time, so it's the only place an off-the-end
                    // placement can be caught. Non-fatal: report, never clamp — silently
                    // moving a fixture the user positioned is worse than telling them.
                    val rig = patch.rigging
                    val localX = patch.stageX
                    if (rig != null && localX != null) {
                        val half = (rig.lengthM ?: 0.0) / 2.0
                        if (half > 0.0 && kotlin.math.abs(localX) > half + 1e-6) {
                            warnings.add(
                                "${patch.key}: ${"%.2f".format(localX)} m is past the end of ${rig.name} (±${"%.2f".format(half)} m)",
                            )
                        }
                    }

                    written.add(patch)
                }
                // Every written patch's lanterns in one query, read after the writes above (a
                // find flushes them first). Checked whether or not this entry touched them, as
                // the fixture's own placement is: a lantern past the end of its bar stays
                // reported however the patch is next edited.
                val placementsByPatch = extraPlacementsByPatch(written.map { it.id })
                val updated = written.map { patch ->
                    val placements = placementsByPatch[patch.id.value].orEmpty()
                    placements.mapNotNullTo(warnings) { it.offTheEndWarning(patch.key) }
                    patch.toDto(placements)
                }
                BulkOutcome(updated, null, failures.toList(), warnings)
            }

            if (outcome.error != null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(outcome.error))
                return@withProject
            }

            val updated = outcome.updated ?: emptyList()
            if (state.isCurrentProject(project)) {
                // The key allowlist guarantees the fixture registry is untouched, so
                // only the metadata cache needs refreshing — never loadFixtures.
                for (dto in updated) {
                    state.show.fixtures.setPatchMetadata(
                        dto.key,
                        Fixtures.FixturePatchMetadata(gelCode = dto.gelCode, infrastructure = dto.infrastructure, media = dto.media),
                    )
                }
            }
            // Exactly once, after the commit — the whole point of this route.
            state.show.fixtures.patchListChanged()

            call.respond(
                BulkPlacementResponse(
                    updated = updated,
                    failed = outcome.failures,
                    warnings = outcome.warnings,
                ),
            )
        }
    }

    // DELETE /{projectId}/patches/{patchId} - Delete a patch
    delete<ProjectPatchResource> { resource ->
        withProject(state, resource.parent.projectId) { project ->
            val deleted = transaction(state.database) {
                val patch = DaoFixturePatch.findById(resource.patchId) ?: return@transaction null
                if (patch.project.id != project.id) return@transaction null

                // Its busk rig tiles go with it — a tile is an enrichment, never a guard, and the
                // FK has no cascade (`DaoBuskRigTiles`).
                val sweptTiles = deleteBuskRigTilesReferencing(patchId = patch.id.value)

                // Remove from any groups first
                DaoFixtureGroupMember.find { DaoFixtureGroupMembers.fixturePatch eq patch.id }
                    .forEach { it.delete() }
                deletePlacementsOf(patch)
                // A cue event fires one of this patch's tubes: it goes with the patch, as a busk tile
                // does — swept, never a guard. A cue changes; it is told by `cueListChanged` below.
                val sweptEvents = deleteCueEventsForPatches(listOf(patch.id))

                patch.delete()
                sweptTiles to sweptEvents
            }

            if (deleted == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Patch not found"))
                return@withProject
            }
            if (deleted.first > 0) state.show.fixtures.buskRigChanged()
            if (deleted.second > 0) state.show.fixtures.cueListChanged()

            if (state.isCurrentProject(project)) {
                DbFixtureLoader.loadFixtures(project.id.value, state.show.fixtures, state.database, parkSource = state.show.outputSource)
            }
            state.show.fixtures.patchListChanged()

            call.respond(HttpStatusCode.NoContent)
        }
    }
}

// Resources
@Resource("/{projectId}/patches")
data class ProjectPatchesResource(val projectId: String)

/**
 * Bulk placement sub-collection.
 *
 * Declared BEFORE [ProjectPatchResource] so the literal `/placements` segment is
 * matched before the `{patchId}` capture — `patchId` is typed `Int` so it could
 * not actually swallow it, but relying on that would be fragile.
 */
@Resource("/placements")
data class ProjectPatchPlacementsResource(val parent: ProjectPatchesResource)

@Resource("/{patchId}")
data class ProjectPatchResource(val parent: ProjectPatchesResource, val patchId: Int)

// DTOs

/** Upper bound on one bulk request, so a runaway client can't hold a transaction open. */
private const val MAX_BULK_PLACEMENTS = 500

@Serializable
data class BulkPlacementRequest(
    /**
     * One entry per patch. Each MUST carry `patchId`; every other key follows the
     * same convention as `PUT /patches/{id}` — absent means unchanged, an explicit
     * JSON null clears the value.
     *
     * Typed as raw [JsonObject] rather than a DTO precisely to inherit that
     * tri-state behaviour: a typed DTO can't distinguish "absent" from "null"
     * without a companion `setStageX: Boolean` beside every field.
     */
    val updates: List<JsonObject>,
    /** Reject the whole batch on the first bad entry (default), or apply the good ones. */
    val atomic: Boolean = true,
)

@Serializable
data class BulkPlacementFailure(val patchId: Int, val error: String)

@Serializable
data class BulkPlacementResponse(
    val updated: List<FixturePatchDto>,
    val failed: List<BulkPlacementFailure>,
    /** Non-fatal notices, e.g. a fixture placed past the end of its truss. */
    val warnings: List<String> = emptyList(),
)

/** Internal carrier so the transaction block can report a fatal error or a result. */
private data class BulkOutcome(
    val updated: List<FixturePatchDto>?,
    val error: String?,
    val failures: List<BulkPlacementFailure>,
    val warnings: List<String>,
)

@Serializable
data class FixturePatchDto(
    val id: Int,
    val key: String,
    val displayName: String,
    /** The operator's head number, unique in the project; null when unnumbered. */
    val headNumber: Int? = null,
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
    /** Body roll about its local Z, applied before pitch and yaw — what stands a strip on end; null is 0. */
    val baseRollDeg: Double? = null,
    val riggingUuid: String? = null,
    val beamAngleDeg: Int? = null,
    val gelCode: String? = null,
    val kindOverride: String? = null,
    /** The unit's own length in metres, for a type that takes one (`acceptsLength`); null is the
     *  type's default. */
    val lengthM: Double? = null,
    val stageHidden: Boolean = false,
    /** Infrastructure, not a lighting fixture: hidden everywhere but the Patches and Channels views. */
    val infrastructure: Boolean = false,
    /**
     * The lantern, by its id in `GET /lanterns`, for a type that takes one (`acceptsLantern`); null
     * is the library's default for the kind. With one named, [kindOverride] is the lantern's kind.
     * This and the six after it are the unit's **focus data** — see `docs/fixtures-engineering.md`
     * §"Lanterns and focus".
     */
    val lanternType: String? = null,
    /** The field it is zoomed to, within the lantern's range; null is the lantern's default. */
    val zoomDeg: Double? = null,
    /** A PAR's lamp turn, which turns its oval; null is 0. */
    val lampRotationDeg: Double? = null,
    /** Four blades — top, bottom, left, right — or null for all out. */
    val shutters: List<ShutterBlade>? = null,
    val gateRotationDeg: Double? = null,
    /** Open fraction, 1 open; null is open. */
    val iris: Double? = null,
    /** Sharp 0 to soft 1; null is the lantern's own edge. */
    val focusSoftness: Double? = null,
    /**
     * The unit's **fitted media** (fixture optics plan D6): what is loaded in its loadable settings —
     * `{slots: {<property>: {<option>: {gel?, gobo?}}}}`, naming only the options that differ from
     * the type's stock. Null is the stock everywhere. Each extra placement carries its own, layered
     * over this option by option. See `docs/fixtures-engineering.md` §"Fitted media".
     */
    val media: FittedMedia? = null,
    /**
     * The other places this fixture hangs — a paired dimmer's second lantern, SL beside SR.
     * One fixture to control, several to draw. In order; empty for almost every patch.
     */
    val extraPlacements: List<PatchPlacementDto> = emptyList(),
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
    /** Unique in the project when set ([MIN_HEAD_NUMBER]..[MAX_HEAD_NUMBER]); a taken one is a 409. */
    val headNumber: Int? = null,
    val address: String? = null,
    val groupName: String? = null,
    val stageX: Double? = null,
    val stageY: Double? = null,
    val stageZ: Double? = null,
    val baseYawDeg: Double? = null,
    val basePitchDeg: Double? = null,
    /** Body roll about its local Z, applied before pitch and yaw — what stands a strip on end; null is 0. */
    val baseRollDeg: Double? = null,
    val riggingUuid: String? = null,
    val beamAngleDeg: Int? = null,
    val gelCode: String? = null,
    val kindOverride: String? = null,
    /** Only for a type that takes one (`acceptsLength`); refused with a 400 otherwise. */
    val lengthM: Double? = null,
    val stageHidden: Boolean = false,
    /** Infrastructure, not a lighting fixture: hidden everywhere but the Patches and Channels views. */
    val infrastructure: Boolean = false,
    /** Only for a type that takes a lantern (`acceptsLantern`); see [FixturePatchDto.lanternType]. */
    val lanternType: String? = null,
    val zoomDeg: Double? = null,
    val lampRotationDeg: Double? = null,
    val shutters: List<ShutterBlade>? = null,
    val gateRotationDeg: Double? = null,
    val iris: Double? = null,
    val focusSoftness: Double? = null,
    /** Only for a type with loadable settings; see [FixturePatchDto.media]. Raw JSON so it is
     *  checked by the same parser as `PUT`'s, every problem at once. */
    val media: JsonElement? = null,
) {
    fun focus() = LanternFocus(
        lanternType?.trim()?.takeIf { it.isNotEmpty() }, zoomDeg, lampRotationDeg, shutters, gateRotationDeg, iris, focusSoftness,
    )
}

/**
 * PUT body keys that are pure patch metadata — present on `fixture_patches`
 * but not consumed by [DbFixtureLoader] when constructing runtime fixtures
 * (the loader reads them into its `PatchData` projection only to surface them
 * via REST). A PUT that only touches these keys can skip the rebuild. Adding a
 * key to this set is only safe if the loader ignores it during fixture
 * instantiation.
 */
internal val METADATA_ONLY_PUT_KEYS = setOf(
    "stageX",
    "stageY",
    "stageZ",
    "baseYawDeg",
    "basePitchDeg",
    "baseRollDeg",
    "riggingUuid",
    "beamAngleDeg",
    "gelCode",
    "kindOverride",
    // A variable-length fixture's own length — drawn, never built from.
    "lengthM",
    // The operator's head number — a label, never built from. The one key here with a uniqueness
    // rule, which the bulk route checks against the batch's final state (`headNumberClashes`).
    "headNumber",
    "stageHidden",
    // A paired fixture's other placements — its own table, which the loader never reads.
    "extraPlacements",
    // Fitted media (fixture optics plan session 3) — drawn and snapped to, never built from: the
    // runtime patch metadata carries it, refreshed on this path as `gelCode` is.
    "media",
    // The lantern and its focus (stage-view plan session 7) — drawn, never built from.
    "lanternType",
    "zoomDeg",
    "lampRotationDeg",
    "shutters",
    "gateRotationDeg",
    "iris",
    "focusSoftness",
)

/**
 * PUT body keys the single-patch PUT applies without a fixture rebuild: [METADATA_ONLY_PUT_KEYS]
 * plus `infrastructure`, which the loader does not build from either, but which `GET /fixtures`
 * carries — so the handler announces `fixturesChanged` for it when it flips rather than rebuilding
 * every controller to get that broadcast. Kept out of [METADATA_ONLY_PUT_KEYS] itself because that
 * set is also the bulk placement route's allowlist, which announces nothing: `infrastructure` is a
 * patch role, not a placement.
 */
internal val PUT_KEYS_WITHOUT_REBUILD: Set<String> = METADATA_ONLY_PUT_KEYS + "infrastructure"

// Helpers
/** [placements] are the patch's extra placements; pass them when a caller has batch-loaded them. */
private fun DaoFixturePatch.toDto(
    placements: List<DaoFixturePatchPlacement> = extraPlacementsOf(this),
): FixturePatchDto {
    val typeInfo = FixtureTypeRegistry.typeInfoForKey(fixtureTypeKey)
    val groupRefs = DaoFixtureGroupMember.find { DaoFixtureGroupMembers.fixturePatch eq this@toDto.id }
        .map { FixturePatchGroupRef(id = it.group.id.value, name = it.group.name) }
    val f = focus
    return FixturePatchDto(
        id = id.value,
        key = key,
        displayName = displayName,
        headNumber = headNumber,
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
        baseRollDeg = baseRollDeg,
        riggingUuid = rigging?.uuid?.toString(),
        beamAngleDeg = beamAngleDeg,
        gelCode = gelCode,
        kindOverride = kindOverride,
        lengthM = lengthM,
        stageHidden = stageHidden,
        infrastructure = infrastructure,
        lanternType = f.lanternType,
        zoomDeg = f.zoomDeg,
        lampRotationDeg = f.lampRotationDeg,
        shutters = f.shutters,
        gateRotationDeg = f.gateRotationDeg,
        iris = f.iris,
        focusSoftness = f.focusSoftness,
        media = fittedMedia,
        extraPlacements = placements.map { it.toDto() },
    )
}

internal fun resolveRiggingForProject(project: DaoProject, uuidStr: String): DaoRigging? {
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
internal fun validateStageMetadata(
    stageX: Double?,
    stageY: Double?,
    stageZ: Double?,
    baseYawDeg: Double?,
    basePitchDeg: Double?,
    beamAngleDeg: Int?,
    lengthM: Double? = null,
    baseRollDeg: Double? = null,
): String? {
    checkStageCoord("stageX", stageX)?.let { return it }
    checkStageCoord("stageY", stageY)?.let { return it }
    checkStageCoord("stageZ", stageZ)?.let { return it }
    // Yaw/pitch allow a full ±360°/±180° range so a UI can normalise either way without
    // tripping a 400. Renderers should reduce mod 360.
    checkAngle("baseYawDeg", baseYawDeg, -360.0, 360.0)?.let { return it }
    checkAngle("basePitchDeg", basePitchDeg, -180.0, 180.0)?.let { return it }
    checkAngle("baseRollDeg", baseRollDeg, -180.0, 180.0)?.let { return it }
    if (beamAngleDeg != null && (beamAngleDeg < 2 || beamAngleDeg > 120)) {
        return "beamAngleDeg must be between 2 and 120"
    }
    checkMetres("lengthM", lengthM, MIN_FIXTURE_LENGTH_M, MAX_FIXTURE_LENGTH_M)?.let { return it }
    return null
}

/**
 * Why a `lengthM` may not be stored on a patch of [typeKey] (or on one of its placements), or null
 * when the type takes one. Only a type whose length is set per install (`FixtureType.acceptsLength`
 * — a lightstrip) does; a pixel bar is always the bar it is, so a length on one would only draw a
 * lie. Callers ask only when a non-null length is being written: clearing one is always allowed.
 */
internal fun fixedLengthRefusal(typeKey: String): String? {
    if (FixtureTypeRegistry.typeInfoForKey(typeKey)?.acceptsLength == true) return null
    return "lengthM is only for fixture types whose length is set per install (such as lightstrip); " +
        "'$typeKey' has a fixed length"
}

/** Why [headNumber] is out of range, or null when it is in range (or null — unnumbered). */
internal fun headNumberRangeError(headNumber: Int?): String? =
    if (headNumber != null && headNumber !in MIN_HEAD_NUMBER..MAX_HEAD_NUMBER) {
        "headNumber must be between $MIN_HEAD_NUMBER and $MAX_HEAD_NUMBER"
    } else null

/**
 * A `headNumber` from a raw JSON body: null (an explicit clear) stays null, a whole number in range
 * is taken, and anything else — a string, a fraction, out of range — is a failure naming why.
 * Parsed by hand rather than through `nullableInt()`, which throws on a fraction and so answers 500.
 */
internal fun parseHeadNumber(element: JsonElement?): Result<Int?> {
    if (element == null || element is JsonNull) return Result.success(null)
    val number = (element as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
        ?: return Result.failure(IllegalArgumentException("headNumber must be a whole number"))
    headNumberRangeError(number)?.let { return Result.failure(IllegalArgumentException(it)) }
    return Result.success(number)
}

/** The patch in [project] that holds head [number], other than [exceptPatchId]; null when free. */
internal fun headNumberHolder(project: DaoProject, number: Int, exceptPatchId: Int?): DaoFixturePatch? =
    DaoFixturePatch.find {
        (DaoFixturePatches.project eq project.id) and (DaoFixturePatches.headNumber eq number)
    }.firstOrNull { it.id.value != exceptPatchId }

internal fun headNumberTaken(number: Int, holder: DaoFixturePatch): String =
    "Head number $number is already '${holder.displayName}' (${holder.key})"

/**
 * The bulk route's head-number check: every entry in [assigned] (patch id → its new number, null
 * clearing) whose number another head of [project] will hold once the batch lands. The rig "as it
 * will stand" is every patch's stored number, overridden by each assignment not in [rejected].
 *
 * Iterated to a fixpoint, because refusing an entry leaves that head on its *old* number — which a
 * sibling entry may have been moving onto (A: 1 → 2 beside B: 2 → 3, with B refused). Every head
 * that shares a number is refused, not only the second one: which of two batch entries "got there
 * first" is not a thing a batch has. A head already holding the number and not in the batch keeps it.
 */
internal fun headNumberClashes(
    project: DaoProject,
    assigned: Map<Int, Int?>,
    rejected: Set<Int>,
): List<BulkPlacementFailure> {
    val stored = DaoFixturePatch.find { DaoFixturePatches.project eq project.id }.toList()
    val byId = stored.associateBy { it.id.value }
    val failed = mutableMapOf<Int, String>()
    while (true) {
        val refused = rejected + failed.keys
        val numberOf = stored.associate { p ->
            val id = p.id.value
            id to (if (id in assigned && id !in refused) assigned[id] else p.headNumber)
        }
        val holders = numberOf.entries.filter { it.value != null }.groupBy({ it.value!! }, { it.key })
        var changed = false
        for ((number, ids) in holders) {
            if (ids.size < 2) continue
            for (id in ids) {
                if (id !in assigned || id in refused) continue
                val other = byId.getValue(ids.first { it != id })
                failed[id] = headNumberTaken(number, other)
                changed = true
            }
        }
        if (!changed) break
    }
    return failed.map { (id, error) -> BulkPlacementFailure(id, error) }
}

internal fun normaliseGelCode(raw: String?): String? {
    val trimmed = raw?.trim() ?: return null
    if (trimmed.isEmpty()) return null
    return trimmed.take(20)
}

/**
 * Validate a `kindOverride` request value: must be either `null`/empty (clear) or
 * the name of a [FixtureKind]. Throws [IllegalArgumentException] for anything else
 * so the caller can map to a 400 response.
 */
internal fun normaliseKindOverride(raw: String?): String? {
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
