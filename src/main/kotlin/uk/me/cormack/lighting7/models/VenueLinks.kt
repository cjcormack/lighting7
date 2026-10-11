package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID

/**
 * Every foreign key that points into a table a venue can own (venue plan P3).
 *
 * A show on a venue will reference rows of its pinned snapshot by plain int FK — a show patch hung
 * on a venue rigging, a show group holding a venue patch, a cue's scenery on a venue drape. Moving
 * a show to another version (Update), moving rows into a venue (Extract) or flattening one into a
 * show (Detach) each change which project those rows live in, keeping every record's uuid. So the
 * links are **re-pointed by uuid** rather than turned into uuid columns, which would be a
 * non-additive change to eight tables on a schema with no migrations: each [Link] here can move
 * one project's references from the rows of one project to the same-uuid rows of another.
 *
 * The registry is complete by test, not by care: `VenueLinksCoverageTest` reads every table in
 * [ALL_TABLES] through Exposed's foreign-key metadata and fails, naming the column, on a foreign
 * key into a [VENUE_OWNABLE] table that has no entry here — as `SyncCoverageTest` does for sync.
 *
 * Nothing calls [Link.repoint] in production yet; venue plan sessions 2, 6 and 7 will.
 * `docs/fixtures-engineering.md` §"The rig scope" has the rule.
 */
object VenueLinks {

    /** The tables a venue can own: the rig, as [RigScope] reads it. */
    val VENUE_OWNABLE: Set<Table> = setOf(
        DaoFixturePatches, DaoFixturePatchPlacements, DaoRiggings, DaoUniverseConfigs,
        DaoStageElements, DaoStageRegions, DaoStageViewpoints, DaoFixtureGroups, DaoFixtureGroupMembers,
    )

    /**
     * How a row of a link's table belongs to a project: through [column], either the table's own
     * `project_id` ([parentProject] null) or a reference to a parent whose `project_id` is
     * [parentProject] (a placement through its patch, a cue's scenery through its cue).
     */
    class Owner(val column: Column<EntityID<Int>>, val parentProject: Column<EntityID<Int>>? = null) {
        /** The condition selecting the rows [project] owns. Must be used inside a transaction. */
        fun ownedBy(project: EntityID<Int>): Op<Boolean> {
            val parent = parentProject ?: return column eq project
            val parentTable = parent.table as IntIdTable
            val parentIds = parentTable.select(parentTable.id).where { parent eq project }.map { it[parentTable.id] }
            return column inList parentIds
        }
    }

    /** What [Link.repoint] did: rows moved, and rows left as they were because [to] had no row of that uuid. */
    data class Repointed(val moved: Int, val unmatched: List<Int>)

    /**
     * One foreign key into a venue-ownable table, [column], whose target is a row of [target].
     *
     * [column] is held as nullable whatever its declared type, so one [repoint] serves a
     * `reference` and an `optReference` alike; it only ever writes a non-null id.
     */
    class Link(
        val column: Column<EntityID<Int>?>,
        val target: IntIdTable,
        private val targetProject: Column<EntityID<Int>>,
        private val targetUuid: Column<UUID>,
        val owner: Owner,
    ) {
        val table: IntIdTable get() = column.table as IntIdTable

        /** `table.column`, as the guard test and a failure name it. */
        val name: String get() = "${table.tableName}.${column.name}"

        /**
         * For the rows [owner] owns in [project], move this link from each row of project [from]
         * to the row of the same uuid in project [to]. A link whose uuid has no single row in [to]
         * is left as it was and reported in [Repointed.unmatched] (by the linking row's id) —
         * deciding what a dead reference becomes is the caller's, not this registry's. Links into
         * any other project are not touched. Must run inside the caller's transaction.
         */
        fun repoint(project: EntityID<Int>, from: EntityID<Int>, to: EntityID<Int>): Repointed {
            val fromUuids = target.select(target.id, targetUuid).where { targetProject eq from }
                .associate { it[target.id].value to it[targetUuid] }
            if (fromUuids.isEmpty()) return Repointed(0, emptyList())
            // A uuid held twice in [to] is ambiguous, so it matches nothing rather than either row.
            val toIds = target.select(target.id, targetUuid).where { targetProject eq to }
                .groupBy({ it[targetUuid] }, { it[target.id].value })
                .filterValues { it.size == 1 }
                .mapValues { it.value.single() }
            val fromIds = fromUuids.keys.map { EntityID(it, target) }
            val rows = table.select(table.id, column)
                .where { owner.ownedBy(project) and (column inList fromIds) }
                .map { it[table.id].value to it[column]!!.value }
            var moved = 0
            val unmatched = mutableListOf<Int>()
            for ((rowId, targetId) in rows) {
                val newId = toIds[fromUuids.getValue(targetId)]
                if (newId == null) {
                    unmatched += rowId
                    continue
                }
                table.update({ table.id eq rowId }) { it[column] = EntityID(newId, target) }
                moved++
            }
            return Repointed(moved, unmatched)
        }
    }

    /**
     * Widen a non-null reference to the nullable column type [Link] holds. Safe: a column's type
     * parameter is only the Kotlin view of its values, and [Link.repoint] never writes a null.
     */
    @Suppress("UNCHECKED_CAST")
    private fun nullable(column: Column<EntityID<Int>>): Column<EntityID<Int>?> = column as Column<EntityID<Int>?>

    private fun link(column: Column<EntityID<Int>?>, target: IntIdTable, owner: Owner): Link = when (target) {
        DaoFixturePatches -> Link(column, target, DaoFixturePatches.project, DaoFixturePatches.uuid, owner)
        DaoUniverseConfigs -> Link(column, target, DaoUniverseConfigs.project, DaoUniverseConfigs.uuid, owner)
        DaoRiggings -> Link(column, target, DaoRiggings.project, DaoRiggings.uuid, owner)
        DaoFixtureGroups -> Link(column, target, DaoFixtureGroups.project, DaoFixtureGroups.uuid, owner)
        DaoStageElements -> Link(column, target, DaoStageElements.project, DaoStageElements.uuid, owner)
        else -> error("No venue-link target ${target.tableName}")
    }

    private val ownPatch = Owner(DaoFixturePatches.project)
    private val viaPatch = Owner(DaoFixturePatchPlacements.fixturePatch, DaoFixturePatches.project)
    private val viaGroup = Owner(DaoFixtureGroupMembers.group, DaoFixtureGroups.project)
    private val viaRigRow = Owner(DaoBuskRigTiles.row, DaoBuskRigRows.project)
    private val viaCue = Owner(DaoCueEvents.cue, DaoCues.project)

    /**
     * The twelve links, in eight tables.
     *
     * A placement belongs to its patch and a member to its group, so for
     * `fixture_patch_placements.fixture_patch_id` and `fixture_group_members.group_id` the owner
     * *is* the link's target: re-pointing them moves one project's placements (members) onto the
     * same-uuid patches (groups) of another, and only `project == from` matches anything. They are
     * last, so that [repointAll] moves a placement's rigging (a member's patch) while the row is
     * still the project's, before the row itself changes hands.
     */
    val ALL: List<Link> = listOf(
        link(nullable(DaoFixturePatches.universeConfig), DaoUniverseConfigs, ownPatch),
        link(DaoFixturePatches.rigging, DaoRiggings, ownPatch),
        link(DaoFixturePatchPlacements.rigging, DaoRiggings, viaPatch),
        link(nullable(DaoFixtureGroupMembers.fixturePatch), DaoFixturePatches, viaGroup),
        link(DaoBuskRigTiles.group, DaoFixtureGroups, viaRigRow),
        link(DaoBuskRigTiles.patch, DaoFixturePatches, viaRigRow),
        link(nullable(DaoCueEvents.patch), DaoFixturePatches, viaCue),
        link(nullable(DaoCueScenery.element), DaoStageElements, Owner(DaoCueScenery.cue, DaoCues.project)),
        link(nullable(DaoCueStackScenery.element), DaoStageElements, Owner(DaoCueStackScenery.stack, DaoCueStacks.project)),
        link(nullable(DaoLookScenery.element), DaoStageElements, Owner(DaoLookScenery.look, DaoLooks.project)),
        link(nullable(DaoFixturePatchPlacements.fixturePatch), DaoFixturePatches, viaPatch),
        link(nullable(DaoFixtureGroupMembers.group), DaoFixtureGroups, viaGroup),
    )

    /**
     * Re-point every link for the rows [project] owns, from project [from] to project [to], and
     * answer each link's outcome by [Link.name]. Must run inside the caller's transaction, so a
     * failure part-way leaves nothing moved.
     */
    fun repointAll(project: EntityID<Int>, from: EntityID<Int>, to: EntityID<Int>): Map<String, Repointed> =
        ALL.associate { it.name to it.repoint(project, from, to) }
}
