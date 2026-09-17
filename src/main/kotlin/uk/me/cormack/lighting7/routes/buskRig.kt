package uk.me.cormack.lighting7.routes

import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.inList
import uk.me.cormack.lighting7.models.BuskRigCellMode
import uk.me.cormack.lighting7.models.BuskRigTileKind
import uk.me.cormack.lighting7.models.DaoBuskRigRow
import uk.me.cormack.lighting7.models.DaoBuskRigRows
import uk.me.cormack.lighting7.models.DaoBuskRigTile
import uk.me.cormack.lighting7.models.DaoBuskRigTiles
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.state.BuskRigRowSpec
import uk.me.cormack.lighting7.state.BuskRigSpec
import uk.me.cormack.lighting7.state.BuskRigTileSpec

/**
 * The busk rig's hand-rolled cascades and its one read, beside the busk layout's (`buskLayout.kt`).
 * SQLite enforces no FK cascade without a per-connection pragma (see `DaoBuskRigTiles`), so every
 * path that removes a row, a group or a patch does the sweep itself, inside its own transaction.
 * Every function here must be called inside one.
 */

/** The project's rig rows in operator order. */
internal fun rigRowsOf(project: DaoProject): List<DaoBuskRigRow> =
    DaoBuskRigRow.find { DaoBuskRigRows.project eq project.id }
        .sortedWith(compareBy({ it.sortOrder }, { it.uuid }))

/**
 * Every tile under [rowIds], keyed by id — read through `find` rather than the entity's referrers
 * for the reason `buskPageContents` is: the rig write reads the rows it is about to rewrite.
 */
internal fun rigTilesOf(rowIds: List<Int>): Map<Int, DaoBuskRigTile> =
    if (rowIds.isEmpty()) emptyMap()
    else DaoBuskRigTile.find { DaoBuskRigTiles.row inList rowIds }.associateBy { it.id.value }

/** Delete every row of [project]'s rig and everything on it: tiles → rows. */
internal fun deleteBuskRig(project: DaoProject) {
    val rows = rigRowsOf(project)
    rigTilesOf(rows.map { it.id.value }).values.forEach { it.delete() }
    rows.forEach { it.delete() }
}

/**
 * Delete every tile that selects the named group or patch and return how many went, so the caller
 * can fire `buskRigChanged` only when something moved. A row left with no tiles is deleted with
 * them — the write boundary refuses an empty row, so one must never be read back.
 *
 * Unconditional, and deliberately not a delete guard (busk-further plan §3.1): a tile is an
 * enrichment of its group or patch, so the record's delete takes its tiles with it. Pass exactly
 * one id; the other is null.
 */
internal fun deleteBuskRigTilesReferencing(groupId: Int? = null, patchId: Int? = null): Int {
    require(listOfNotNull(groupId, patchId).size == 1) { "exactly one record id" }
    val tiles = when {
        groupId != null -> DaoBuskRigTile.find { DaoBuskRigTiles.group eq groupId }
        else -> DaoBuskRigTile.find { DaoBuskRigTiles.patch eq patchId!! }
    }.toList()
    if (tiles.isEmpty()) return 0
    val rowIds = tiles.mapTo(mutableSetOf()) { it.readValues[DaoBuskRigTiles.row].value }
    tiles.forEach { it.delete() }
    DaoBuskRigRow.find { DaoBuskRigRows.id inList rowIds.toList() }
        .filter { it.tiles.empty() }
        .forEach { it.delete() }
    return tiles.size
}

/**
 * Delete every **cell** tile of [patchId] — a tile carrying an `element_key` — and return how many
 * went, sweeping any row left empty as [deleteBuskRigTilesReferencing] does.
 *
 * For a patch **rename**: an element key embeds its parent's key, and the desk never parses one
 * (the keys are opaque), so a renamed patch's stored cell keys cannot be rewritten — they can only
 * be checked against the live fixture, which after the rename has different ones. Left in place, a
 * stale cell tile vanishes from the order and, worse, is echoed by the read and refused by the next
 * whole-document write (`BUSK_RIG_REF`), wedging every rig save. The fixture's own tiles keep their
 * place: they name the patch by id and read its new key.
 */
internal fun deleteBuskRigCellTilesOf(patchId: Int): Int {
    val tiles = DaoBuskRigTile.find { (DaoBuskRigTiles.patch eq patchId) and DaoBuskRigTiles.elementKey.isNotNull() }.toList()
    if (tiles.isEmpty()) return 0
    val rowIds = tiles.mapTo(mutableSetOf()) { it.readValues[DaoBuskRigTiles.row].value }
    tiles.forEach { it.delete() }
    DaoBuskRigRow.find { DaoBuskRigRows.id inList rowIds.toList() }
        .filter { it.tiles.empty() }
        .forEach { it.delete() }
    return tiles.size
}

/**
 * The rig as `state/BuskRigOrder.kt` reads it — names and keys, not ids — resolved from the tables.
 * A malformed tile (neither arm or both) is absent, as every reader treats it.
 */
internal fun readBuskRigSpec(project: DaoProject): BuskRigSpec {
    val rows = rigRowsOf(project)
    val tiles = rigTilesOf(rows.map { it.id.value }).values
        .groupBy { it.readValues[DaoBuskRigTiles.row].value }
    return BuskRigSpec(
        rows.map { row ->
            BuskRigRowSpec(
                name = row.name,
                tiles = tiles[row.id.value].orEmpty()
                    .sortedWith(compareBy({ it.sortOrder }, { it.uuid }))
                    .mapNotNull { tile ->
                        when (tile.kind) {
                            BuskRigTileKind.GROUP -> BuskRigTileSpec(groupName = tile.group!!.name)
                            BuskRigTileKind.FIXTURE -> BuskRigTileSpec(
                                fixtureKey = tile.patch!!.key,
                                elementKey = tile.elementKey,
                                cellMode = BuskRigCellMode.entries.firstOrNull { it.name == tile.cellMode } ?: BuskRigCellMode.PIPS,
                                cellSplit = tile.cellSplit,
                            )
                            null -> null
                        }
                    },
            )
        },
    )
}
