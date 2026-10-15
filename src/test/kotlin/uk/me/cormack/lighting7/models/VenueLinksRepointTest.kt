package uk.me.cormack.lighting7.models

import uk.me.cormack.lighting7.testsupport.inStack
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.After
import org.junit.Before
import org.junit.Test
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.testsupport.IntegrationTestDb
import uk.me.cormack.lighting7.testsupport.importWithKeptRecordUuids
import uk.me.cormack.lighting7.testsupport.seedRichProject
import uk.me.cormack.lighting7.testsupport.testAppConfig
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Each [VenueLinks] entry's re-point, as a venue Update will use it (venue plan P3): a show's rows
 * reference one version's rows, and move to the same-uuid rows of the next.
 *
 * `rich` is [seedRichProject], the "old version"; `next` is a copy of it with every record uuid
 * kept and a fresh project uuid, the "new version"; `show` is a third project whose rows link
 * into `rich` through every link a show can own — a patch on its universe and rigging, a
 * placement on its rigging, a group holding its patch, rig tiles on its group and patch, a cue
 * event firing its cannon, and scenery on its elements in a cue, a stack and a Look.
 */
class VenueLinksRepointTest {

    private lateinit var state: State
    private lateinit var rich: EntityID<Int>
    private lateinit var next: EntityID<Int>
    private lateinit var show: EntityID<Int>

    /** The two links whose owner is their own target, so a third project never owns a row of theirs. */
    private val selfOwned = setOf("fixture_patch_placements.fixture_patch_id", "fixture_group_members.group_id")

    @Before
    fun setUp() {
        IntegrationTestDb.reset()
        state = State(testAppConfig())
        val richId = seedRichProject(state)
        val nextId = importWithKeptRecordUuids(state, richId, "rich-next")
        transaction(state.database) {
            rich = DaoProject[richId].id
            next = DaoProject[nextId].id
            show = seedShowLinkingInto(DaoProject[richId]).id
        }
    }

    @After
    fun tearDown() {
        runCatching { state.shutdown() }
    }

    /** A project whose rows link into [venue]'s through every link a show can own. */
    private fun seedShowLinkingInto(venue: DaoProject): DaoProject {
        val rig = RigScope.of(venue)
        val project = DaoProject.new { name = "show"; isCurrent = false }
        val universe = rig.universeConfigs().first()
        val rigging = rig.riggings().first()
        val venuePatch = rig.patches().first()
        val cannon = rig.patches(DaoFixturePatches.key eq "cannon-1").single()
        val venueGroup = rig.groups().first()
        val elements = rig.stageElements().toList()

        val patch = DaoFixturePatch.new {
            this.project = project
            universeConfig = universe
            this.rigging = rigging
            fixtureTypeKey = "hex-fixture"; key = "show-hex"; displayName = "Show hex"; startChannel = 400
        }
        DaoFixturePatchPlacement.new { fixturePatch = patch; this.rigging = rigging; label = "SR" }
        val group = DaoFixtureGroup.new { this.project = project; name = "show-group" }
        DaoFixtureGroupMember.new { this.group = group; fixturePatch = venuePatch }
        val row = DaoBuskRigRow.new { this.project = project; name = "Show row" }
        DaoBuskRigTile.new { this.row = row; this.group = venueGroup; sortOrder = 0 }
        DaoBuskRigTile.new { this.row = row; this.patch = venuePatch; sortOrder = 1 }
        val stack = DaoCueStack.new { this.project = project; name = "Show stack"; type = CueStackType.STACK.name }
        val cue = DaoCue.new { this.project = project;  name = "Show cue" }.inStack(stack)
        DaoCueEvent.new { this.cue = cue; this.patch = cannon; trigger = "output1"; offset = Duration.ZERO }
        DaoCueSceneryRow.new { this.cue = cue; element = elements[0]; stateJson = "{}" }
        DaoCueStackSceneryRow.new { this.stack = stack; element = elements[1]; stateJson = "{}" }
        val look = DaoLook.new { this.project = project; name = "Show look" }
        DaoLookSceneryRow.new { this.look = look; element = elements[2]; stateJson = "{}" }
        return project
    }

    /** Every link value in the database: (link, linking row id) → the uuid and project of the row it points at. */
    private fun snapshot(): Map<Pair<String, Int>, Pair<UUID, Int>> = VenueLinks.ALL.flatMap { link ->
        val uuidColumn = link.target.columns.single { it.name == "uuid" }
        val projectColumn = link.target.columns.single { it.name == "project_id" }
        val targets = link.target.select(link.target.id, uuidColumn, projectColumn).associate {
            it[link.target.id].value to (it[uuidColumn] as UUID to (it[projectColumn] as EntityID<*>).value as Int)
        }
        link.table.select(link.table.id, link.column).mapNotNull { r ->
            val target = r[link.column]?.value ?: return@mapNotNull null
            (link.name to r[link.table.id].value) to targets.getValue(target)
        }
    }.toMap()

    /** Which project owns each linking row, through each link's [VenueLinks.Owner]. */
    private fun ownedRows(project: EntityID<Int>): Set<Pair<String, Int>> = VenueLinks.ALL.flatMap { link ->
        link.table.select(link.table.id).where { link.owner.ownedBy(project) }.map { link.name to it[link.table.id].value }
    }.toSet()

    @Test
    fun `every link a show owns moves to the same-uuid row of the next version, and nothing else moves`() {
        transaction(state.database) {
            val before = snapshot()
            val showRows = ownedRows(show)
            val outcome = VenueLinks.repointAll(show, rich, next)
            val after = snapshot()

            for (link in VenueLinks.ALL) {
                val result = outcome.getValue(link.name)
                assertEquals(emptyList(), result.unmatched, "${link.name}: every uuid is in the next version")
                if (link.name in selfOwned) {
                    assertEquals(0, result.moved, "${link.name}: a show owns no row through its own target")
                } else {
                    assertTrue(result.moved > 0, "${link.name}: the show's rows should exercise it")
                }
            }
            for ((row, was) in before) {
                val now = after.getValue(row)
                if (row in showRows && was.second == rich.value) {
                    assertEquals(was.first to next.value, now, "$row should follow its uuid into the next version")
                } else {
                    assertEquals(was, now, "$row is not the show's link into the old version, so it stays")
                }
            }
            assertEquals(before.keys, after.keys)
        }
    }

    @Test
    fun `a project's own placements and members move onto the same-uuid patches and groups`() {
        transaction(state.database) {
            val placements = VenueLinks.ALL.single { it.name == "fixture_patch_placements.fixture_patch_id" }
            val members = VenueLinks.ALL.single { it.name == "fixture_group_members.group_id" }
            val before = snapshot()

            val movedPlacements = placements.repoint(rich, rich, next)
            val movedMembers = members.repoint(rich, rich, next)

            val after = snapshot()
            for ((link, result) in listOf(placements to movedPlacements, members to movedMembers)) {
                val richRows = before.filter { (k, v) -> k.first == link.name && v.second == rich.value }
                assertTrue(richRows.isNotEmpty())
                assertEquals(richRows.size, result.moved)
                for ((row, was) in richRows) assertEquals(was.first to next.value, after.getValue(row))
            }
        }
    }

    @Test
    fun `a link whose uuid the next version lacks is reported and left where it was`() {
        transaction(state.database) {
            val dropped = DaoRigging.new { project = DaoProject[rich]; name = "Dropped in next"; sortOrder = 9 }
            val showPatch = RigScope.of(show).patches().single()
            showPatch.rigging = dropped

            val link = VenueLinks.ALL.single { it.name == "fixture_patches.rigging_id" }
            val result = link.repoint(show, rich, next)

            assertEquals(0, result.moved)
            assertEquals(listOf(showPatch.id.value), result.unmatched)
            assertEquals(dropped.id, DaoFixturePatch[showPatch.id].rigging?.id)
        }
    }

    @Test
    fun `re-pointing from a project the show does not link into moves nothing`() {
        transaction(state.database) {
            val before = snapshot()
            val outcome = VenueLinks.repointAll(show, next, rich)
            assertTrue(outcome.values.all { it.moved == 0 && it.unmatched.isEmpty() })
            assertEquals(before, snapshot())
        }
    }
}
