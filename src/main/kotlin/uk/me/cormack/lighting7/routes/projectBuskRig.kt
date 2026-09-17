package uk.me.cormack.lighting7.routes

import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.request.receive
import io.ktor.server.resources.get
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fixture.group.MultiElementFixture
import uk.me.cormack.lighting7.fixture.group.SymmetricMode
import uk.me.cormack.lighting7.models.BuskRigCellMode
import uk.me.cormack.lighting7.models.BuskRigTileKind
import uk.me.cormack.lighting7.models.DaoBuskRigRow
import uk.me.cormack.lighting7.models.DaoBuskRigTile
import uk.me.cormack.lighting7.models.DaoBuskRigTiles
import uk.me.cormack.lighting7.models.DaoFixtureGroup
import uk.me.cormack.lighting7.models.DaoFixturePatch
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.buskRigTileKind
import uk.me.cormack.lighting7.models.warnMalformedBuskRigTile
import uk.me.cormack.lighting7.show.Fixtures
import uk.me.cormack.lighting7.state.State

/** 400 code: the rig document is malformed — an empty row, a blank name, a bad cell mode, a split out of range. */
internal const val CODE_BUSK_RIG_INVALID = "BUSK_RIG_INVALID"

/** 400 code: the document names a row or tile id that is not this project's rig, or names one twice. */
internal const val CODE_BUSK_RIG_IDENTITY = "BUSK_RIG_IDENTITY"

/** 400 code: a tile names a group or patch not in this project, or an element key its patch does not have. */
internal const val CODE_BUSK_RIG_REF = "BUSK_RIG_REF"

/**
 * The busk **rig**'s REST surface: one read and one whole-document write (busk-further plan §3.2).
 *
 * `GET /busk/rig` answers the rows of tiles with each tile's group summary or patch summary
 * **embedded** (`GroupSummaryDto`, the patch's key, name and elements), so the band draws from one
 * read. An empty `rows` is stored as empty and answered as empty: the show-all fallback — every
 * group, then every fixture — is the **client's** (`effectiveRig`) and the desk's rig order
 * (`state/BuskRigOrder.kt`), never something the server invents into the document (D1).
 *
 * `PUT /busk/rig` is the busk layout's D10 exactly: one PUT per gesture, the whole rig, rows and
 * tiles addressed by position, a row or tile with an id moved and rewritten, one without created,
 * one absent deleted, everything renumbered dense. It refuses as a whole — before touching a row —
 * with [CODE_BUSK_RIG_INVALID] (an empty row, a blank name, a `HALVES` with `cellSplit` below 2 or
 * above the fixture's cell count, a cell mode the desk does not know), [CODE_BUSK_RIG_IDENTITY]
 * (an id not this project's, or named twice) or [CODE_BUSK_RIG_REF] (a dangling group or patch, an
 * element key that is not one of the patch's cells). It answers the rig as written, ids minted,
 * because the client's next gesture must carry them.
 *
 * Both are gated on the **current** project: the summaries a tile embeds and the cells an element
 * key is validated against are the live show's, and only the current project has one.
 */
internal fun Route.routeApiRestProjectBuskRig(state: State) {
    // GET /projects/{id}/busk/rig
    get<BuskRigResource> { resource ->
        withCurrentProject(state, resource.projectId, "Cannot read the rig — not the current project") { project ->
            val rig = transaction(state.database) { rigDto(state.show.fixtures, project) }
            call.respond(rig)
        }
    }

    // PUT /projects/{id}/busk/rig — the whole document
    put<BuskRigResource> { resource ->
        withCurrentProject(state, resource.projectId) { project ->
            val request = call.receive<BuskRigRequest>()
            val outcome = transaction(state.database) { applyBuskRig(state.show.fixtures, project, request) }
            when (outcome) {
                is BuskRigOutcome.Invalid ->
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(outcome.message, code = CODE_BUSK_RIG_INVALID))
                is BuskRigOutcome.Identity ->
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(outcome.message, code = CODE_BUSK_RIG_IDENTITY))
                is BuskRigOutcome.Ref ->
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(outcome.message, code = CODE_BUSK_RIG_REF))
                is BuskRigOutcome.Ok -> {
                    state.show.fixtures.buskRigChanged()
                    call.respond(outcome.rig)
                }
            }
        }
    }
}

// ─── Resources ──────────────────────────────────────────────────────────

@Resource("/{projectId}/busk/rig")
internal data class BuskRigResource(val projectId: String)

// ─── Read DTOs ──────────────────────────────────────────────────────────

/** The rig as the band draws it: rows of tiles, positions implied by order. */
@Serializable
internal data class BuskRigDto(val rows: List<BuskRigRowDto> = emptyList())

@Serializable
internal data class BuskRigRowDto(
    val id: Int,
    val uuid: String,
    val name: String,
    val tiles: List<BuskRigTileDto> = emptyList(),
)

/**
 * A tile: which kind it selects and **the record's own summary** — exactly one of [group] / [patch]
 * is set, matching [kind]. A [BuskRigCellMode] name in [cellMode]; [cellSplit] only for `HALVES`;
 * [elementKey] only on a tile that is one cell.
 */
@Serializable
internal data class BuskRigTileDto(
    val id: Int,
    val uuid: String,
    /** `GROUP` or `FIXTURE`. */
    val kind: String,
    val group: GroupSummaryDto? = null,
    val patch: BuskRigPatchDto? = null,
    val elementKey: String? = null,
    val cellMode: String = BuskRigCellMode.PIPS.name,
    val cellSplit: Int? = null,
    val label: String? = null,
)

/** What a fixture tile draws: the patch's key and name, and its cells in element order. */
@Serializable
internal data class BuskRigPatchDto(
    val id: Int,
    val key: String,
    val name: String,
    val elements: List<BuskRigElementDto> = emptyList(),
)

@Serializable
internal data class BuskRigElementDto(val key: String, val name: String)

// ─── Write DTOs ─────────────────────────────────────────────────────────

/**
 * The whole rig. Rows and tiles are ordered by list position; a row or tile with an id is moved
 * and rewritten, one without is created, and one on the rig but absent here is deleted.
 * `rows: []` is a legal empty rig.
 */
@Serializable
internal data class BuskRigRequest(val rows: List<BuskRigRowInput> = emptyList())

@Serializable
internal data class BuskRigRowInput(
    val rowId: Int? = null,
    val name: String,
    val tiles: List<BuskRigTileInput> = emptyList(),
)

/** Exactly one of [groupId] / [patchId]. Int ids, like every REST body; uuids are sync's. */
@Serializable
internal data class BuskRigTileInput(
    val tileId: Int? = null,
    val groupId: Int? = null,
    val patchId: Int? = null,
    val elementKey: String? = null,
    val cellMode: String = BuskRigCellMode.PIPS.name,
    val cellSplit: Int? = null,
    val label: String? = null,
)

// ─── Outcomes ───────────────────────────────────────────────────────────

internal sealed interface BuskRigOutcome {
    data class Invalid(val message: String) : BuskRigOutcome
    data class Identity(val message: String) : BuskRigOutcome
    data class Ref(val message: String) : BuskRigOutcome
    data class Ok(val rig: BuskRigDto) : BuskRigOutcome
}

// ─── The rig write ──────────────────────────────────────────────────────

/** One tile's input after validation: the resolved records and the normalised cell fields. */
private class TileWrite(
    val input: BuskRigTileInput,
    val group: DaoFixtureGroup?,
    val patch: DaoFixturePatch?,
    val elementKey: String?,
    val cellMode: BuskRigCellMode,
    val cellSplit: Int?,
    val label: String?,
)

private const val LABEL_MAX = 64
private const val NAME_MAX = 64

/**
 * Validate [request] against [project]'s rig and, if it is a well-formed document of rows on this
 * rig, records in this project and cells the live patch has, write it. **Returns before touching a
 * row on any refusal.** Validation runs shape → identity → references, so the message names the
 * first thing wrong in the order an author would fix it. Must be called inside a transaction.
 */
internal fun applyBuskRig(fixtures: Fixtures, project: DaoProject, request: BuskRigRequest): BuskRigOutcome {
    // Shape.
    request.rows.forEachIndexed { rowIndex, row ->
        val where = "row ${rowIndex + 1}"
        if (row.name.isBlank()) return BuskRigOutcome.Invalid("Row ${rowIndex + 1} has a blank name")
        if (row.name.trim().length > NAME_MAX) return BuskRigOutcome.Invalid("Name of $where is longer than $NAME_MAX characters")
        if (row.tiles.isEmpty()) return BuskRigOutcome.Invalid("Row ${rowIndex + 1} ('${row.name.trim()}') has no tiles")
        row.tiles.forEachIndexed { tileIndex, tile ->
            val at = "$where, tile ${tileIndex + 1}"
            val kind = buskRigTileKind(tile.groupId, tile.patchId)
                ?: return BuskRigOutcome.Invalid("Tile at $at must name exactly one of groupId or patchId")
            if (BuskRigCellMode.entries.none { it.name == tile.cellMode }) {
                return BuskRigOutcome.Invalid("Cell mode '${tile.cellMode}' at $at is not one of ${BuskRigCellMode.entries.map { it.name }}")
            }
            if (kind == BuskRigTileKind.GROUP && !tile.elementKey.isNullOrBlank()) {
                return BuskRigOutcome.Invalid("Tile at $at is a group and cannot name an element key")
            }
            if ((tile.label?.trim()?.length ?: 0) > LABEL_MAX) {
                return BuskRigOutcome.Invalid("Label at $at is longer than $LABEL_MAX characters")
            }
        }
    }

    // Identity: every named id is on this rig, none twice.
    val rows = rigRowsOf(project).associateBy { it.id.value }
    val tiles = rigTilesOf(rows.keys.toList())
    val allTiles = request.rows.flatMap { it.tiles }
    rigIdentityProblem("rows", request.rows.mapNotNull { it.rowId }, rows.keys)?.let { return it }
    rigIdentityProblem("tiles", allTiles.mapNotNull { it.tileId }, tiles.keys)?.let { return it }

    // References: every group and patch named is in this project, and every cell is one the live
    // fixture has — validated against the fixture's own `elements`, never by parsing the key.
    val groups = allTiles.mapNotNull { it.groupId }.toSet()
        .associateWith { id -> DaoFixtureGroup.findById(id)?.takeIf { it.project.id == project.id } }
    val patches = allTiles.mapNotNull { it.patchId }.toSet()
        .associateWith { id -> DaoFixturePatch.findById(id)?.takeIf { it.project.id == project.id } }
    val missingGroups = groups.filterValues { it == null }.keys.sorted()
    val missingPatches = patches.filterValues { it == null }.keys.sorted()
    if (missingGroups.isNotEmpty() || missingPatches.isNotEmpty()) {
        val parts = buildList {
            if (missingGroups.isNotEmpty()) add("groups $missingGroups")
            if (missingPatches.isNotEmpty()) add("patches $missingPatches")
        }
        return BuskRigOutcome.Ref("Rig names records not in this project — " + parts.joinToString("; "))
    }

    val writes = ArrayList<List<TileWrite>>()
    request.rows.forEachIndexed { rowIndex, row ->
        writes += row.tiles.mapIndexed { tileIndex, tile ->
            val at = "row ${rowIndex + 1}, tile ${tileIndex + 1}"
            val label = tile.label?.trim()?.takeIf { it.isNotEmpty() }
            when (buskRigTileKind(tile.groupId, tile.patchId)!!) {
                // A group tile has no cell mode (D3): stored at the default, whatever was sent.
                BuskRigTileKind.GROUP -> TileWrite(tile, groups.getValue(tile.groupId!!), null, null, BuskRigCellMode.PIPS, null, label)
                BuskRigTileKind.FIXTURE -> {
                    val patch = patches.getValue(tile.patchId!!)!!
                    val mode = BuskRigCellMode.valueOf(tile.cellMode)
                    val cells = liveCells(fixtures, patch.key)
                    val elementKey = tile.elementKey?.trim()?.takeIf { it.isNotEmpty() }
                    if (elementKey != null) {
                        if (cells == null) return BuskRigOutcome.Ref("Tile at $at names cell '$elementKey' of '${patch.key}', which is not in the live rig")
                        if (cells.none { it.key == elementKey }) {
                            return BuskRigOutcome.Ref("Tile at $at names cell '$elementKey', which '${patch.key}' does not have")
                        }
                        if (mode == BuskRigCellMode.PER_CELL || mode == BuskRigCellMode.HALVES) {
                            return BuskRigOutcome.Invalid("Tile at $at is one cell and cannot be split")
                        }
                        TileWrite(tile, null, patch, elementKey, mode, null, label)
                    } else {
                        val split = when (mode) {
                            BuskRigCellMode.HALVES -> {
                                val count = cells?.size ?: 0
                                val split = tile.cellSplit
                                    ?: return BuskRigOutcome.Invalid("Tile at $at is HALVES and needs a cellSplit")
                                if (split < 2) return BuskRigOutcome.Invalid("Tile at $at has cellSplit $split; HALVES needs at least 2")
                                if (split > count) {
                                    return BuskRigOutcome.Invalid("Tile at $at has cellSplit $split but '${patch.key}' has $count cells")
                                }
                                split
                            }
                            BuskRigCellMode.PER_CELL -> {
                                if (cells.isNullOrEmpty()) return BuskRigOutcome.Invalid("Tile at $at is PER_CELL but '${patch.key}' has no cells")
                                null
                            }
                            BuskRigCellMode.PIPS, BuskRigCellMode.WHOLE -> null
                        }
                        TileWrite(tile, null, patch, null, mode, split, label)
                    }
                }
            }
        }
    }

    // Write: upsert everything named, then sweep what was not. Moves first, so a row whose tiles
    // have all moved elsewhere is empty by the time it is swept rather than taking them with it.
    val keptRows = HashSet<Int>()
    val keptTiles = HashSet<Int>()
    request.rows.forEachIndexed { rowIndex, r ->
        val row = r.rowId?.let { rows.getValue(it) } ?: DaoBuskRigRow.new {
            this.project = project
            name = r.name.trim()
            sortOrder = rowIndex
        }
        row.name = r.name.trim()
        row.sortOrder = rowIndex
        keptRows += row.id.value
        writes[rowIndex].forEachIndexed { tileIndex, w ->
            // Refs set inside the constructor for a new tile: the `busk_rig_tile_exactly_one_ref`
            // check fires on flush, so a tile minted with no arm would be a 500 in place of a 400.
            val tile = w.input.tileId?.let { tiles.getValue(it) } ?: DaoBuskRigTile.new {
                this.row = row
                sortOrder = tileIndex
                group = w.group
                patch = w.patch
            }
            tile.row = row
            tile.sortOrder = tileIndex
            tile.group = w.group
            tile.patch = w.patch
            tile.elementKey = w.elementKey
            tile.cellMode = w.cellMode.name
            tile.cellSplit = w.cellSplit
            tile.label = w.label
            keptTiles += tile.id.value
        }
    }
    tiles.values.filter { it.id.value !in keptTiles }.forEach { it.delete() }
    rows.values.filter { it.id.value !in keptRows }.forEach { it.delete() }

    return BuskRigOutcome.Ok(rigDto(fixtures, project))
}

private fun rigIdentityProblem(what: String, named: List<Int>, onRig: Set<Int>): BuskRigOutcome.Identity? {
    val unknown = named.filter { it !in onRig }
    if (unknown.isNotEmpty()) return BuskRigOutcome.Identity("Rig names $what not on this project's rig — $unknown")
    val duplicates = named.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    if (duplicates.isNotEmpty()) return BuskRigOutcome.Identity("Rig names $what more than once — ${duplicates.sorted()}")
    return null
}

// ─── Reads ──────────────────────────────────────────────────────────────

/** The live fixture's cells for [patchKey], or null when nothing is registered under that key. */
private fun liveCells(fixtures: Fixtures, patchKey: String): List<BuskRigElementDto>? {
    val fixture = runCatching { fixtures.untypedFixture(patchKey) }.getOrNull() ?: return null
    return (fixture as? MultiElementFixture<*>)?.elements?.map { BuskRigElementDto(it.elementKey, it.displayName) }.orEmpty()
}

/** Must be called inside a transaction. Reads through `find` so a just-written rig reads back fresh. */
internal fun rigDto(fixtures: Fixtures, project: DaoProject): BuskRigDto {
    val rows = rigRowsOf(project)
    val tilesByRow = rigTilesOf(rows.map { it.id.value }).values.groupBy { it.readValues[DaoBuskRigTiles.row].value }
    return BuskRigDto(
        rows.map { row ->
            BuskRigRowDto(
                id = row.id.value,
                uuid = row.uuid.toString(),
                name = row.name,
                tiles = tilesByRow[row.id.value].orEmpty()
                    .sortedWith(compareBy({ it.sortOrder }, { it.uuid }))
                    .mapNotNull { it.toDto(fixtures) },
            )
        },
    )
}

/** Null for a malformed tile, which every reader treats as absent (`buskRigTileKind`). */
private fun DaoBuskRigTile.toDto(fixtures: Fixtures): BuskRigTileDto? {
    val kind = kind ?: run { warnMalformedBuskRigTile { uuid.toString() }; return null }
    return when (kind) {
        BuskRigTileKind.GROUP -> {
            val dao = group!!
            BuskRigTileDto(
                id = id.value, uuid = uuid.toString(), kind = kind.name,
                group = groupSummary(fixtures, dao),
                cellMode = cellMode, label = label,
            )
        }
        BuskRigTileKind.FIXTURE -> {
            val dao = patch!!
            BuskRigTileDto(
                id = id.value, uuid = uuid.toString(), kind = kind.name,
                patch = BuskRigPatchDto(dao.id.value, dao.key, dao.displayName, liveCells(fixtures, dao.key).orEmpty()),
                elementKey = elementKey, cellMode = cellMode, cellSplit = cellSplit, label = label,
            )
        }
    }
}

/**
 * The live group's summary, or — for a group the loader did not register (no members) — a stub with
 * its name and member count, so a tile never reads as absent while its record exists.
 */
private fun groupSummary(fixtures: Fixtures, dao: DaoFixtureGroup): GroupSummaryDto =
    runCatching { fixtures.untypedGroup(dao.name) }.getOrNull()?.toGroupSummaryDto()
        ?: GroupSummaryDto(
            name = dao.name,
            memberCount = dao.members.count().toInt(),
            capabilities = emptyList(),
            symmetricMode = SymmetricMode.NONE.name,
            defaultDistribution = "LINEAR",
        )
