package uk.me.cormack.lighting7.testsupport

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.GroupableFixture
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import uk.me.cormack.lighting7.fixture.dmx.LedLightbar12PixelFixture
import uk.me.cormack.lighting7.models.BuskRigCellMode
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.show.Fixtures
import uk.me.cormack.lighting7.state.BuskRigRowSpec
import uk.me.cormack.lighting7.state.BuskRigSpec
import uk.me.cormack.lighting7.state.BuskRigTileSpec

/**
 * The two JSON fixtures under `src/test/resources/busk/` that pin the desk's rig order and
 * sub-selection rules — and that the client's `buskRig.test.ts` / `cellsSubSelection.ts` mirror
 * reads verbatim, so the two ends cannot drift. Each file carries its own fixtures, groups and named
 * rigs; this reader builds the live [Fixtures] and the [BuskRigSpec]s from them.
 */
object BuskRigFixture {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class FixtureSpec(val key: String, val type: String)

    @Serializable
    data class GroupSpec(val name: String, val members: List<String>)

    @Serializable
    data class TileSpec(
        val group: String? = null,
        val fixture: String? = null,
        val elementKey: String? = null,
        val cellMode: String = "PIPS",
        val cellSplit: Int? = null,
    )

    @Serializable
    data class RowSpec(val name: String, val tiles: List<TileSpec>)

    @Serializable
    data class RigOrderCase(
        val note: String,
        val rig: String,
        val stepsHeads: List<List<CueTargetDto>>,
        val stepsCells: List<List<CueTargetDto>>,
        val sortInput: List<CueTargetDto>,
        val sortOutput: List<CueTargetDto>,
    )

    @Serializable
    data class SubselectCase(
        val note: String,
        val rig: String,
        val mode: String,
        val selection: List<CueTargetDto>,
        val expected: List<CueTargetDto>,
    )

    @Serializable
    data class RigOrderFile(
        val fixtures: List<FixtureSpec>,
        val groups: List<GroupSpec>,
        val rigs: Map<String, List<RowSpec>>,
        val cases: List<RigOrderCase>,
    )

    @Serializable
    data class SubselectFile(
        val fixtures: List<FixtureSpec>,
        val groups: List<GroupSpec>,
        val rigs: Map<String, List<RowSpec>>,
        val cases: List<SubselectCase>,
    )

    fun rigOrderFile(): RigOrderFile = json.decodeFromString(read("/busk/rigOrder.fixture.json"))

    fun subselectFile(): SubselectFile = json.decodeFromString(read("/busk/subselect.fixture.json"))

    private fun read(path: String): String =
        checkNotNull(BuskRigFixture::class.java.getResourceAsStream(path)) { "missing test resource $path" }
            .bufferedReader().readText()

    /** The live register the fixture describes: fixtures then groups, in file order. */
    fun fixtures(specs: List<FixtureSpec>, groups: List<GroupSpec>): Fixtures {
        val universe = Universe(0, 0)
        val fixtures = Fixtures()
        fixtures.register {
            val byKey = HashMap<String, GroupableFixture>()
            var channel = 1
            for (spec in specs) {
                val fixture = when (spec.type) {
                    "hex" -> HexFixture(universe, spec.key, spec.key, firstChannel = channel)
                    "led-lightbar-12-pixel-48ch" -> LedLightbar12PixelFixture.Mode48Ch(universe, spec.key, spec.key, channel)
                    else -> error("fixture type ${spec.type} is not one the rig fixture knows")
                }
                channel += 64
                byKey[spec.key] = addFixture(fixture)
            }
            for (group in groups) {
                createGroup<GroupableFixture>(group.name) {
                    addSpread(group.members.map { byKey.getValue(it) })
                }
            }
        }
        return fixtures
    }

    fun rig(rows: List<RowSpec>): BuskRigSpec = BuskRigSpec(
        rows.map { row ->
            BuskRigRowSpec(
                row.name,
                row.tiles.map { tile ->
                    BuskRigTileSpec(
                        groupName = tile.group,
                        fixtureKey = tile.fixture,
                        elementKey = tile.elementKey,
                        cellMode = BuskRigCellMode.valueOf(tile.cellMode),
                        cellSplit = tile.cellSplit,
                    )
                },
            )
        },
    )
}
