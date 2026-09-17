package uk.me.cormack.lighting7.state

import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fixture.group.MultiElementFixture
import uk.me.cormack.lighting7.fx.SpreadOver
import uk.me.cormack.lighting7.models.BuskRigCellMode
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.show.Fixtures

/** The busk rig as the desk reads it: rows of tiles, resolved by name and key against the live patch. */
data class BuskRigSpec(val rows: List<BuskRigRowSpec> = emptyList()) {
    val isEmpty: Boolean get() = rows.isEmpty()
}

data class BuskRigRowSpec(val name: String, val tiles: List<BuskRigTileSpec>)

/**
 * One tile: exactly one of [groupName] / [fixtureKey]. [elementKey] only with a fixture, for a tile
 * that *is* one cell; [cellMode] and [cellSplit] as stored (busk-further plan D3).
 */
data class BuskRigTileSpec(
    val groupName: String? = null,
    val fixtureKey: String? = null,
    val elementKey: String? = null,
    val cellMode: BuskRigCellMode = BuskRigCellMode.PIPS,
    val cellSplit: Int? = null,
)

/**
 * The **effective rig order** — the one sequence the desk walks for `selection.subselect`'s *Next* /
 * *Prev* and for a spread's `LINEAR` order (busk-further plan §3.3, D9, D12).
 *
 * Rows in order, tiles in order, and each tile as the operator would press it:
 *
 * - a **group** tile is one step, the group itself, and places its members in member order — over
 *   [SpreadOver.CELLS] it is its members' units instead, so a cell reached through a group tile
 *   steps like one reached through a fixture tile;
 * - a **fixture** tile is one step (`PIPS` / `WHOLE`), or one step per cell (`PER_CELL`), or
 *   `cellSplit` steps each a contiguous run of cells (`HALVES`); over [SpreadOver.CELLS] every
 *   multi-head fixture is its cells whatever its mode, because a *Next* pressed with a cell selected
 *   should step one cell (the plan's §9);
 * - a tile carrying an **element key** is that one cell;
 * - a tile naming nothing the patch has is absent;
 * - a step whose targets an earlier step already produced is not repeated (an empty rig over cells
 *   reaches every cell once through its group and again as a fixture; a cell tile beside a
 *   `PER_CELL` tile of its parent names a cell already stepped), so *Next* never lands on the same
 *   thing twice.
 *
 * **An empty rig is today's band** (D1): every group, then every fixture, in the order the two list
 * routes answer them — the client's `effectiveRig` draws exactly that, and `BuskRigOrderTest`'s
 * fixture is what `buskRig.test.ts` pins the mirror against.
 *
 * Element keys are opaque: every cell is read off `MultiElementFixture.elements`, never parsed.
 * Pure over a [BuskRigSpec] and a [Fixtures]; the reader that builds the spec from the tables lives
 * with the route (`routes/buskRig.kt`).
 */
class BuskRigOrder(private val fixtures: Fixtures, private val rig: BuskRigSpec) {

    /**
     * The steps of the band, each the targets one press on it selects, at [over]'s granularity.
     */
    fun steps(over: SpreadOver): List<List<CueTargetDto>> {
        val out = LinkedHashSet<List<CueTargetDto>>()
        if (rig.isEmpty) {
            fixtures.groups.forEach { out += groupSteps(it.name, over) }
            fixtures.fixtures.forEach { fixture -> out += fixtureSteps(fixture, over, BuskRigCellMode.PIPS, null) }
            return out.toList()
        }
        for (row in rig.rows) {
            for (tile in row.tiles) {
                when {
                    tile.groupName != null -> out += groupSteps(tile.groupName, over)
                    tile.fixtureKey != null -> {
                        val fixture = resolveFixture(tile.fixtureKey) ?: continue
                        if (tile.elementKey != null) {
                            if (cellsOf(fixture).any { it.key == tile.elementKey }) {
                                out += listOf(fixtureTarget(tile.elementKey))
                            }
                        } else {
                            out += fixtureSteps(fixture, over, tile.cellMode, tile.cellSplit)
                        }
                    }
                }
            }
        }
        return out.toList()
    }

    /**
     * A group tile's steps: the group itself over heads, its members' units over cells — every cell
     * of a multi-head member, a single-head member as itself — because a *Next* pressed with one
     * cell selected must step one cell whether the rig reaches that cell through a group tile or a
     * fixture tile. Nothing for a group the patch does not have.
     */
    private fun groupSteps(name: String, over: SpreadOver): List<List<CueTargetDto>> {
        val group = resolveGroup(name) ?: return emptyList()
        if (over == SpreadOver.HEADS) return listOf(listOf(groupTarget(name)))
        return group.fixtures.filterIsInstance<Fixture>().flatMap { fixtureSteps(it, over, BuskRigCellMode.PIPS, null) }
    }

    /**
     * Where every head sits along the rig, for ordering: a fixture at its first appearance (through
     * a group tile or its own), its cells fractionally after it, and a cell tile at its own place.
     * A head the rig does not reach has no entry and [sort] leaves it last, in the order given.
     */
    fun positions(): Map<CueTargetDto, Double> {
        val out = LinkedHashMap<CueTargetDto, Double>()
        var rank = 0
        fun place(fixture: Fixture) {
            val target = fixtureTarget(fixture.key)
            if (target in out) return
            val at = rank++.toDouble()
            out[target] = at
            val cells = cellsOf(fixture)
            cells.forEachIndexed { i, cell -> out.putIfAbsent(cell, at + (i + 1).toDouble() / (cells.size + 1)) }
        }
        fun placeGroup(name: String) {
            val group = resolveGroup(name) ?: return
            group.fixtures.filterIsInstance<Fixture>().forEach { place(it) }
        }
        if (rig.isEmpty) {
            fixtures.groups.forEach { placeGroup(it.name) }
            fixtures.fixtures.forEach { place(it) }
            return out
        }
        for (row in rig.rows) {
            for (tile in row.tiles) {
                when {
                    tile.groupName != null -> placeGroup(tile.groupName)
                    tile.fixtureKey != null -> {
                        val fixture = resolveFixture(tile.fixtureKey) ?: continue
                        if (tile.elementKey != null) {
                            val cell = fixtureTarget(tile.elementKey)
                            if (cellsOf(fixture).any { it == cell } && cell !in out) out[cell] = rank++.toDouble()
                        } else {
                            place(fixture)
                        }
                    }
                }
            }
        }
        return out
    }

    /** [heads] in rig order; unplaced heads last, in the order given. Stable. */
    fun sort(heads: List<CueTargetDto>): List<CueTargetDto> {
        val positions = positions()
        return heads.sortedBy { positions[it] ?: Double.MAX_VALUE }
    }

    private fun fixtureSteps(
        fixture: Fixture,
        over: SpreadOver,
        mode: BuskRigCellMode,
        split: Int?,
    ): List<List<CueTargetDto>> {
        val cells = cellsOf(fixture)
        if (cells.isEmpty()) return listOf(listOf(fixtureTarget(fixture.key)))
        if (over == SpreadOver.CELLS) return cells.map { listOf(it) }
        return when (mode) {
            BuskRigCellMode.PIPS, BuskRigCellMode.WHOLE -> listOf(listOf(fixtureTarget(fixture.key)))
            BuskRigCellMode.PER_CELL -> cells.map { listOf(it) }
            BuskRigCellMode.HALVES -> halves(cells, split ?: 2)
        }
    }

    private fun cellsOf(fixture: Fixture): List<CueTargetDto> =
        (fixture as? MultiElementFixture<*>)?.elements?.map { fixtureTarget(it.elementKey) }.orEmpty()

    private fun resolveGroup(name: String) = runCatching { fixtures.untypedGroup(name) }.getOrNull()

    private fun resolveFixture(key: String): Fixture? = runCatching { fixtures.untypedFixture(key) }.getOrNull()

    private fun groupTarget(name: String) = CueTargetDto(TargetRef.Group.TYPE, name)
    private fun fixtureTarget(key: String) = CueTargetDto(TargetRef.Fixture.TYPE, key)

    companion object {
        /**
         * [cells] cut into [count] contiguous runs, the first runs one longer when it does not
         * divide — twelve cells in five halves is 3 · 3 · 2 · 2 · 2. A count past the cell count
         * yields one cell per run and no empty runs.
         */
        fun halves(cells: List<CueTargetDto>, count: Int): List<List<CueTargetDto>> {
            val n = count.coerceIn(1, cells.size.coerceAtLeast(1))
            if (cells.isEmpty()) return emptyList()
            val base = cells.size / n
            val extra = cells.size % n
            val out = ArrayList<List<CueTargetDto>>(n)
            var at = 0
            repeat(n) { i ->
                val size = base + if (i < extra) 1 else 0
                out += cells.subList(at, at + size)
                at += size
            }
            return out
        }
    }
}
