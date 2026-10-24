package uk.me.cormack.lighting7.state

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.VarCharColumnType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.fx.ParameterInfo
import uk.me.cormack.lighting7.fx.normalizeFxType
import uk.me.cormack.lighting7.models.DEFERRED_TARGET_TYPE
import uk.me.cormack.lighting7.models.DaoCueAdHocFx
import uk.me.cormack.lighting7.models.DaoCues
import uk.me.cormack.lighting7.models.DaoLookFx
import uk.me.cormack.lighting7.models.DaoLooks
import uk.me.cormack.lighting7.models.DaoProjects
import uk.me.cormack.lighting7.models.DaoPaletteFx
import uk.me.cormack.lighting7.models.DaoPaletteRows
import uk.me.cormack.lighting7.models.DaoPalettes
import uk.me.cormack.lighting7.sync.LegacyNames
import java.util.UUID

private val logger = LoggerFactory.getLogger("FxLibraryMigration")

/** `PRAGMA user_version` once [rewriteFxLibrary] has run, the step after [LOOK_LAYER_TARGETS_CLEARED_USER_VERSION]. */
internal const val FX_LIBRARY_REWRITTEN_USER_VERSION = 4

/**
 * Rewrite every stored FX onto today's FX library, once per database (terminology plan §4.2, §5
 * step 4): the renamed ids, Breathe as Sine Wave, `pingPong` as Direction Bounce, Colour Cycle on
 * literal colours, `SPLIT` as `CENTRE_IN` and Lightning Strike out of the composite category — each
 * by [LegacyNames.rewriteFx], which the importer shares, and each named in the log by its Look, cue
 * or palette. A script FX definition or script on the composite mode, which went, is moved to the
 * standard one and named, since its script returns a map no FX applies any more; and every script
 * whose source names something the FX library changed is named with what to edit.
 *
 * Runs once, after [clearLookLayerTargets], inside `State.initDatabase`'s transaction: `PRAGMA
 * user_version` below [FX_LIBRARY_REWRITTEN_USER_VERSION] means it has not. Never twice: a second
 * pass would halve a bouncing FX's cycle again, since an FX with no `pingPong` is read here as one
 * written before it went.
 */
internal fun JdbcTransaction.rewriteFxLibrary() {
    var version = 0
    exec("PRAGMA user_version") { rs -> if (rs.next()) version = rs.getInt(1) }
    if (version >= FX_LIBRARY_REWRITTEN_USER_VERSION) return

    val palettes = palettesByUuid()
    val definitions = definitionsByProject()
    val projects = DaoProjects.selectAll().associate { it[DaoProjects.id].value to it[DaoProjects.name] }
    // A project's own FX definitions name its FX; an FX whose owner has gone has none.
    val contexts = HashMap<Int?, LegacyNames.Context>()
    val contextOf = { projectId: Int? ->
        contexts.getOrPut(projectId) {
            val own = definitions[projectId].orEmpty()
            LegacyNames.Context(palettes::get, { own[normalizeFxType(it)] }, absentPingPongIsOn = true)
        }
    }

    rewriteHome(
        home = "Look",
        table = DaoLookFx,
        owner = DaoLookFx.look,
        owners = DaoLooks.selectAll().associate { it[DaoLooks.id].value to (it[DaoLooks.name] to it[DaoLooks.project].value) },
        columns = FxColumns(
            DaoLookFx.fxType, DaoLookFx.family, DaoLookFx.cycle,
            DaoLookFx.order, DaoLookFx.phaseOffset, DaoLookFx.parameters,
            DaoLookFx.direction, DaoLookFx.runs, DaoLookFx.spread,
        ),
        projects = projects,
        contextOf = contextOf,
    )
    rewriteHome(
        home = "Cue",
        table = DaoCueAdHocFx,
        owner = DaoCueAdHocFx.cue,
        owners = DaoCues.selectAll().associate { it[DaoCues.id].value to (it[DaoCues.name] to it[DaoCues.project].value) },
        columns = FxColumns(
            DaoCueAdHocFx.fxType, DaoCueAdHocFx.family, DaoCueAdHocFx.cycle,
            DaoCueAdHocFx.order, DaoCueAdHocFx.phaseOffset, DaoCueAdHocFx.parameters,
            DaoCueAdHocFx.direction, DaoCueAdHocFx.runs, DaoCueAdHocFx.spread,
        ),
        projects = projects,
        contextOf = contextOf,
    )
    rewriteHome(
        home = "Palette",
        table = DaoPaletteFx,
        owner = DaoPaletteFx.palette,
        owners = DaoPalettes.selectAll().associate { it[DaoPalettes.id].value to (it[DaoPalettes.name] to it[DaoPalettes.project].value) },
        columns = FxColumns(
            DaoPaletteFx.fxType, DaoPaletteFx.family, DaoPaletteFx.cycle,
            DaoPaletteFx.order, DaoPaletteFx.phaseOffset, DaoPaletteFx.parameters,
            DaoPaletteFx.direction, DaoPaletteFx.runs, DaoPaletteFx.spread,
        ),
        projects = projects,
        contextOf = contextOf,
    )
    leaveCompositeMode(projects)

    exec("PRAGMA user_version = $FX_LIBRARY_REWRITTEN_USER_VERSION", explicitStatementType = StatementType.OTHER)
}

/** The columns of one FX home that [LegacyNames.StoredFx] reads, which every home names alike. */
private class FxColumns(
    val fxType: Column<String>,
    val family: Column<String>,
    val cycle: Column<Double>,
    val order: Column<String>,
    val phaseOffset: Column<Double>,
    val parameters: Column<Map<String, String>>,
    val direction: Column<String>,
    val runs: Column<Int?>,
    val spread: Column<Double>,
) {
    fun read(row: ResultRow) = LegacyNames.StoredFx(
        fxType = row[fxType],
        family = row[family],
        cycle = row[cycle],
        order = row[order],
        phaseOffset = row[phaseOffset],
        parameters = row[parameters],
        direction = row[direction],
        runs = row[runs],
        spread = row[spread],
    )
}

private fun <T : IntIdTable> rewriteHome(
    home: String,
    table: T,
    owner: Column<EntityID<Int>>,
    owners: Map<Int, Pair<String, Int>>,
    columns: FxColumns,
    projects: Map<Int, String>,
    contextOf: (Int?) -> LegacyNames.Context,
) {
    val rows = table.selectAll().orderBy(table.id).map { it[table.id].value to (it[owner].value to columns.read(it)) }
    for ((id, ownedFx) in rows) {
        val (ownerId, fx) = ownedFx
        val (name, projectId) = owners[ownerId] ?: ("#$ownerId, deleted" to null)
        val rewrite = LegacyNames.rewriteFx(fx, contextOf(projectId)) ?: continue
        val out = rewrite.fx
        table.update({ table.id eq id }) {
            it[columns.fxType] = out.fxType
            it[columns.family] = out.family
            it[columns.cycle] = out.cycle
            it[columns.order] = out.order
            it[columns.phaseOffset] = out.phaseOffset
            it[columns.parameters] = out.parameters
            it[columns.direction] = out.direction
            it[columns.runs] = out.runs
            it[columns.spread] = out.spread
        }
        val said = "$home '{}' in '{}': {} — {}"
        val args = arrayOf(name, projectId?.let { projects[it] } ?: "no project", fx.fxType, rewrite.changes.joinToString("; "))
        if (rewrite.notes.isEmpty()) {
            logger.info(said, *args)
        } else {
            logger.warn("$said; output moves: {}", *args, rewrite.notes.joinToString("; "))
        }
    }
}

/** Every palette's rows, by its uuid, for Colour Cycle's references. */
private fun palettesByUuid(): Map<UUID, LegacyNames.Palette> {
    val withFx = DaoPaletteFx.selectAll().mapTo(HashSet()) { it[DaoPaletteFx.palette].value }
    val rows = DaoPaletteRows.selectAll().orderBy(DaoPaletteRows.sortOrder).groupBy(
        { it[DaoPaletteRows.palette].value },
        {
            LegacyNames.PaletteRow(
                attribute = it[DaoPaletteRows.attribute],
                value = it[DaoPaletteRows.value],
                deferred = it[DaoPaletteRows.targetType] == DEFERRED_TARGET_TYPE,
            )
        },
    )
    return DaoPalettes.selectAll().associate {
        val id = it[DaoPalettes.id].value
        it[DaoPalettes.uuid] to LegacyNames.Palette(it[DaoPalettes.name], rows[id].orEmpty(), id in withFx)
    }
}

/**
 * Every project's script FX definitions, by normalised effect id, as [LegacyNames.rewriteFx] reads
 * one. Raw SQL, since `fx_mode` may still hold the composite mode the enum no longer knows.
 */
private fun JdbcTransaction.definitionsByProject(): Map<Int?, Map<String, LegacyNames.Definition>> {
    val out = HashMap<Int?, HashMap<String, LegacyNames.Definition>>()
    exec("SELECT project_id, fx_id, output_type, parameters FROM fx_definitions") { rs ->
        while (rs.next()) {
            val projectId = rs.getInt(1).takeUnless { rs.wasNull() }
            val parameters = runCatching {
                Json.decodeFromString(ListSerializer(ParameterInfo.serializer()), rs.getString(4) ?: "[]")
            }.getOrDefault(emptyList())
            out.getOrPut(projectId) { HashMap() }[normalizeFxType(rs.getString(2))] = LegacyNames.Definition(
                outputType = rs.getString(3),
                colourLists = parameters.filter { it.type == "colourList" }.mapTo(HashSet()) { it.name },
            )
        }
    }
    return out
}

/**
 * Script FX definitions and scripts on the composite mode, moved to the standard one in raw SQL,
 * since the enum columns that read them no longer know the old name. Their scripts return a map,
 * so each is named for an edit.
 */
private fun JdbcTransaction.leaveCompositeMode(projects: Map<Int, String>) {
    data class Named(val name: String, val projectId: Int?)
    fun named(sql: String): List<Named> {
        val found = ArrayList<Named>()
        exec(sql) { rs ->
            while (rs.next()) found += Named(rs.getString(1), rs.getInt(2).takeUnless { rs.wasNull() })
        }
        return found
    }
    data class Composite(val id: Int, val fxId: String, val projectId: Int?, val family: String, val outputType: String)
    val composites = ArrayList<Composite>()
    exec("SELECT id, fx_id, project_id, family, output_type FROM fx_definitions WHERE fx_mode = 'COMPOSITE' OR family = 'composite'") { rs ->
        while (rs.next()) {
            composites += Composite(rs.getInt(1), rs.getString(2), rs.getInt(3).takeUnless { rs.wasNull() }, rs.getString(4), rs.getString(5))
        }
    }
    for (d in composites) {
        val family = LegacyNames.familyOf(d.family, d.outputType)
        exec(
            "UPDATE fx_definitions SET family = ?, fx_mode = CASE WHEN fx_mode = 'COMPOSITE' " +
                "THEN '${LegacyNames.fxMode("COMPOSITE")}' ELSE fx_mode END WHERE id = ?",
            listOf(VarCharColumnType() to family, IntegerColumnType() to d.id),
        )
        logger.warn(
            "FX definition '{}' in '{}': the composite mode is gone, so it is a standard FX filed under {} — " +
                "its script must now return one output",
            d.fxId, d.projectId?.let { id -> projects[id] } ?: "no project", family,
        )
    }
    named("SELECT name, project_id FROM scripts WHERE script_type = 'FX_CALC_COMPOSITE'").forEach {
        logger.warn(
            "Script '{}' in '{}': the composite calculate body is gone, so it is a standard one — it must now " +
                "return one output",
            it.name, it.projectId?.let { id -> projects[id] } ?: "no project",
        )
    }
    exec("UPDATE scripts SET script_type = '${LegacyNames.scriptType("FX_CALC_COMPOSITE")}' WHERE script_type = 'FX_CALC_COMPOSITE'")

    // A script names FX and their parts in its own source, which is the operator's to edit, not
    // this step's: each it names that changed is said, script by script.
    exec("SELECT name, project_id, script FROM scripts") { rs ->
        while (rs.next()) {
            val edits = scriptEdits(rs.getString(3) ?: continue)
            if (edits.isEmpty()) continue
            logger.warn("Script '{}' in '{}' needs editing: {}", rs.getString(1), projects[rs.getInt(2)] ?: "no project", edits.joinToString("; "))
        }
    }
}

/** What in a script's [source] the FX library changed under it, each in words. */
internal fun scriptEdits(source: String): List<String> = buildList {
    for (id in LegacyNames.retiredFxIds) {
        if ("\"$id\"" !in source) continue
        val now = LegacyNames.fxId(id).takeUnless { it == id }
        add(if (now != null) "$id is now $now" else "$id is gone; SineWave a quarter-cycle on draws it")
    }
    for (id in LegacyNames.bouncedFxIds) {
        if ("\"$id\"" in source) add("$id bounces by Direction now, each leg a whole cycle, so it runs at half its old rate")
    }
    if ("pingPong" in source) add("pingPong is gone; set the FX's Direction instead")
    if (Regex("\\bSPLIT\\b").containsMatchIn(source)) add("SPLIT is gone; CENTRE_IN gives its offsets")
    if ("CompositeEffect" in source || "calculateComposite" in source || "FX_CALC_COMPOSITE" in source) {
        add("the composite mode is gone; an FX applies one output")
    }
}
