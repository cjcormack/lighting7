package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.slf4j.LoggerFactory

/**
 * The **busk rig**: the rows of tiles the busk view's target band draws, built by the operator.
 *
 * The target band used to be every group then every fixture, in one column-flow grid that scrolled
 * sideways. `docs/plans/busk-further-plan.md` makes it a document the operator builds (D1): rows of
 * tiles, each tile a group, a fixture, or one **cell** of a multi-head fixture. Three decisions
 * shape the two tables:
 *
 * - **D1 — one rig per project, and an empty rig is today's band.** Not per page: the two-screen
 *   flow this exists for is a colour page on one screen and a position page on the other, pressed
 *   onto **one** selection, and a rig per page would give those screens two selection surfaces. An
 *   empty rig ([DaoBuskRigRows] holding no rows) renders exactly what the band rendered before —
 *   every group, then every fixture — and that fallback is the **client's** (`effectiveRig`) and
 *   the desk's rig order (`state/BuskRigOrder.kt`), never something the server stores. So there is
 *   no migration and no first-open generator.
 * - **D2 — rows, not coordinates.** The busk layout's argument, verbatim: a coordinate system
 *   nothing else reads is a second model. A tile's place is its row and its `sort_order`; *Plot*
 *   from the patch's stage coordinates is `FU-BUSK-RIG-PLOT`, out of scope.
 * - **D3 — a multi-head tile decides how it shows its cells.** [DaoBuskRigTiles.cellMode] is one of
 *   [BuskRigCellMode]: `PIPS` (the whole fixture, cells drawn as pips that select individually),
 *   `WHOLE` (no pips), `PER_CELL` (one tile per cell, the fixture's own tile absent), `HALVES`
 *   (`cell_split` tiles, each a contiguous run of cells). A `PER_CELL` or `HALVES` tile is **one
 *   stored tile** the read side expands; [DaoBuskRigTiles.elementKey] is set only on a tile the
 *   operator dragged in as a single cell. A group tile has no cell mode.
 *
 * Element keys are **opaque** here as everywhere: the write boundary validates an `element_key`
 * against the live fixture's `elements`, never by parsing it, and the read side resolves it the
 * same way.
 *
 * **No `ReferenceOption` on any FK here**, for the reason `busk_pads` has none: SQLite enforces no
 * cascade without a per-connection pragma, so every path that removes a row or a referent sweeps
 * by hand — a rig write (tiles absent from the document are deleted), a group delete, a patch
 * delete, the project delete and the project importer's replace (`deleteBuskRigTilesReferencing` /
 * `deleteBuskRig` in `routes/buskRig.kt`). A tile is an **enrichment** of its group or patch, never
 * a delete guard: deleting the record takes its tiles with it inside the same transaction, and sync
 * reads a tile whose referent the archive lacks as absent, with a warning.
 *
 * The exactly-one rule on a tile is a CHECK constraint below, with the `cue_layers` caveat — it
 * reaches only a database created after it was added — so every reader treats a malformed tile as
 * absent ([buskRigTileKind]) rather than trusting the schema.
 */
object DaoBuskRigRows : IntIdTable("busk_rig_rows") {
    val project = reference("project_id", DaoProjects)

    /** Shown as the row's label on the band. May repeat — a row's identity is its position. */
    val name = varchar("name", 64)

    /** Position among the project's rows, dense from zero; renumbered by every rig write. */
    val sortOrder = integer("sort_order").default(0)
    val uuid = javaUUID("uuid").autoGenerate()

    init {
        uniqueIndex(project, uuid)
    }
}

class DaoBuskRigRow(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoBuskRigRow>(DaoBuskRigRows)

    var project by DaoProject referencedOn DaoBuskRigRows.project
    var name by DaoBuskRigRows.name
    var sortOrder by DaoBuskRigRows.sortOrder
    var uuid by DaoBuskRigRows.uuid

    /** The tiles on this row. Order is `sortOrder`; callers sort in memory. */
    val tiles by DaoBuskRigTile referrersOn DaoBuskRigTiles.row
}

/** How a multi-head tile shows its cells (D3). Serialised by name on the wire and in sync. */
enum class BuskRigCellMode {
    /** The whole fixture, cells drawn as pips that select individually. The default. */
    PIPS,

    /** The whole fixture, no pips. */
    WHOLE,

    /** One tile per cell; the fixture's own tile is absent. */
    PER_CELL,

    /** `cell_split` tiles, each a contiguous run of cells. */
    HALVES,
}

object DaoBuskRigTiles : IntIdTable("busk_rig_tiles") {
    val row = reference("row_id", DaoBuskRigRows)

    /** Position within the row, dense from zero. */
    val sortOrder = integer("sort_order")
    val uuid = javaUUID("uuid").autoGenerate()

    /**
     * What this tile selects: **exactly one** of [group] / [patch] is set — the `busk_pads`
     * pattern, two-armed. Nullable FKs rather than a `(kind, key)` pair so a tile pointing at a
     * deleted record cannot be expressed once the record's delete has swept its tiles.
     */
    val group = reference("group_id", DaoFixtureGroups).nullable()
    val patch = reference("patch_id", DaoFixturePatches).nullable()

    /**
     * Only with [patch]: the one cell this tile is, for a tile dragged in as a single cell. Opaque —
     * validated against the live fixture's elements at the write boundary, never parsed.
     */
    val elementKey = varchar("element_key", 128).nullable()

    /** A [BuskRigCellMode] name. Meaningful only on a fixture tile with cells; stored as given. */
    val cellMode = varchar("cell_mode", 16).default(BuskRigCellMode.PIPS.name)

    /** `HALVES` only: how many tiles the fixture's cells are split into. */
    val cellSplit = integer("cell_split").nullable()

    /** An operator-given caption; null draws the group's or fixture's own name. */
    val label = varchar("label", 64).nullable()

    init {
        // Exactly one referent, stated to the database as well as to the code. Reaches only a DB
        // created after it was added — see the `cue_layers` note; `buskRigTileKind` holds the line
        // on one that predates it.
        check("busk_rig_tile_exactly_one_ref") {
            (group.isNotNull() and patch.isNull()) or (group.isNull() and patch.isNotNull())
        }
    }
}

class DaoBuskRigTile(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoBuskRigTile>(DaoBuskRigTiles)

    var row by DaoBuskRigRow referencedOn DaoBuskRigTiles.row
    var sortOrder by DaoBuskRigTiles.sortOrder
    var uuid by DaoBuskRigTiles.uuid
    var group by DaoFixtureGroup optionalReferencedOn DaoBuskRigTiles.group
    var patch by DaoFixturePatch optionalReferencedOn DaoBuskRigTiles.patch
    var elementKey by DaoBuskRigTiles.elementKey
    var cellMode by DaoBuskRigTiles.cellMode
    var cellSplit by DaoBuskRigTiles.cellSplit
    var label by DaoBuskRigTiles.label

    /** Which arm this tile's row sets, or null for a malformed row every reader treats as absent. */
    val kind: BuskRigTileKind?
        get() = buskRigTileKind(readValues[DaoBuskRigTiles.group], readValues[DaoBuskRigTiles.patch])
}

/** The two things a tile can select. Serialised by name on the wire. */
enum class BuskRigTileKind {
    GROUP,
    FIXTURE,
}

private val buskRigLogger = LoggerFactory.getLogger("buskRig")

/**
 * The [DaoBuskRigTiles] exactly-one rule, decided in one place — [buskPadKind]'s shape, two-armed.
 *
 * Takes `Any?` because the rule is purely about presence: the read path holds entity ids, the rig
 * route int ids and the importer uuid strings. Null means malformed (none set, or both); the
 * caller drops the tile and, if it is a reader, says so through [warnMalformedBuskRigTile].
 */
fun buskRigTileKind(group: Any?, patch: Any?): BuskRigTileKind? =
    listOfNotNull(group?.let { BuskRigTileKind.GROUP }, patch?.let { BuskRigTileKind.FIXTURE }).singleOrNull()

/** The shared behaviour for a malformed tile row: warn, naming [tile], and the caller treats it as absent. */
fun warnMalformedBuskRigTile(tile: () -> String) {
    buskRigLogger.warn("busk rig tile {} names no record or both — dropping the tile", tile())
}
