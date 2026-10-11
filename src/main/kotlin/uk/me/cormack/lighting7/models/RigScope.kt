package uk.me.cormack.lighting7.models

import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.SizedIterable
import org.jetbrains.exposed.v1.jdbc.select
import java.util.UUID

/**
 * The one answer to "which rows make up this project's rig" (venue plan P4).
 *
 * Every project-scoped read of a rig table — patches and their placements, riggings, universe
 * configs, scene elements, stage regions, viewpoints, groups and their members — goes through a
 * method here rather than filtering on `project_id` itself. For a standalone show, a venue and a
 * version, a project's rig is exactly its own rows. For a show **on a venue** (venue plan session
 * 3, §3.3) it is its own rows plus the pinned snapshot's, less the show's hides (`show_hides`) and
 * less the snapshot's Preset seating — one place, rather than ~80 call sites. Facet overrides
 * (session 5) will apply here too.
 *
 * Two views, because a rig read and a write's lookup are different questions:
 *
 *  * the scope itself (`RigScope.of(project).patches()`) is the **effective** rig: what is loaded,
 *    drawn, listed, linked to, and checked for a clash (a key, a name, a head number);
 *  * [own] is the rows the project itself **owns**: what a write may change or delete, the next
 *    `sortOrder` of a row it adds, and what its own sync export carries. Writes stay
 *    project-specific — a show never edits a venue's row in place.
 *
 * For a project with no pin the two are the same rows. Each method takes an optional extra
 * condition and answers a `SizedIterable`, so a caller still orders, limits and maps as it did. Must
 * be used inside a transaction, as the queries it replaces were: the pin is resolved on first use,
 * inside the caller's transaction, and held for the scope's life — make a scope per transaction.
 *
 * `docs/fixtures-engineering.md` §"The rig scope" has the rule; `RigScopeEquivalenceTest` pins
 * each method to the query it replaced.
 */
class RigScope private constructor(
    val projectId: EntityID<Int>,
    private val ownOnly: Boolean,
) {
    /** The rows this project itself owns: the target of a write, never the effective rig. */
    val own: RigScope get() = if (ownOnly) this else RigScope(projectId, ownOnly = true)

    /**
     * The venue this project's rig sits on: its pin resolved to the snapshot project, with the
     * show's hides. Null for a standalone show, a venue, a version, a pin whose snapshot this desk
     * does not hold (the rig is then the show's own rows), and always for [own].
     */
    val venue: VenueLayer? by lazy { if (ownOnly) null else VenueLayer.resolve(projectId) }

    fun patches(where: Op<Boolean>? = null): SizedIterable<DaoFixturePatch> =
        DaoFixturePatch.find(scoped(DaoFixturePatches.project, DaoFixturePatches.uuid, HideKind.PATCH, where))

    /**
     * [patches]' extra placements, by patch id, each list in stored order; a patch with none is
     * absent. The patches must come from this scope.
     */
    fun placementsByPatch(patches: Collection<DaoFixturePatch>): Map<Int, List<DaoFixturePatchPlacement>> =
        extraPlacementsByPatch(patches.map { it.id })

    fun riggings(where: Op<Boolean>? = null): SizedIterable<DaoRigging> =
        DaoRigging.find(scoped(DaoRiggings.project, DaoRiggings.uuid, HideKind.RIGGING, where))

    /** A venue's universes are never hidden: a show patches onto them. */
    fun universeConfigs(where: Op<Boolean>? = null): SizedIterable<DaoUniverseConfig> =
        DaoUniverseConfig.find(scoped(DaoUniverseConfigs.project, null, null, where))

    /** The scene less the snapshot's Preset seating: a show seats its own copy of a preset (D6). */
    fun stageElements(where: Op<Boolean>? = null): SizedIterable<DaoStageElement> {
        val layer = venue
        val presetSeating = layer?.presetSeatingIds.orEmpty()
        val base = scoped(DaoStageElements.project, DaoStageElements.uuid, HideKind.ELEMENT, where)
        return DaoStageElement.find(if (presetSeating.isEmpty()) base else base and (DaoStageElements.id notInList presetSeating))
    }

    fun stageRegions(where: Op<Boolean>? = null): SizedIterable<DaoStageRegion> =
        DaoStageRegion.find(scoped(DaoStageRegions.project, DaoStageRegions.uuid, HideKind.REGION, where))

    fun stageViewpoints(where: Op<Boolean>? = null): SizedIterable<DaoStageViewpoint> =
        DaoStageViewpoint.find(scoped(DaoStageViewpoints.project, DaoStageViewpoints.uuid, HideKind.VIEWPOINT, where))

    fun groups(where: Op<Boolean>? = null): SizedIterable<DaoFixtureGroup> =
        DaoFixtureGroup.find(scoped(DaoFixtureGroups.project, DaoFixtureGroups.uuid, HideKind.GROUP, where))

    /**
     * [group]'s members, as the rig sees them: less any venue patch the show hides, which a venue
     * group (and a show group) may still hold. The group must come from this scope.
     */
    fun members(group: DaoFixtureGroup): SizedIterable<DaoFixtureGroupMember> {
        val hidden = venue?.hiddenPatchIds.orEmpty()
        if (hidden.isEmpty()) return group.members
        return DaoFixtureGroupMember.find {
            (DaoFixtureGroupMembers.group eq group.id) and (DaoFixtureGroupMembers.fixturePatch notInList hidden)
        }
    }

    /**
     * Whose a rig row of project [rowProject] is, seen from this project: [RigOwner.SHOW] for its
     * own, [RigOwner.VENUE] for the pinned snapshot's — hidden or not — and null for any other
     * project's, which a route answers as not found. A write by id goes ahead only on `SHOW`; on
     * `VENUE` it is refused with `VENUE_OWNED` (`venue/VenueRigGuard.kt`).
     */
    fun ownerOf(rowProject: EntityID<Int>): RigOwner? = when (rowProject) {
        projectId -> RigOwner.SHOW
        venue?.snapshotId -> RigOwner.VENUE
        else -> null
    }

    /** Whether the show hides the venue record [uuid] of [kind]. */
    fun isHidden(kind: HideKind, uuid: UUID): Boolean = venue?.hides?.get(kind)?.contains(uuid) == true

    /**
     * The project a machine-local override of a rig row of [rowProject] is keyed by (venue plan P4):
     * a venue record's resolves against the **venue** project's id, whichever version holds it, so a
     * universe's controller IP is the same in the draft, in every version and in every show on it.
     * A row of a version read directly (the Patch list's removed rows) resolves against its venue too.
     */
    fun overrideProjectId(rowProject: EntityID<Int>): Int {
        venue?.let { if (rowProject == it.snapshotId) return it.venueProjectId.value }
        return venueOfSnapshot(rowProject) ?: rowProject.value
    }

    /**
     * The stage's size: a show on a venue reads the venue's, as the pinned version froze it (§3.2);
     * anyone else its own columns.
     */
    fun stageBounds(): StageBounds {
        val source = venue?.let { DaoProject[it.snapshotId] } ?: DaoProject[projectId]
        return StageBounds(source.stageWidthM, source.stageDepthM, source.stageHeightM)
    }

    private fun scoped(
        project: Column<EntityID<Int>>,
        uuid: Column<UUID>?,
        kind: HideKind?,
        where: Op<Boolean>?,
    ): Op<Boolean> {
        val mine = project eq projectId
        val layer = venue
        val rows = if (layer == null) {
            mine
        } else {
            val hidden = kind?.let { layer.hides[it] }.orEmpty()
            val venueRows = if (uuid == null || hidden.isEmpty()) {
                project eq layer.snapshotId
            } else {
                (project eq layer.snapshotId) and (uuid notInList hidden)
            }
            mine or venueRows
        }
        return if (where == null) rows else rows and where
    }

    companion object {
        fun of(project: DaoProject): RigScope = of(project.id)
        fun of(projectId: EntityID<Int>): RigScope = RigScope(projectId, ownOnly = false)
        fun of(projectId: Int): RigScope = of(EntityID(projectId, DaoProjects))

        /** The venue project a version snapshot [projectId] belongs to, or null when it is none. */
        private fun venueOfSnapshot(projectId: EntityID<Int>): Int? =
            DaoVenueVersion.find { DaoVenueVersions.snapshotProject eq projectId }.firstOrNull()?.venueProject?.id?.value
    }
}

/** Whose a row of a show's rig is (venue plan §3.1): the show's own, or its venue's. */
enum class RigOwner { SHOW, VENUE }

/** A stage's size in metres, each null when unset. */
data class StageBounds(val widthM: Double?, val depthM: Double?, val heightM: Double?)

/**
 * A show's venue as its rig sees it: the pin (`projects.venue_uuid` + `.venue_version`) resolved to
 * the venue project and the version's snapshot, plus the show's hides. Built inside a transaction.
 */
class VenueLayer(
    val venueProjectId: EntityID<Int>,
    val venueUuid: UUID,
    val venueName: String,
    val version: Int,
    val snapshotId: EntityID<Int>,
    /** The show's hides, by kind. */
    val hides: Map<HideKind, Set<UUID>>,
) {
    /** The snapshot's patches the show hides, by id: what a group's members are filtered by. */
    val hiddenPatchIds: List<EntityID<Int>> by lazy {
        val uuids = hides[HideKind.PATCH].orEmpty()
        if (uuids.isEmpty()) emptyList()
        else DaoFixturePatches.select(DaoFixturePatches.id, DaoFixturePatches.uuid)
            .where { DaoFixturePatches.project eq snapshotId }
            .filter { it[DaoFixturePatches.uuid] in uuids }
            .map { it[DaoFixturePatches.id] }
    }

    /** The snapshot's Preset seating (absent `block` is Preset), by id: never loaded into a show (D6). */
    val presetSeatingIds: List<EntityID<Int>> by lazy {
        DaoStageElement.find {
            (DaoStageElements.project eq snapshotId) and (DaoStageElements.kind eq StageElementKind.SEATING.name)
        }.filter { !isFixedSeating(it.params) }.map { it.id }
    }

    companion object {
        /** [showId]'s venue layer, or null when it pins nothing or its version is not on this desk. */
        fun resolve(showId: EntityID<Int>): VenueLayer? {
            val show = DaoProject.findById(showId) ?: return null
            if (show.kind != ProjectKind.SHOW) return null
            val venueUuid = show.venueUuid ?: return null
            val number = show.venueVersion ?: return null
            val venue = DaoProject.find {
                (DaoProjects.uuid eq venueUuid) and (DaoProjects.kind eq ProjectKind.VENUE)
            }.firstOrNull() ?: return null
            val version = DaoVenueVersion.find {
                (DaoVenueVersions.venueProject eq venue.id) and (DaoVenueVersions.number eq number)
            }.firstOrNull() ?: return null
            val hides = DaoShowHide.find { DaoShowHides.project eq showId }
                .groupBy({ it.recordKind }, { it.recordUuid })
                .mapValues { it.value.toSet() }
            return VenueLayer(venue.id, venueUuid, venue.name, number, version.snapshotProject.id, hides)
        }

        /** Whether a seating element's stored params mark it Fixed (venue plan D6); absent is Preset. */
        fun isFixedSeating(paramsText: String): Boolean =
            (storedParamsObject(paramsText)["block"] as? JsonPrimitive)?.content == SeatingBlock.FIXED.name
    }
}
