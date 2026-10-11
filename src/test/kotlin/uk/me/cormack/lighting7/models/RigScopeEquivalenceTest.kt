package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.After
import org.junit.Before
import org.junit.Test
import uk.me.cormack.lighting7.routes.resolveRiggingForProject
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.testsupport.IntegrationTestDb
import uk.me.cormack.lighting7.testsupport.importWithKeptRecordUuids
import uk.me.cormack.lighting7.testsupport.seedRichProject
import uk.me.cormack.lighting7.testsupport.testAppConfig
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Pins every [RigScope] method to the query its call sites wrote before the scope existed (venue
 * plan session 1): for a project not on a venue, the rig is exactly its own rows, so moving ~80
 * reads behind the scope changed no behaviour.
 *
 * Across [seedRichProject] — which populates every rig table with more than one row — plus a
 * second copy of it that keeps every record uuid, so a scope that leaked another project's rows,
 * or matched by uuid alone, would show here. Each comparison is of the ordered id lists the two
 * queries answer, so the scope must also keep the order the old query had.
 */
class RigScopeEquivalenceTest {

    private lateinit var state: State
    private var rich = 0
    private var copy = 0

    @Before
    fun setUp() {
        IntegrationTestDb.reset()
        state = State(testAppConfig())
        rich = seedRichProject(state)
        copy = importWithKeptRecordUuids(state, rich, "rich-copy")
    }

    @After
    fun tearDown() {
        runCatching { state.shutdown() }
    }

    private fun Iterable<IntEntity>.ids() = map { it.id.value }

    /** Each kind's old query beside its scope method, for [projectId]: (kind, old ids, scope ids). */
    private fun pairs(projectId: Int, scope: RigScope): List<Triple<String, List<Int>, List<Int>>> {
        val p = EntityID(projectId, DaoProjects)
        return listOf(
            Triple("patches", DaoFixturePatch.find { DaoFixturePatches.project eq p }.ids(), scope.patches().ids()),
            Triple("riggings", DaoRigging.find { DaoRiggings.project eq p }.ids(), scope.riggings().ids()),
            Triple(
                "universeConfigs",
                DaoUniverseConfig.find { DaoUniverseConfigs.project eq p }.ids(), scope.universeConfigs().ids(),
            ),
            Triple(
                "stageElements",
                DaoStageElement.find { DaoStageElements.project eq p }.ids(), scope.stageElements().ids(),
            ),
            Triple(
                "stageRegions",
                DaoStageRegion.find { DaoStageRegions.project eq p }.ids(), scope.stageRegions().ids(),
            ),
            Triple(
                "stageViewpoints",
                DaoStageViewpoint.find { DaoStageViewpoints.project eq p }.ids(), scope.stageViewpoints().ids(),
            ),
            Triple("groups", DaoFixtureGroup.find { DaoFixtureGroups.project eq p }.ids(), scope.groups().ids()),
        )
    }

    @Test
    fun `every scope method answers exactly the rows its old query did`() = transaction(state.database) {
        for (projectId in listOf(rich, copy)) {
            for (scope in listOf(RigScope.of(projectId), RigScope.of(projectId).own)) {
                for ((kind, old, scoped) in pairs(projectId, scope)) {
                    assertTrue(old.isNotEmpty(), "$kind: the rich fixture should populate it")
                    assertEquals(old, scoped, "$kind of project $projectId (own=${scope === scope.own})")
                }
            }
        }
    }

    @Test
    fun `an extra condition narrows the scope as the old and-ed query did`() = transaction(state.database) {
        val p = EntityID(rich, DaoProjects)
        val scope = RigScope.of(rich)
        assertEquals(
            DaoFixturePatch.find { (DaoFixturePatches.project eq p) and (DaoFixturePatches.key eq "hex-2") }.ids(),
            scope.patches(DaoFixturePatches.key eq "hex-2").ids(),
        )
        assertEquals(1, scope.patches(DaoFixturePatches.key eq "hex-2").count())
        assertEquals(
            DaoRigging.find { (DaoRiggings.project eq p) and (DaoRiggings.name eq "FOH Truss") }.ids(),
            scope.riggings(DaoRiggings.name eq "FOH Truss").ids(),
        )
        // And an order the caller adds still applies.
        assertEquals(
            DaoFixturePatch.find { DaoFixturePatches.project eq p }
                .orderBy(DaoFixturePatches.sortOrder to SortOrder.DESC).ids(),
            scope.patches().orderBy(DaoFixturePatches.sortOrder to SortOrder.DESC).ids(),
        )
    }

    @Test
    fun `placements and members are each patch's and group's own`() = transaction(state.database) {
        val scope = RigScope.of(rich)
        val patches = scope.patches().toList()
        val byPatch = scope.placementsByPatch(patches)
        assertTrue(byPatch.isNotEmpty())
        assertEquals(
            patches.associate { it.id.value to extraPlacementsOf(it).ids() }.filterValues { it.isNotEmpty() },
            byPatch.mapValues { it.value.ids() },
        )
        val groups = scope.groups().toList()
        for (g in groups) {
            assertEquals(
                DaoFixtureGroupMember.find { DaoFixtureGroupMembers.group eq g.id }.ids().sorted(),
                scope.members(g).ids().sorted(),
            )
        }
        assertTrue(groups.any { scope.members(it).count() > 0 })
    }

    @Test
    fun `a rigging named by uuid resolves in its own project, not the first project holding that uuid`() {
        transaction(state.database) {
            val original = RigScope.of(rich).riggings().first()
            val sameUuid = RigScope.of(copy).riggings(DaoRiggings.uuid eq original.uuid).single()
            assertNotEquals(original.id, sameUuid.id)
            // The copy's rigging, though the original — the lower id — holds the same uuid: the
            // unscoped lookup found the original first and answered null for the copy.
            assertEquals(sameUuid.id, resolveRiggingForProject(DaoProject[copy], original.uuid.toString())?.id)
            assertEquals(original.id, resolveRiggingForProject(DaoProject[rich], original.uuid.toString())?.id)
        }
    }
}
