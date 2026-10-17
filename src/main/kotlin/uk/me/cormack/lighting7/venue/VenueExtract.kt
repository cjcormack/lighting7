package uk.me.cormack.lighting7.venue

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.telemetry.Tracing
import uk.me.cormack.lighting7.models.DaoFixtureGroup
import uk.me.cormack.lighting7.models.DaoFixturePatch
import uk.me.cormack.lighting7.models.DaoMachineOverride
import uk.me.cormack.lighting7.models.DaoMachineOverrides
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoProjects
import uk.me.cormack.lighting7.models.DaoRigging
import uk.me.cormack.lighting7.models.DaoStageElement
import uk.me.cormack.lighting7.models.DaoStageRegion
import uk.me.cormack.lighting7.models.DaoStageViewpoint
import uk.me.cormack.lighting7.models.DaoParkedChannel
import uk.me.cormack.lighting7.models.DaoUniverseConfig
import uk.me.cormack.lighting7.models.ParkAddress
import uk.me.cormack.lighting7.models.DaoVenueVersion
import uk.me.cormack.lighting7.models.PlatformParams
import uk.me.cormack.lighting7.models.ProjectKind
import uk.me.cormack.lighting7.models.StageElementKind
import uk.me.cormack.lighting7.models.StageElementLayer
import uk.me.cormack.lighting7.models.StageViewpointKind
import uk.me.cormack.lighting7.models.VenueLayer
import uk.me.cormack.lighting7.models.VENUE_VERSION_NOTE_MAX
import uk.me.cormack.lighting7.models.VenueLinks
import uk.me.cormack.lighting7.models.ensureDefaultSpeedMasters
import uk.me.cormack.lighting7.models.extraPlacementsOf
import uk.me.cormack.lighting7.models.isVenueBound
import uk.me.cormack.lighting7.models.nowUtc
import uk.me.cormack.lighting7.models.sceneImageHashesOf
import uk.me.cormack.lighting7.models.readElementParams
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.sync.KeptUuidCopy
import java.util.UUID

/**
 * *Extract a venue* (venue plan §3.5, D1, D10): move a show's room and house rig into a new venue,
 * publish it as v1, and put the show on it — so the show loads the same fixtures at the same
 * addresses, now read through its pin.
 *
 * **What moves.** [ExtractKind] names each part; the default is all of them (§3.5): the universes,
 * the patches (with their extra placements), the riggings, the regions, every `VENUE`-layer scene
 * element, the groups, the camera viewpoints, the seating, and the parked channels — which then reach
 * the show through its pin, so every parked channel holds its level. Every row keeps its uuid and changes
 * project — nothing is copied but Preset seating. The parts are checked against each other before
 * anything moves ([VenueException] 400 `VENUE_EXTRACT_INCOMPLETE`): a venue patch on a show's universe
 * or hung on a show's rigging, a venue group holding a show's patch (D12) or a venue platform decking
 * a show's region would point out of the venue.
 *
 * **Seating, per block (D6).** A Fixed block moves, with the seat views that sit in it. A Preset block
 * (absent `block` is Preset) is **copied** into the venue with its uuid and stays the show's: a
 * preset is the venue's, but a show seats its own copy, and the rig scope leaves the venue's out.
 * Seat views in a Preset block stay the show's.
 *
 * **What follows the rows.** Every `machine_overrides` row keyed by a moved record's uuid — a
 * universe's controller IP and transmit interval, an element's *Full detail* — moves to the venue's
 * id, which is what a venue record's override resolves against (P4); so the universe address
 * survives. The scene images a moved or copied element is painted with are copied into the venue's
 * store, from which publishing copies them into v1's; the show's own copies are left for its prune.
 *
 * **Links.** After v1 is published the show's remaining rows that pointed at a moved row — a busk
 * rig tile, a cue event, a scenery change, a show group's member — are re-pointed at v1's row of the
 * same uuid through [VenueLinks.repointAll] (P3). A link that finds no row fails the whole extract.
 *
 * **One transaction.** The move, the venue, v1 (export → import through [KeptUuidCopy], whose own
 * transactions join this one) and the pin commit together or not at all. What a transaction cannot
 * hold is file IO: the images copied into the venue's store and hydrated into v1's are written as it
 * runs, and removed (best effort) when it rolls back. **A dry run is the same run rolled back** — it
 * answers exactly what the move would do, then leaves the database and both stores as they were.
 */
class VenueExtract(private val state: State) {

    private val logger = LoggerFactory.getLogger(VenueExtract::class.java)

    /** The parts of a show's rig an extract can move (venue plan §3.5's default set is all of them). */
    enum class ExtractKind { UNIVERSES, PATCHES, RIGGINGS, REGIONS, ELEMENTS, SEATING, GROUPS, VIEWPOINTS, PARKS }

    /**
     * Extract [include] of show [showId] into a new venue called [name]; [publishedBy] signs v1.
     * With [dryRun] nothing is kept. The caller reloads a live show.
     */
    suspend fun extract(
        showId: Int,
        name: String,
        include: Set<ExtractKind>,
        dryRun: Boolean,
        publishedBy: String?,
    ): ExtractResultDto = Tracing.transactionSuspending("venue.extract", "venue.extract") { span ->
        span?.setData("venue.dry_run", dryRun)
        extractTraced(showId, name, include, dryRun, publishedBy)
    }

    private suspend fun extractTraced(
        showId: Int,
        name: String,
        include: Set<ExtractKind>,
        dryRun: Boolean,
        publishedBy: String?,
    ): ExtractResultDto = withContext(Dispatchers.IO) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) throw VenueException.badRequest(CODE_EXTRACT_INVALID, "A venue needs a name")
        if (trimmed.length > PROJECT_NAME_MAX) {
            throw VenueException.badRequest(CODE_EXTRACT_INVALID, "A venue's name may be at most $PROJECT_NAME_MAX characters")
        }
        if (include.isEmpty()) throw VenueException.badRequest(CODE_EXTRACT_INVALID, "Choose at least one part of the rig to extract")

        val venueUuid = UUID.randomUUID()
        val snapshotUuid = UUID.randomUUID()
        try {
            transaction(state.database) {
                val result = run(showId, trimmed, include, publishedBy, venueUuid, snapshotUuid)
                if (dryRun) TransactionManager.current().rollback()
                result.copy(dryRun = dryRun, venueId = result.venueId.takeUnless { dryRun })
            }.also {
                if (dryRun) dropStores(venueUuid, snapshotUuid)
                else logger.info("Extracted venue '{}' ({}) from project {}", trimmed, venueUuid, showId)
            }
        } catch (e: Throwable) {
            dropStores(venueUuid, snapshotUuid)
            throw e
        }
    }

    private fun dropStores(vararg uuids: UUID) {
        for (uuid in uuids) {
            runCatching { state.sceneImages.deleteProject(uuid.toString()) }
            runCatching { state.promptScriptStoreRoot.resolve(uuid.toString()).toFile().deleteRecursively() }
        }
    }

    /** The extract itself, inside the caller's transaction. */
    private fun run(
        showId: Int,
        name: String,
        include: Set<ExtractKind>,
        publishedBy: String?,
        venueUuid: UUID,
        snapshotUuid: UUID,
    ): ExtractResultDto {
        val show = DaoProject.findById(showId)
            ?: throw VenueException(HttpStatusCode.NotFound, CODE_EXTRACT_INVALID, "Project not found")
        if (show.kind != ProjectKind.SHOW) {
            throw VenueException.conflict(CODE_EXTRACT_REFUSED, "Only a show's rig can be extracted into a venue")
        }
        if (show.isVenueBound) throw VenueException.conflict(CODE_EXTRACT_REFUSED, "${show.name} is already on a venue")
        if (DaoProject.find { DaoProjects.name eq name }.any()) {
            throw VenueException.conflict(CODE_EXTRACT_INVALID, "A project named \"$name\" already exists")
        }

        val plan = plan(show, include)
        if (plan.isEmpty()) throw VenueException.conflict(CODE_EXTRACT_EMPTY, "${show.name} has nothing of that to extract")

        val venue = DaoProject.new {
            this.name = name
            description = null
            isCurrent = false
            stageWidthM = show.stageWidthM
            stageDepthM = show.stageDepthM
            stageHeightM = show.stageHeightM
            kind = ProjectKind.VENUE
            uuid = venueUuid
        }
        ensureDefaultSpeedMasters(venue)

        // The move: each row changes project, uuid kept. Placements and members follow their parent.
        plan.universes.forEach { it.project = venue }
        plan.patches.forEach { it.project = venue }
        plan.riggings.forEach { it.project = venue }
        plan.regions.forEach { it.project = venue }
        plan.elements.forEach { it.project = venue }
        plan.groups.forEach { it.project = venue }
        plan.viewpoints.forEach { it.project = venue }
        plan.parks.forEach { it.project = venue }
        plan.presetSeating.forEach { copyElement(it, venue) }

        // What follows the rows: their machine-local overrides, and the images they are painted with.
        val movedUuids = plan.movedUuids()
        val overrides = DaoMachineOverride.find { DaoMachineOverrides.project eq show.id }
            .filter { it.recordUuid in movedUuids }
        overrides.forEach { it.project = venue }
        val hashes = (plan.elements + plan.presetSeating).flatMap { sceneImageHashesOf(it.params) }.toSortedSet()
        val images = hashes.count { hash ->
            val src = state.sceneImages.original(show.uuid.toString(), hash)?.first ?: return@count false
            state.sceneImages.hydrate(venueUuid.toString(), hash, src)
            true
        }

        // v1, in this transaction: the import's own `transaction` joins it.
        var snapshotId: EntityID<Int>? = null
        KeptUuidCopy.copy(
            state, venue.id.value,
            name = VenueVersions.snapshotName(snapshotUuid),
            kind = ProjectKind.VENUE_VERSION,
            projectUuid = snapshotUuid,
        ) { snapshot ->
            DaoVenueVersion.new {
                venueProject = venue
                number = 1
                note = "Extracted from ${show.name}".take(VENUE_VERSION_NOTE_MAX)
                publishedAt = nowUtc()
                this.publishedBy = publishedBy
                snapshotProject = snapshot
            }
            snapshotId = snapshot.id
        }
        val snapshot = snapshotId ?: error("v1 was not written")

        // The pin, then every link the show still holds into the moved rows, onto v1's.
        show.venueUuid = venueUuid
        show.venueVersion = 1
        // A show that extracts a venue is on it now, not detached from another.
        show.detachedFromVenueUuid = null
        show.detachedFromVersion = null
        val repointed = VenueLinks.repointAll(show.id, venue.id, snapshot)
        val dangling = repointed.filterValues { it.unmatched.isNotEmpty() }
        if (dangling.isNotEmpty()) {
            error("Extract left links pointing at the draft: ${dangling.keys.joinToString()}")
        }
        check(VenueLayer.resolve(show.id)?.snapshotId == snapshot) { "The pin does not resolve to v1" }

        return ExtractResultDto(
            dryRun = false,
            venueId = venue.id.value,
            venueUuid = venueUuid.toString(),
            name = name,
            version = 1,
            moved = ExtractRowsDto(
                universes = plan.universes.map { "${it.subnet}:${it.universe}" },
                patches = plan.patches.map { it.key },
                riggings = plan.riggings.map { it.name },
                regions = plan.regions.map { it.name },
                elements = plan.elements.map { it.name },
                groups = plan.groups.map { it.name },
                viewpoints = plan.viewpoints.map { it.name },
                parks = plan.parks.map { ParkAddress(it.universe, it.channel).toString() }.sorted(),
            ),
            copiedPresetSeating = plan.presetSeating.map { it.name },
            relinked = repointed.filterValues { it.moved > 0 }.mapValues { it.value.moved },
            overridesMoved = overrides.size,
            imagesCopied = images,
        )
    }

    /** What moves, read and checked before anything does. */
    private class Plan(
        val universes: List<DaoUniverseConfig>,
        val patches: List<DaoFixturePatch>,
        val riggings: List<DaoRigging>,
        val regions: List<DaoStageRegion>,
        /** Moving elements: VENUE-layer non-seating, and Fixed seating. */
        val elements: List<DaoStageElement>,
        val presetSeating: List<DaoStageElement>,
        val groups: List<DaoFixtureGroup>,
        val viewpoints: List<DaoStageViewpoint>,
        /** Channel-level, linking to nothing: a park moves whether or not its universe does. */
        val parks: List<DaoParkedChannel>,
    ) {
        fun isEmpty() = universes.isEmpty() && patches.isEmpty() && riggings.isEmpty() && regions.isEmpty() &&
            elements.isEmpty() && presetSeating.isEmpty() && groups.isEmpty() && viewpoints.isEmpty() && parks.isEmpty()

        fun movedUuids(): Set<UUID> = buildSet {
            universes.mapTo(this) { it.uuid }
            patches.mapTo(this) { it.uuid }
            riggings.mapTo(this) { it.uuid }
            regions.mapTo(this) { it.uuid }
            elements.mapTo(this) { it.uuid }
            groups.mapTo(this) { it.uuid }
            viewpoints.mapTo(this) { it.uuid }
        }
    }

    private fun plan(show: DaoProject, include: Set<ExtractKind>): Plan {
        fun has(kind: ExtractKind) = kind in include
        val universes = if (has(ExtractKind.UNIVERSES)) show.universeConfigs.toList() else emptyList()
        val patches = if (has(ExtractKind.PATCHES)) show.fixturePatches.toList() else emptyList()
        val riggings = if (has(ExtractKind.RIGGINGS)) show.riggings.toList() else emptyList()
        val regions = if (has(ExtractKind.REGIONS)) show.stageRegions.toList() else emptyList()
        val allElements = show.stageElements.toList()
        val seating = if (has(ExtractKind.SEATING)) allElements.filter { it.kind == StageElementKind.SEATING.name } else emptyList()
        val (fixedSeating, presetSeating) = seating.partition { VenueLayer.isFixedSeating(it.params) }
        val venueElements = if (has(ExtractKind.ELEMENTS)) {
            allElements.filter { it.kind != StageElementKind.SEATING.name && it.layer == StageElementLayer.VENUE.name }
        } else {
            emptyList()
        }
        val elements = venueElements + fixedSeating
        val groups = if (has(ExtractKind.GROUPS)) show.fixtureGroups.toList() else emptyList()
        val fixedSeatingUuids = fixedSeating.map { it.uuid }.toSet()
        val viewpoints = if (has(ExtractKind.VIEWPOINTS)) {
            show.stageViewpoints.filter { vp ->
                when (vp.kind) {
                    StageViewpointKind.SEAT.name -> vp.seatElementUuid in fixedSeatingUuids
                    else -> true
                }
            }
        } else {
            emptyList()
        }

        // Nothing that moves may point at a row that stays: the venue would link out of itself.
        val problems = mutableListOf<String>()
        val universeIds = universes.map { it.id }.toSet()
        val riggingIds = riggings.map { it.id }.toSet()
        val regionUuids = regions.map { it.uuid.toString() }.toSet()
        val patchIds = patches.map { it.id }.toSet()
        for (patch in patches) {
            if (patch.universeConfig.id !in universeIds) problems += "patch ${patch.key} is on a universe that stays (include UNIVERSES)"
            val hangs = listOfNotNull(patch.rigging) + extraPlacementsOf(patch).mapNotNull { it.rigging }
            if (hangs.any { it.id !in riggingIds }) problems += "patch ${patch.key} hangs on a rigging that stays (include RIGGINGS)"
        }
        for (group in groups) {
            if (group.members.any { it.fixturePatch.id !in patchIds }) {
                problems += "group ${group.name} holds patches that stay (include PATCHES): a venue group never holds a show's"
            }
        }
        for (element in elements.filter { it.kind == StageElementKind.PLATFORM.name }) {
            val region = (readElementParams(StageElementKind.PLATFORM, element.params) as? PlatformParams)?.regionUuid ?: continue
            if (region !in regionUuids && show.stageRegions.any { it.uuid.toString() == region }) {
                problems += "platform ${element.name} decks a region that stays (include REGIONS)"
            }
        }
        if (problems.isNotEmpty()) {
            throw VenueException.badRequest(CODE_EXTRACT_INCOMPLETE, "Extract would split the rig: ${problems.joinToString("; ")}")
        }
        val parks = if (has(ExtractKind.PARKS)) show.parkedChannels.toList() else emptyList()
        return Plan(universes, patches, riggings, regions, elements, presetSeating, groups, viewpoints, parks)
    }

    /** [element] copied into [venue], uuid kept: a Preset seating block, which stays the show's too. */
    private fun copyElement(element: DaoStageElement, venue: DaoProject) {
        DaoStageElement.new {
            project = venue
            name = element.name
            kind = element.kind
            layer = element.layer
            positionX = element.positionX
            positionY = element.positionY
            positionZ = element.positionZ
            yawDeg = element.yawDeg
            widthM = element.widthM
            depthM = element.depthM
            heightM = element.heightM
            finishColour = element.finishColour
            finishPattern = element.finishPattern
            emissive = element.emissive
            params = element.params
            hidden = element.hidden
            sortOrder = element.sortOrder
            uuid = element.uuid
        }
    }

    companion object {
        const val CODE_EXTRACT_INVALID = "VENUE_EXTRACT_INVALID"
        const val CODE_EXTRACT_REFUSED = "VENUE_EXTRACT_REFUSED"
        const val CODE_EXTRACT_EMPTY = "VENUE_EXTRACT_EMPTY"
        const val CODE_EXTRACT_INCOMPLETE = "VENUE_EXTRACT_INCOMPLETE"

        /** `projects.name`'s width. */
        private const val PROJECT_NAME_MAX = 50
    }
}

/** The rows an extract moves, each named as the Patch list and the rigging views name it. */
@Serializable
data class ExtractRowsDto(
    /** `subnet:universe`. */
    val universes: List<String>,
    /** By key. */
    val patches: List<String>,
    val riggings: List<String>,
    val regions: List<String>,
    /** VENUE-layer elements and Fixed seating. */
    val elements: List<String>,
    val groups: List<String>,
    val viewpoints: List<String>,
    /** Parked channels, as `1-005`. */
    val parks: List<String> = emptyList(),
)

/** What an extract did — or, with [dryRun], would do. */
@Serializable
data class ExtractResultDto(
    val dryRun: Boolean,
    /** The new venue; null on a dry run, which keeps nothing. */
    val venueId: Int? = null,
    val venueUuid: String,
    val name: String,
    /** The version the show is pinned to: always 1. */
    val version: Int,
    val moved: ExtractRowsDto,
    /** Preset seating copied into the venue and kept by the show (D6). */
    val copiedPresetSeating: List<String>,
    /** The show's links re-pointed at v1, by `table.column`. */
    val relinked: Map<String, Int>,
    /** `machine_overrides` rows moved to the venue's id (controller IPs, *Full detail*). */
    val overridesMoved: Int,
    /** Scene images copied into the venue's store. */
    val imagesCopied: Int,
)
