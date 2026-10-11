package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.SizedIterable

/**
 * The one answer to "which rows make up this project's rig" (venue plan P4).
 *
 * Every project-scoped read of a rig table — patches and their placements, riggings, universe
 * configs, scene elements, stage regions, viewpoints, groups and their members — goes through a
 * method here rather than filtering on `project_id` itself. Today a project's rig is exactly its
 * own rows, so each method is the query its callers used to write. The point is that it stops
 * being: once a show sits on a venue (venue plan session 3), its rig is its own rows plus the
 * pinned snapshot's, less its hides and the snapshot's Preset seating, with facet overrides
 * applied, and that has to change in one place rather than at ~80 call sites.
 *
 * Two views, because a rig read and a write's lookup are different questions:
 *
 *  * the scope itself (`RigScope.of(project).patches()`) is the **effective** rig: what is loaded,
 *    drawn, listed, linked to, and checked for a clash (a key, a name, a head number);
 *  * [own] is the rows the project itself **owns**: what a write may change or delete, the next
 *    `sortOrder` of a row it adds, and what its own sync export carries. Writes stay
 *    project-specific — a show never edits a venue's row in place.
 *
 * In session 1 the two are the same rows. Each method takes an optional extra condition and
 * answers a `SizedIterable`, so a caller still orders, limits and maps as it did. Must be used
 * inside a transaction, as the queries it replaces were.
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

    fun patches(where: Op<Boolean>? = null): SizedIterable<DaoFixturePatch> =
        DaoFixturePatch.find(scoped(DaoFixturePatches.project, where))

    /**
     * [patches]' extra placements, by patch id, each list in stored order; a patch with none is
     * absent. The patches must come from this scope.
     */
    fun placementsByPatch(patches: Collection<DaoFixturePatch>): Map<Int, List<DaoFixturePatchPlacement>> =
        extraPlacementsByPatch(patches.map { it.id })

    fun riggings(where: Op<Boolean>? = null): SizedIterable<DaoRigging> =
        DaoRigging.find(scoped(DaoRiggings.project, where))

    fun universeConfigs(where: Op<Boolean>? = null): SizedIterable<DaoUniverseConfig> =
        DaoUniverseConfig.find(scoped(DaoUniverseConfigs.project, where))

    fun stageElements(where: Op<Boolean>? = null): SizedIterable<DaoStageElement> =
        DaoStageElement.find(scoped(DaoStageElements.project, where))

    fun stageRegions(where: Op<Boolean>? = null): SizedIterable<DaoStageRegion> =
        DaoStageRegion.find(scoped(DaoStageRegions.project, where))

    fun stageViewpoints(where: Op<Boolean>? = null): SizedIterable<DaoStageViewpoint> =
        DaoStageViewpoint.find(scoped(DaoStageViewpoints.project, where))

    fun groups(where: Op<Boolean>? = null): SizedIterable<DaoFixtureGroup> =
        DaoFixtureGroup.find(scoped(DaoFixtureGroups.project, where))

    /** [group]'s members, as the rig sees them. The group must come from this scope. */
    fun members(group: DaoFixtureGroup): SizedIterable<DaoFixtureGroupMember> = group.members

    private fun scoped(project: Column<EntityID<Int>>, where: Op<Boolean>?): Op<Boolean> {
        val mine = project eq projectId
        return if (where == null) mine else mine and where
    }

    companion object {
        fun of(project: DaoProject): RigScope = of(project.id)
        fun of(projectId: EntityID<Int>): RigScope = RigScope(projectId, ownOnly = false)
        fun of(projectId: Int): RigScope = of(EntityID(projectId, DaoProjects))
    }
}
