package uk.me.cormack.lighting7.venue

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import uk.me.cormack.lighting7.models.DaoFixtureGroup
import uk.me.cormack.lighting7.models.DaoFixtureGroupMember
import uk.me.cormack.lighting7.models.DaoFixtureGroupMembers
import uk.me.cormack.lighting7.models.DaoFixtureGroups
import uk.me.cormack.lighting7.models.DaoFixturePatch
import uk.me.cormack.lighting7.models.DaoFixturePatches
import uk.me.cormack.lighting7.models.DaoMachineOverride
import uk.me.cormack.lighting7.models.DaoMachineOverrides
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoRiggings
import uk.me.cormack.lighting7.models.DaoStageElements
import uk.me.cormack.lighting7.models.DaoStageRegions
import uk.me.cormack.lighting7.models.DaoStageViewpoints
import uk.me.cormack.lighting7.models.DaoUniverseConfig
import uk.me.cormack.lighting7.models.DaoUniverseConfigs
import uk.me.cormack.lighting7.models.PatchFacet
import uk.me.cormack.lighting7.models.StageBounds
import uk.me.cormack.lighting7.models.StageElementKind
import uk.me.cormack.lighting7.models.StageElementLayer
import uk.me.cormack.lighting7.models.VenueLayer
import uk.me.cormack.lighting7.models.VenueParks
import uk.me.cormack.lighting7.models.detachedFrom
import uk.me.cormack.lighting7.models.extraPlacementsOf
import uk.me.cormack.lighting7.models.storedParamsObject
import uk.me.cormack.lighting7.models.venueFacetOf
import uk.me.cormack.lighting7.venue.VenueAttachPairing.GroupMemberRow
import uk.me.cormack.lighting7.venue.VenueAttachPairing.GroupRow
import uk.me.cormack.lighting7.venue.VenueAttachPairing.HallRow
import uk.me.cormack.lighting7.venue.VenueAttachPairing.MachineOverrideRow
import uk.me.cormack.lighting7.venue.VenueAttachPairing.PatchRow
import uk.me.cormack.lighting7.venue.VenueAttachPairing.PlacementRow
import uk.me.cormack.lighting7.venue.VenueAttachPairing.Rows
import uk.me.cormack.lighting7.venue.VenueAttachPairing.UniverseRow
import uk.me.cormack.lighting7.venue.VenueAttachPairing.Version
import java.util.UUID

/**
 * The three row sets [VenueAttachPairing] pairs (venue-attach plan §3.1), read inside the caller's
 * transaction: a standalone show's own rows, and a venue version's snapshot rows — less its Preset
 * seating, which no show's rig loads — with the venue project's machine overrides, since a venue
 * record's resolve against the venue's id (venue plan P4).
 */
object VenueAttachRows {

    /** [show]'s rows, its origin (when it records one) and venue [venueId]'s version [to] (the installed one when null). */
    data class Inputs(val show: Rows, val origin: Version?, val target: Version)

    /**
     * What a pairing of [show] against venue [venueId] reads. 404 `VENUE_VERSION_UNKNOWN` for a version
     * the venue lacks, 409 `VENUE_NOTHING_PUBLISHED` before v1.
     */
    fun inputs(show: DaoProject, venueId: Int, to: Int?): Inputs {
        val venue = VenueVersions.requireVenue(venueId)
        val versions = VenueVersions.versionsOf(venueId)
        if (versions.isEmpty()) {
            throw VenueException.conflict(VenueVersions.CODE_NOTHING_PUBLISHED, "${venue.name} has no published version to attach to")
        }
        val number = to ?: versions.first().number
        val target = versions.firstOrNull { it.number == number }
            ?: throw VenueException(HttpStatusCode.NotFound, VenueVersions.CODE_VERSION_UNKNOWN, "${venue.name} has no v$number")
        val origin = show.detachedFrom()?.let { (uuid, version) ->
            val originVenue = VenuePins.venueOf(uuid)
            val snapshot = VenuePins.snapshotOf(uuid, version)
            Version(uuid, originVenue?.name ?: "a venue not on this desk", version, snapshot?.let { ofSnapshot(it.id, originVenue!!.id) })
        }
        return Inputs(
            show = ofProject(show.id, show.id),
            origin = origin,
            target = Version(venue.uuid, venue.name, number, ofSnapshot(target.snapshotProject.id, venue.id)),
        )
    }

    /** A version snapshot's rows, its machine overrides read from its venue [venueId]. */
    fun ofSnapshot(snapshotId: EntityID<Int>, venueId: EntityID<Int>): Rows = ofProject(snapshotId, venueId, snapshot = true)

    /**
     * [projectId]'s own rows, with the machine overrides kept under [overridesOf]. A [snapshot] leaves
     * out its Preset seating (D6); a show's rows keep theirs, marked never to pair.
     */
    fun ofProject(projectId: EntityID<Int>, overridesOf: EntityID<Int>, snapshot: Boolean = false): Rows {
        val project = DaoProject[projectId]
        val patches = DaoFixturePatch.find { DaoFixturePatches.project eq projectId }
            .orderBy(DaoFixturePatches.sortOrder to SortOrder.ASC, DaoFixturePatches.id to SortOrder.ASC)
            .map(::patchRow)
        val elements = hallRows(DaoStageElements, DaoStageElements.project, projectId, DaoStageElements.uuid, DaoStageElements.name, DaoStageElements.sortOrder) { row, fields ->
            val seating = row[DaoStageElements.kind] == StageElementKind.SEATING.name
            val preset = seating && !VenueLayer.isFixedSeating(row[DaoStageElements.params])
            val params = storedParamsObject(row[DaoStageElements.params])
            val region = params[VenueAttachPairing.PLATFORM_REGION]?.let { (it as? JsonPrimitive)?.content }
            fields["params"] = JsonObject(params - VenueAttachPairing.PLATFORM_REGION).toString()
            Extra(
                refs = if (row[DaoStageElements.kind] == StageElementKind.PLATFORM.name) mapOf(VenueAttachPairing.PLATFORM_REGION to region?.let(::uuidOrNull)) else emptyMap(),
                pairable = row[DaoStageElements.layer] == StageElementLayer.VENUE.name && !preset,
                skip = snapshot && preset,
            )
        }
        val viewpoints = hallRows(DaoStageViewpoints, DaoStageViewpoints.project, projectId, DaoStageViewpoints.uuid, DaoStageViewpoints.name, DaoStageViewpoints.sortOrder) { row, fields ->
            fields.remove(DaoStageViewpoints.seatElementUuid.name)
            Extra(refs = mapOf(VenueAttachPairing.SEAT_ELEMENT to row[DaoStageViewpoints.seatElementUuid]))
        }
        return Rows(
            universes = DaoUniverseConfig.find { DaoUniverseConfigs.project eq projectId }.sortedBy { it.id.value }
                .map { UniverseRow(it.id.value, it.uuid, it.subnet, it.universe, it.controllerType) },
            patches = patches,
            riggings = hallRows(DaoRiggings, DaoRiggings.project, projectId, DaoRiggings.uuid, DaoRiggings.name, DaoRiggings.sortOrder),
            regions = hallRows(DaoStageRegions, DaoStageRegions.project, projectId, DaoStageRegions.uuid, DaoStageRegions.name, DaoStageRegions.sortOrder),
            elements = elements,
            viewpoints = viewpoints,
            groups = DaoFixtureGroup.find { DaoFixtureGroups.project eq projectId }.sortedBy { it.id.value }.map { g ->
                val members = DaoFixtureGroupMember.find { DaoFixtureGroupMembers.group eq g.id }
                    .sortedWith(compareBy({ it.sortOrder }, { it.id.value }))
                    .map { GroupMemberRow(it.fixturePatch.uuid, it.panOffset, it.tiltOffset) }
                GroupRow(g.id.value, g.uuid, g.name, members)
            },
            parks = VenueParks.rowsOf(projectId),
            machineOverrides = DaoMachineOverride.find { DaoMachineOverrides.project eq overridesOf }.sortedBy { it.id.value }
                .map { MachineOverrideRow(it.id.value, it.targetTable, it.recordUuid, it.fieldName, it.valueJson) },
            stage = StageBounds(project.stageWidthM, project.stageDepthM, project.stageHeightM),
        )
    }

    private fun patchRow(patch: DaoFixturePatch): PatchRow {
        val placements = extraPlacementsOf(patch)
        return PatchRow(
            id = patch.id.value,
            uuid = patch.uuid,
            key = patch.key,
            displayName = patch.displayName,
            fixtureTypeKey = patch.fixtureTypeKey,
            headNumber = patch.headNumber,
            placements = placements.map { PlacementRow(it.id.value, it.uuid, it.label) },
            facets = PatchFacet.entries.associateWith { venueFacetOf(patch, placements, it) },
            attributes = buildMap {
                put("displayName", patch.displayName)
                put("stageHidden", patch.stageHidden.toString())
                put("infrastructure", patch.infrastructure.toString())
                // A lantern derives its kind (`resolvePatchFocus`), so only a hand-set one is the patch's own.
                put("kindOverride", patch.kindOverride.takeIf { patch.lanternType == null })
                put("placementLabels", placements.joinToString("|") { it.label.orEmpty() })
            },
        )
    }

    /** What a kind's own reader adds to a hall row: its references, whether it pairs, and whether it is read at all. */
    private class Extra(val refs: Map<String, UUID?> = emptyMap(), val pairable: Boolean = true, val skip: Boolean = false)

    private val IGNORED = setOf("id", "project_id", "uuid", "name", "sort_order")

    /**
     * [table]'s rows of [projectId] as hall rows, every column but [IGNORED] a compared field — read
     * generically, so a column added later is compared without a change here.
     */
    private fun hallRows(
        table: IntIdTable,
        project: Column<EntityID<Int>>,
        projectId: EntityID<Int>,
        uuid: Column<UUID>,
        name: Column<String>,
        sortOrder: Column<Int>,
        extra: (ResultRow, MutableMap<String, String?>) -> Extra = { _, _ -> Extra() },
    ): List<HallRow> =
        table.selectAll().where { project eq projectId }
            .orderBy(sortOrder to SortOrder.ASC, table.id to SortOrder.ASC)
            .mapNotNull { row ->
                // A column's type parameter is only the Kotlin view of its values: any is read back as text.
                @Suppress("UNCHECKED_CAST")
                val fields = table.columns.filter { it.name !in IGNORED }
                    .associateTo(sortedMapOf<String, String?>()) { it.name to row[it as Column<Any?>]?.toString() }
                val more = extra(row, fields)
                if (more.skip) null
                else HallRow(row[table.id].value, row[uuid], row[name], fields.toMap(), more.refs, more.pairable)
            }

    private fun uuidOrNull(text: String): UUID? = runCatching { UUID.fromString(text) }.getOrNull()
}
