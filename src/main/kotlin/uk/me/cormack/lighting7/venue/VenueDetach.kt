package uk.me.cormack.lighting7.venue

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.telemetry.Tracing
import uk.me.cormack.lighting7.fixture.lantern.focus
import uk.me.cormack.lighting7.fixture.locate.storedLocate
import uk.me.cormack.lighting7.fixture.media.fittedMedia
import uk.me.cormack.lighting7.models.DaoBuskRigTiles
import uk.me.cormack.lighting7.models.DaoCueEvents
import uk.me.cormack.lighting7.models.DaoCueScenery
import uk.me.cormack.lighting7.models.DaoCueStackScenery
import uk.me.cormack.lighting7.models.DaoFixtureGroupMembers
import uk.me.cormack.lighting7.models.DaoFixtureGroups
import uk.me.cormack.lighting7.models.DaoFixturePatch
import uk.me.cormack.lighting7.models.DaoFixturePatchPlacement
import uk.me.cormack.lighting7.models.DaoFixturePatchPlacements
import uk.me.cormack.lighting7.models.DaoFixturePatches
import uk.me.cormack.lighting7.models.DaoLookScenery
import uk.me.cormack.lighting7.models.DaoMachineOverride
import uk.me.cormack.lighting7.models.DaoMachineOverrides
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoRiggings
import uk.me.cormack.lighting7.models.DaoParkedChannel
import uk.me.cormack.lighting7.models.DaoParkedChannels
import uk.me.cormack.lighting7.models.DaoFixtureGroup
import uk.me.cormack.lighting7.models.DaoShowColours
import uk.me.cormack.lighting7.models.DaoShowGroupOrders
import uk.me.cormack.lighting7.models.DaoShowHides
import uk.me.cormack.lighting7.models.DaoShowParkReleases
import uk.me.cormack.lighting7.models.ParkAddress
import uk.me.cormack.lighting7.models.VenueParks
import uk.me.cormack.lighting7.models.DaoShowOverrides
import uk.me.cormack.lighting7.models.DaoStageElements
import uk.me.cormack.lighting7.models.DaoStageRegions
import uk.me.cormack.lighting7.models.DaoStageViewpoints
import uk.me.cormack.lighting7.models.DaoUniverseConfigs
import uk.me.cormack.lighting7.models.ProjectKind
import uk.me.cormack.lighting7.models.RigScope
import uk.me.cormack.lighting7.models.VenueLayer
import uk.me.cormack.lighting7.models.VenueLinks
import uk.me.cormack.lighting7.models.extraPlacementsOf
import uk.me.cormack.lighting7.models.sceneImageHashesOf
import uk.me.cormack.lighting7.state.State
import java.util.UUID

/**
 * *Detach* (venue plan D9, session 7): flatten a show on a venue into a standalone show — the venue's
 * rig at the show's pin, as the show sees it, written into the show's own rows — so it loads the same
 * fixtures at the same addresses with no venue under it. The archive, and the rare show that leaves
 * its hall; moving a show to another venue by its keys is `FU-VENUE-TOURING`, not this.
 *
 * **What is copied** is [RigScope]'s effective rig at the **pinned** version, less what the show
 * already owns: every universe, and every patch (with its extra placements), rigging, region,
 * scene element, group and viewpoint of the snapshot that the show does not hide — and every park of
 * the snapshot the show neither re-parks nor releases ([VenueParks]), so each parked channel holds. A patch is written
 * with its facet overrides **applied** — `RigScope.effective` / `effectivePlacements`' fields, never
 * the snapshot row's — and a dormant override contributes nothing, as it does to the loaded rig. A
 * venue group comes over with its members as the rig sees them (less hidden patches), in the show's
 * order of it where it has one (group-order plan D10). The snapshot's
 * **Preset** seating is left out: the show already seats its own copy (D6), which the rig reads in
 * its place. **Fixed** seating comes over with its seat views.
 *
 * **Uuids are kept.** Everything the show already holds that names a venue record names it by uuid —
 * a seat view's seating, a platform's region, a scenery row once re-pointed, the importer's maps on a
 * peer — and the venue's rows are the show's rig in every sense that matters after this, so the copy
 * is the same record under a new project, as Extract's move is in the other direction. The uuid
 * columns are not unique (venue plan §1), so the venue's draft and versions sharing them is the
 * situation Extract already makes.
 *
 * **What follows the rows** (P4). A venue record's machine-local overrides — a universe's controller
 * IP and transmit interval, an element's *Full detail* — are keyed by the **venue's** id; the copies
 * are the show's records now, resolved against the show's id, so each override is **copied** to the
 * show (never moved: the venue and every other show on it still use it). The scene images a copied
 * element is painted with are copied from the version's store into the show's.
 *
 * **Links** (P3). Every link the show's own rows hold into the snapshot — a show patch on a venue
 * universe or rigging, a show group's venue member, a busk tile, a cue event, a cue's, stack's or
 * Look's scenery, and the copied rows' own links — is re-pointed at the show's row of the same uuid
 * through [VenueLinks.ALL]. One that finds no row named a record the show hides (only a show group's
 * member can, since a hide is refused while anything else uses its record): it goes, as Update drops
 * a link to a removed record. Nothing may be left pointing into a snapshot.
 *
 * **Then** the show's hides and overrides go, and the pin, its repo hint and the stage size (read from
 * the version until now) are written onto the show. The pin becomes the show's **origin**
 * (`detachedFromVenueUuid` + `detachedFromVersion`, venue-attach plan D1), so an attach can later
 * match it three ways (`venue/VenueAttachPairing.kt`). **A show that is behind detaches at its pin** —
 * what it runs — not at the installed version: an update has decisions only an Update can ask, and
 * the answer names how far behind it was, so the confirm can say the hall has moved on.
 *
 * **One transaction**, and **a dry run is the same run rolled back**, as Extract's and Update's are; the
 * images are the step outside it, copied only by a real run and removed (best effort) if it fails.
 */
class VenueDetach(private val state: State) {

    private val logger = LoggerFactory.getLogger(VenueDetach::class.java)

    /** Detach [projectId]; with [dryRun] nothing is kept. The caller reloads a live show. */
    suspend fun detach(projectId: Int, dryRun: Boolean): DetachResultDto =
        Tracing.transactionSuspending("venue.detach", "venue.detach") { span ->
            span?.setData("venue.dry_run", dryRun)
            detachTraced(projectId, dryRun)
        }

    private suspend fun detachTraced(projectId: Int, dryRun: Boolean): DetachResultDto = withContext(Dispatchers.IO) {
        // Refused before anything is judged, so a project that is not a show on a venue is answered by name.
        val showUuid = transaction(state.database) { requireDetachable(projectId).uuid.toString() }
        // How far behind the pin is: through the version cache, outside the transaction (it exports).
        val behind = Tracing.span("venue.behind") { ShowBehind.judge(state, projectId) }
        val copied = mutableListOf<String>()
        try {
            transaction(state.database) {
                val result = run(projectId, behind, copyImages = !dryRun, showUuid = showUuid, copiedImages = copied)
                if (dryRun) TransactionManager.current().rollback()
                result.copy(dryRun = dryRun)
            }.also { if (!dryRun) logger.info("Detached project {} from {} v{}", projectId, it.venueName, it.version) }
        } catch (e: Throwable) {
            copied.forEach { runCatching { state.sceneImages.delete(showUuid, it) } }
            throw e
        }
    }

    /** [projectId] as a show on a venue whose pinned version this desk holds, or the refusal. */
    private fun requireDetachable(projectId: Int): DaoProject {
        val project = DaoProject.findById(projectId)
            ?: throw VenueException(HttpStatusCode.NotFound, CODE_DETACH_NOT_A_SHOW, "Project not found")
        if (project.kind != ProjectKind.SHOW || project.venueUuid == null || project.venueVersion == null) {
            throw VenueException.conflict(CODE_DETACH_NOT_A_SHOW, "${project.name} is not a show on a venue: there is nothing to detach")
        }
        if (VenuePins.isMissing(project)) throw VenuePins.missing(project)
        return project
    }

    /** The detach itself, inside the caller's transaction. */
    private fun run(
        projectId: Int,
        behind: ShowBehind.Judgement,
        copyImages: Boolean,
        showUuid: String,
        copiedImages: MutableList<String>,
    ): DetachResultDto {
        val show = requireDetachable(projectId)
        val rig = RigScope.of(show)
        val layer = rig.venue ?: throw VenuePins.missing(show)
        val snapshotId = layer.snapshotId
        val snapshot = DaoProject[snapshotId]
        fun ofSnapshot(project: EntityID<Int>) = project == snapshotId

        // What the rig reads from the snapshot, read before anything is written.
        val universes = rig.universeConfigs(DaoUniverseConfigs.project eq snapshotId).toList()
        val riggings = rig.riggings(DaoRiggings.project eq snapshotId).toList()
        val regions = rig.stageRegions(DaoStageRegions.project eq snapshotId).toList()
        val elements = rig.stageElements(DaoStageElements.project eq snapshotId).toList()
        val viewpoints = rig.stageViewpoints(DaoStageViewpoints.project eq snapshotId).toList()
        val groups = rig.groups(DaoFixtureGroups.project eq snapshotId).toList()
        val patches = rig.patches(DaoFixturePatches.project eq snapshotId).toList()
        val effective = patches.associateWith { rig.effective(it) }
        val placements = patches.associateWith { rig.effectivePlacements(it, extraPlacementsOf(it)) }
        val members = groups.associateWith { rig.members(it).toList() }
        val overridden = patches.mapNotNull { p ->
            effective.getValue(p).overridden.takeIf { it.isNotEmpty() }?.let { facets ->
                "${p.key}: ${facets.sortedBy { it.ordinal }.joinToString { it.name.lowercase() }}"
            }
        }
        val hides = DaoShowHides.select(DaoShowHides.id).where { DaoShowHides.project eq show.id }.count().toInt()

        // Keys and names are one namespace across the rig (§3.3), so the copies cannot clash with the
        // show's own — unless a row came in by a door that never checked (an old import). Named, not a
        // constraint error.
        val own = rig.own
        val clashes = buildList {
            fun clash(kind: String, venue: List<String>, mine: List<String>) {
                val taken = mine.toSet()
                venue.filter { it in taken }.forEach { add("$kind '$it'") }
            }
            clash("patch key", patches.map { it.key }, own.patches().map { it.key })
            clash("universe", universes.map { "${it.subnet}:${it.universe}" }, own.universeConfigs().map { "${it.subnet}:${it.universe}" })
            clash("rigging", riggings.map { it.name }, own.riggings().map { it.name })
            clash("stage region", regions.map { it.name }, own.stageRegions().map { it.name })
            clash("stage element", elements.map { it.name }, own.stageElements().map { it.name })
            clash("viewpoint", viewpoints.map { it.name }, own.stageViewpoints().map { it.name })
            clash("group", groups.map { it.name }, own.groups().map { it.name })
        }
        if (clashes.isNotEmpty()) {
            throw VenueException.conflict(
                CODE_DETACH_BLOCKED,
                "${show.name} has its own ${clashes.joinToString()}, which the venue's rig also has: rename yours first",
            )
        }

        // The copies, each under the show, uuid kept. Links into the snapshot are re-pointed below.
        universes.forEach { copyRow(DaoUniverseConfigs, it.id, DaoUniverseConfigs.project, show.id) }
        riggings.forEach { copyRow(DaoRiggings, it.id, DaoRiggings.project, show.id) }
        regions.forEach { copyRow(DaoStageRegions, it.id, DaoStageRegions.project, show.id) }
        elements.forEach { copyRow(DaoStageElements, it.id, DaoStageElements.project, show.id) }
        viewpoints.forEach { copyRow(DaoStageViewpoints, it.id, DaoStageViewpoints.project, show.id) }
        for (patch in patches) {
            val e = effective.getValue(patch)
            val copy = DaoFixturePatch[copyRow(DaoFixturePatches, patch.id, DaoFixturePatches.project, show.id)]
            // The overridden facets' fields, as the rig sees them, over the snapshot row's.
            copy.universeConfig = e.universeConfig
            copy.startChannel = e.startChannel
            copy.rigging = e.rigging
            copy.stageX = e.stageX
            copy.stageY = e.stageY
            copy.stageZ = e.stageZ
            copy.baseYawDeg = e.baseYawDeg
            copy.basePitchDeg = e.basePitchDeg
            copy.baseRollDeg = e.baseRollDeg
            copy.lengthM = e.lengthM
            copy.focus = e.focus
            copy.beamAngleDeg = e.beamAngleDeg
            copy.lampWatts = e.lampWatts
            copy.lampKelvin = e.lampKelvin
            copy.storedLocate = e.locate
            copy.kindOverride = e.kindOverride
            copy.gelCode = e.gelCode
            copy.fittedMedia = e.fittedMedia
            // The colour as the show draws it: its recolour, else the venue's, which the copy already holds.
            rig.colours[patch.uuid]?.let { copy.colour = it }
            for (p in placements.getValue(patch)) {
                val placement = DaoFixturePatchPlacement[
                    copyRow(DaoFixturePatchPlacements, p.row.id, DaoFixturePatchPlacements.fixturePatch, copy.id),
                ]
                placement.rigging = p.rigging
                placement.stageX = p.stageX
                placement.stageY = p.stageY
                placement.stageZ = p.stageZ
                placement.baseYawDeg = p.baseYawDeg
                placement.basePitchDeg = p.basePitchDeg
                placement.baseRollDeg = p.baseRollDeg
                placement.lengthM = p.lengthM
                placement.focus = p.focus
                placement.fittedMedia = p.fittedMedia
            }
        }
        for (group in groups) {
            val copy = copyRow(DaoFixtureGroups, group.id, DaoFixtureGroups.project, show.id)
            rig.colours[group.uuid]?.let { colour -> DaoFixtureGroup[copy].colour = colour }
            // Its members as the rig sees them, in the show's order; each still names the snapshot's patch until the re-point.
            members.getValue(group).forEachIndexed { i, member ->
                val copied = copyRow(DaoFixtureGroupMembers, member.id, DaoFixtureGroupMembers.group, copy)
                DaoFixtureGroupMembers.update({ DaoFixtureGroupMembers.id eq copied }) { it[DaoFixtureGroupMembers.sortOrder] = i }
            }
        }

        // Every link from the snapshot onto the show's rows of the same uuid; one that finds none named a
        // hidden record, and goes.
        val dropped = mutableListOf<UpdateItemDto>()
        val relinked = mutableMapOf<String, Int>()
        for (link in VenueLinks.ALL) {
            val outcome = link.repoint(show.id, snapshotId, show.id)
            if (outcome.moved > 0) relinked[link.name] = outcome.moved
            if (outcome.unmatched.isNotEmpty()) dropLinks(link, outcome.unmatched, dropped)
        }
        val dangling = VenueLinks.ALL.filter { link -> linksInto(link, show.id, snapshotId) > 0 }
        check(dangling.isEmpty()) { "Detach left links pointing into the venue: ${dangling.joinToString { it.name }}" }

        // What follows the rows: the venue's machine overrides of them, copied to the show's id (P4).
        val copiedUuids = buildSet {
            universes.mapTo(this) { it.uuid }
            riggings.mapTo(this) { it.uuid }
            regions.mapTo(this) { it.uuid }
            elements.mapTo(this) { it.uuid }
            viewpoints.mapTo(this) { it.uuid }
            groups.mapTo(this) { it.uuid }
            patches.mapTo(this) { it.uuid }
        }
        val machineOverrides = copyMachineOverrides(layer.venueProjectId, show, copiedUuids)

        // The images the copied elements are painted with, from the version's store (else the venue's).
        val hashes = elements.flatMap { sceneImageHashesOf(it.params) }.toSortedSet()
        val held = state.sceneImages.storedHashes(showUuid)
        val missing = hashes.filter { it !in held }
        var images = 0
        if (copyImages) {
            for (hash in missing) {
                val src = state.sceneImages.original(snapshot.uuid.toString(), hash)?.first
                    ?: state.sceneImages.original(layer.venueUuid.toString(), hash)?.first
                    ?: continue
                if (state.sceneImages.hydrate(showUuid, hash, src)) {
                    copiedImages += hash
                    images++
                }
            }
        } else {
            images = missing.count {
                state.sceneImages.exists(snapshot.uuid.toString(), it) || state.sceneImages.exists(layer.venueUuid.toString(), it)
            }
        }

        // The venue's parks at the pin, as the show runs them: every one the show neither re-parks nor
        // releases becomes the show's own row, uuid kept — so every parked channel holds its level.
        val parkLayers = VenueParks.of(show.id)
        val venueParks = DaoParkedChannel.find { DaoParkedChannels.project eq snapshotId }
            .filter { ParkAddress(it.universe, it.channel).let { a -> a !in parkLayers.own && a !in parkLayers.releases } }
            .sortedWith(compareBy({ it.universe }, { it.channel }))
        for (park in venueParks) {
            DaoParkedChannel.new {
                project = show
                universe = park.universe
                channel = park.channel
                value = park.value
                uuid = park.uuid
            }
        }
        val releasesDropped = DaoShowParkReleases.deleteWhere { DaoShowParkReleases.project eq show.id }

        // The show's layer goes, and the show stands on its own: no pin, and the stage it was reading.
        DaoShowOverrides.deleteWhere { DaoShowOverrides.project eq show.id }
        DaoShowHides.deleteWhere { DaoShowHides.project eq show.id }
        // Counted over the copies: a dormant recolour of a hidden record, or of one the pin lacks, was never written.
        val coloursApplied = patches.count { it.uuid in rig.colours } + groups.count { it.uuid in rig.colours }
        DaoShowColours.deleteWhere { DaoShowColours.project eq show.id }
        val ordersApplied = groups.count { it.uuid in rig.groupOrders }
        DaoShowGroupOrders.deleteWhere { DaoShowGroupOrders.project eq show.id }
        show.stageWidthM = snapshot.stageWidthM
        show.stageDepthM = snapshot.stageDepthM
        show.stageHeightM = snapshot.stageHeightM
        show.detachedFromVenueUuid = layer.venueUuid
        show.detachedFromVersion = layer.version
        show.venueUuid = null
        show.venueVersion = null
        show.venueRepo = null
        check(VenueLayer.resolve(show.id) == null) { "The show still resolves a venue" }

        return DetachResultDto(
            dryRun = false,
            projectId = projectId,
            venueId = layer.venueProjectId.value,
            venueName = layer.venueName,
            version = layer.version,
            installed = behind.installed,
            behind = behind.behind,
            detachedFrom = DetachedFromDto(layer.venueUuid.toString(), layer.version, layer.venueProjectId.value, layer.venueName),
            copied = DetachRowsDto(
                universes = universes.map { "${it.subnet}:${it.universe}" },
                patches = patches.map { it.key },
                riggings = riggings.map { it.name },
                regions = regions.map { it.name },
                elements = elements.map { it.name },
                groups = groups.map { it.name },
                viewpoints = viewpoints.map { it.name },
                parks = venueParks.map { ParkAddress(it.universe, it.channel).toString() },
            ),
            overridesApplied = overridden,
            hidesDropped = hides,
            coloursApplied = coloursApplied,
            ordersApplied = ordersApplied,
            parkReleasesDropped = releasesDropped,
            relinked = relinked,
            dropped = dropped,
            machineOverridesCopied = machineOverrides,
            imagesCopied = images,
        )
    }

    /**
     * [rowId] of [table] copied whole into a new row whose [owner] column is [ownerId] — every other
     * column, its uuid included, as the source row holds it. Generic over the table so a column added
     * to a rig table later is carried without a change here.
     */
    private fun copyRow(table: IntIdTable, rowId: EntityID<Int>, owner: Column<EntityID<Int>>, ownerId: EntityID<Int>): EntityID<Int> {
        val source = table.selectAll().where { table.id eq rowId }.single()
        return table.insertAndGetId { statement ->
            for (column in table.columns) {
                if (column == table.id) continue
                // A column's type parameter is only the Kotlin view of its values: the source row's
                // value is that column's own, so writing it back through an erased column is safe.
                @Suppress("UNCHECKED_CAST")
                statement[column as Column<Any?>] = source[column]
            }
            statement[owner] = ownerId
        }
    }

    /** How many of [project]'s rows still link through [link] into [snapshot]. */
    private fun linksInto(link: VenueLinks.Link, project: EntityID<Int>, snapshot: EntityID<Int>): Long {
        val targetIds = link.target.select(link.target.id).where { projectColumnOf(link.target) eq snapshot }
            .map { it[link.target.id] }
        if (targetIds.isEmpty()) return 0
        return link.table.select(link.table.id).where { link.owner.ownedBy(project) and (link.column inList targetIds) }.count()
    }

    private fun projectColumnOf(table: IntIdTable): Column<EntityID<Int>> = when (table) {
        DaoFixturePatches -> DaoFixturePatches.project
        DaoUniverseConfigs -> DaoUniverseConfigs.project
        DaoRiggings -> DaoRiggings.project
        DaoFixtureGroups -> DaoFixtureGroups.project
        DaoStageElements -> DaoStageElements.project
        else -> error("No venue-link target ${table.tableName}")
    }

    /**
     * The show's rows still linking to a record of the snapshot the show hides: a membership, tile,
     * event or scenery row goes, and a patch or placement hung on a hidden rigging comes off it, as
     * Update drops a link to a removed record. A universe is never hidden, so a patch never lands here
     * on its universe.
     */
    private fun dropLinks(link: VenueLinks.Link, rowIds: List<Int>, dropped: MutableList<UpdateItemDto>) {
        val ids = rowIds.map { EntityID(it, link.table) }
        when (link.table) {
            DaoFixturePatches -> {
                check(link.column == DaoFixturePatches.rigging) { "A show patch names a universe the venue lacks" }
                DaoFixturePatches.update({ DaoFixturePatches.id inList ids }) { it[DaoFixturePatches.rigging] = null }
                dropped += UpdateItemDto("LINK", "${rowIds.size} patch${if (rowIds.size == 1) "" else "es"}", "hung on a rigging this show hides: off it, position kept")
            }
            DaoFixturePatchPlacements -> {
                DaoFixturePatchPlacements.update({ DaoFixturePatchPlacements.id inList ids }) { it[DaoFixturePatchPlacements.rigging] = null }
                dropped += UpdateItemDto("LINK", "${rowIds.size} placement${if (rowIds.size == 1) "" else "s"}", "hung on a rigging this show hides: off it")
            }
            DaoFixtureGroupMembers, DaoBuskRigTiles, DaoCueEvents, DaoCueScenery, DaoCueStackScenery, DaoLookScenery -> {
                link.table.deleteWhere { link.table.id inList ids }
                dropped += UpdateItemDto("LINK", link.name, "${rowIds.size} row${if (rowIds.size == 1) "" else "s"} naming a record this show hides")
            }
            else -> error("Unexpected unmatched link ${link.name}")
        }
    }

    /** The venue's machine overrides of [uuids], copied to [show]'s id; one the show already has is kept. */
    private fun copyMachineOverrides(venueId: EntityID<Int>, show: DaoProject, uuids: Set<UUID>): Int {
        if (uuids.isEmpty()) return 0
        val existing = DaoMachineOverride.find { DaoMachineOverrides.project eq show.id }
            .map { Triple(it.targetTable, it.recordUuid, it.fieldName) }.toSet()
        var copied = 0
        for (o in DaoMachineOverride.find { DaoMachineOverrides.project eq venueId }.filter { it.recordUuid in uuids }) {
            if (Triple(o.targetTable, o.recordUuid, o.fieldName) in existing) continue
            DaoMachineOverride.new {
                project = show
                targetTable = o.targetTable
                recordUuid = o.recordUuid
                fieldName = o.fieldName
                valueJson = o.valueJson
            }
            copied++
        }
        return copied
    }

    companion object {
        const val CODE_DETACH_NOT_A_SHOW = VenueUpdate.CODE_UPDATE_NOT_A_SHOW
        const val CODE_DETACH_BLOCKED = "VENUE_DETACH_BLOCKED"
    }
}

/** The records a detach copies into the show, each named as the Patch list and the rigging views name it. */
@Serializable
data class DetachRowsDto(
    /** `subnet:universe`. */
    val universes: List<String>,
    /** By key. */
    val patches: List<String>,
    val riggings: List<String>,
    val regions: List<String>,
    /** Scene elements, Fixed seating among them; never Preset seating, which the show already holds. */
    val elements: List<String>,
    val groups: List<String>,
    val viewpoints: List<String>,
    /** The venue's parks at the pin the show ran (neither re-parked nor released), as `1-005`. */
    val parks: List<String> = emptyList(),
)

/** What a detach did — or, with [dryRun], would do. */
@Serializable
data class DetachResultDto(
    val dryRun: Boolean,
    val projectId: Int,
    val venueId: Int,
    val venueName: String,
    /** The version the show was pinned to, and is flattened at. */
    val version: Int,
    /** The venue's installed version; above [version] when the show was behind. */
    val installed: Int? = null,
    /** How far behind the pin was: the venue's later changes do not come with it. */
    val behind: VenueBehind = VenueBehind.NONE,
    /** The origin the show now records: the venue and the version it was flattened at. */
    val detachedFrom: DetachedFromDto,
    val copied: DetachRowsDto,
    /** The venue patches whose overrides were written into the copy, as `key: facet, facet`. */
    val overridesApplied: List<String>,
    /** The show's hides, gone with the venue they hid records of. */
    val hidesDropped: Int,
    /** The show's recolours of venue patches and groups, written into the copies' own colour. */
    val coloursApplied: Int = 0,
    /** The show's orders of venue groups, written into the copies' member order (group-order plan D10). */
    val ordersApplied: Int = 0,
    /** The show's releases of venue parks, gone with the venue parks they released. */
    val parkReleasesDropped: Int = 0,
    /** The show's links re-pointed from the version onto its own rows, by `table.column`. */
    val relinked: Map<String, Int>,
    /** Links to a hidden record, which have nothing to point at once the venue is gone. */
    val dropped: List<UpdateItemDto>,
    /** `machine_overrides` rows copied from the venue's id to the show's (controller IPs, *Full detail*). */
    val machineOverridesCopied: Int,
    /** Scene images copied into the show's store (a dry run: that would be). */
    val imagesCopied: Int,
)

/**
 * Where a standalone show was detached from (venue-attach plan D1): the venue by its uuid, and its
 * project id and name when it is on this desk, and the version the show was pinned to.
 */
@Serializable
data class DetachedFromDto(
    val venueUuid: String,
    val version: Int,
    val venueId: Int? = null,
    val venueName: String? = null,
)
