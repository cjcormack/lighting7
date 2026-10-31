package uk.me.cormack.lighting7.state

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.vendors.currentDialectMetadata
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.models.DaoBuskPads
import uk.me.cormack.lighting7.models.DaoCueAdHocFx
import uk.me.cormack.lighting7.models.DaoCueLayers
import uk.me.cormack.lighting7.models.DaoCueAttributeAssignments
import uk.me.cormack.lighting7.models.DaoFxDefinitions
import uk.me.cormack.lighting7.models.DaoLookFx
import uk.me.cormack.lighting7.models.DaoLookRows
import uk.me.cormack.lighting7.models.DaoSpeedMasters
import uk.me.cormack.lighting7.models.DaoPaletteFx
import uk.me.cormack.lighting7.models.DaoPaletteRows
import uk.me.cormack.lighting7.models.DaoPalettes
import uk.me.cormack.lighting7.models.TIMING_CYCLE
import uk.me.cormack.lighting7.models.TIMING_HEAD
import uk.me.cormack.lighting7.sync.LegacyNames
import java.nio.file.Path
import java.time.Instant
import javax.sql.DataSource

private val logger = LoggerFactory.getLogger("TerminologyMigration")

/** `PRAGMA user_version` once the rename has run and the steps before it have caught up. */
internal const val TERMINOLOGY_RENAMED_USER_VERSION = 5

/**
 * Database step 5 (terminology plan §4.4, §5): the tables and columns that hold palettes, FX and
 * attributes take the glossary's names, and the stored values that are also wire values move with
 * them — the Order constants, `tmpl:` references, the `dimmer` and `palette` families and the two
 * surface binding names — through the maps [LegacyNames] holds for the archive.
 *
 * ### What it does
 *
 * It runs while the database is below [TERMINOLOGY_RENAMED_USER_VERSION] and still holds an old
 * table or column ([needed]), so a fresh database (no tables) and one it has renamed are left alone.
 *
 *  1. **A backup first**: `VACUUM INTO '<db>.pre-terminology-<utc>.db'`, outside any transaction.
 *  2. **Then one transaction**, so a failure leaves the database as it was:
 *     - Each table in [REBUILDS] is rebuilt from the model, its rows copied by id with each column
 *       taken from its old name, the way `CueModelMigration` rebuilds `cues`. A rebuild rather than
 *       `ALTER TABLE … RENAME COLUMN` for two reasons: `step_timing` and `default_step_timing` change
 *       type (a boolean becomes `CYCLE` / `HEAD`), and every index and constraint name Exposed derives
 *       from a table's and a column's name comes out as a fresh database's, so the reconciliation that
 *       follows finds nothing to add beside the old ones. The model's exactly-one CHECKs on
 *       `cue_layers` and `busk_pads` reach a database older than them here, so a row naming none or
 *       several of its records — one every reader already skips — is left out and counted.
 *     - A retired table that already holds a new name (`palettes`, the positional colour lists) is
 *       kept as `<name>_retired` and named in the log, rather than stopping the desk starting.
 *     - The stored values are renamed in place ([renameValues]), and the sync engine's machine-local
 *       record keys follow the archive's renamed folders.
 *     - Every script whose source names something this step renamed is named in the log; its source
 *       is the operator's to edit.
 *
 * ### Where it runs
 *
 * **Before** `createMissingTablesAndColumns`, like `CueModelMigration` and for its reason: the
 * reconciliation would otherwise create every renamed table and column empty beside the old one, and
 * the first DAO write would land in the empty one. So it also runs before steps 1–4, which run inside
 * `initDatabase`'s transaction after the reconciliation and so meet the renamed shape: their raw SQL
 * says the new names. That is why it does not set `PRAGMA user_version` itself — a database at 3 must
 * still run step 4 — and [markTerminologyRenamed] records 5 once step 4 is behind it. It runs after
 * `CueModelMigration`, whose raw SQL says the old names, and which runs only on a database older
 * than both.
 *
 * Never twice: [needed] reads the shape, not the version, and the value renames are idempotent maps
 * besides. A failure after this step's transaction commits leaves a renamed database below 5, which
 * the next start finishes without renaming again.
 */
internal object TerminologyMigration {

    /** What one run did, for the log line and the tests. */
    data class Outcome(
        val backup: Path?,
        val tables: List<String>,
        val dropped: List<String>,
        val values: Map<String, Int>,
        val setAside: List<String> = emptyList(),
        val leftOut: Map<String, Int> = emptyMap(),
    )

    /**
     * One table rebuilt from its model: its name before, and each renamed column's old name.
     * [exactlyOne] names, by their old names, the columns of an exactly-one CHECK the model declares
     * and an older table may not have had: a row naming none or several of them is left out.
     */
    private class Rebuild(
        val old: String,
        val table: Table,
        val columns: Map<String, String>,
        val exactlyOne: List<String> = emptyList(),
        /**
         * Columns the model no longer has but a later step still reads, by name with their DDL: added
         * back and copied when the old table has them. The rebuild would otherwise drop them first.
         */
        val carried: Map<String, String> = emptyMap(),
    )

    private val FX_COLUMNS = mapOf(
        "fx_type" to "effect_type",
        "family" to "category",
        "attribute" to "property_name",
        "cycle" to "beat_division",
        "spread_order" to "distribution",
        "timing" to "step_timing",
        "amount" to "size",
        "runs" to "n_shot",
        "spread_segments" to "spread_blocks",
        "spread_parts" to "spread_groups",
    )

    /** A boolean Timing column's old name, by its new one: copied as `HEAD` / `CYCLE`. */
    private val TIMINGS = mapOf("timing" to "step_timing", "default_timing" to "default_step_timing")

    /**
     * Every table the step rebuilds, `palettes` first: the tables that reference it are created from
     * the model, which would otherwise create an empty `palettes` of its own.
     */
    private val REBUILDS = listOf(
        Rebuild("templates", DaoPalettes, emptyMap()),
        Rebuild("template_rows", DaoPaletteRows, mapOf("palette_id" to "template_id", "attribute" to "property_name")),
        Rebuild("template_effects", DaoPaletteFx, FX_COLUMNS + ("palette_id" to "template_id")),
        Rebuild("look_effects", DaoLookFx, FX_COLUMNS),
        Rebuild("cue_ad_hoc_effects", DaoCueAdHocFx, FX_COLUMNS),
        // Step 9 lifts the retired move-in-dark flag into its cue's setting, so it must survive this one.
        Rebuild(
            "cue_property_assignments", DaoCueAttributeAssignments, mapOf("attribute" to "property_name"),
            carried = mapOf("move_in_dark" to "BOOLEAN DEFAULT 0 NOT NULL"),
        ),
        Rebuild("look_rows", DaoLookRows, mapOf("attribute" to "property_name")),
        Rebuild(
            "cue_layers", DaoCueLayers, mapOf("palette_id" to "template_id", "attribute_mask" to "property_mask"),
            exactlyOne = listOf("look_id", "template_id"),
        ),
        Rebuild("busk_pads", DaoBuskPads, mapOf("palette_id" to "template_id"), exactlyOne = listOf("template_id", "look_id", "cue_id")),
        Rebuild("speed_masters", DaoSpeedMasters, mapOf("family" to "usage")),
        Rebuild(
            "fx_definitions",
            DaoFxDefinitions,
            mapOf(
                "fx_id" to "effect_id",
                "family" to "category",
                "fx_mode" to "effect_mode",
                "compatible_attributes" to "compatible_properties",
                "default_timing" to "default_step_timing",
            ),
        ),
    )

    /**
     * Run the step if [dataSource]'s database still needs it. [dbPath] is the database file, the
     * backup's directory and stem; null skips the backup (an in-memory database has no file to copy).
     * Answers null when there was nothing to do.
     */
    fun runIfNeeded(dataSource: DataSource, database: Database, dbPath: String?, now: Instant = Instant.now()): Outcome? {
        if (!transaction(database) { needed() }) return null
        val backup = dbPath?.let { backUpDatabase(dataSource, it, "pre-terminology", now) }
        val outcome = transaction(database) { migrate() }.copy(backup = backup)
        logger.info(
            "Terminology rename: tables {} rebuilt in the glossary's words{}; values renamed: {}; backup {}",
            outcome.tables.joinToString(), outcome.dropped.takeIf { it.isNotEmpty() }?.let { "; unused columns dropped: ${it.joinToString()}" } ?: "",
            outcome.values.entries.joinToString { "${it.key} ${it.value}" }.ifEmpty { "none" }, backup ?: "skipped",
        )
        outcome.setAside.forEach { logger.warn("Terminology rename: a retired table already held a new name, so it is kept as {}", it) }
        outcome.leftOut.forEach { (table, rows) ->
            logger.warn("Terminology rename: {} {} row(s) named no record, or several, and were left out (the readers already skipped them)", rows, table)
        }
        return outcome
    }

    /** Whether the database is below [TERMINOLOGY_RENAMED_USER_VERSION] and still holds an old name. */
    internal fun JdbcTransaction.needed(): Boolean {
        if (userVersion() >= TERMINOLOGY_RENAMED_USER_VERSION) return false
        return REBUILDS.any { tableExists(it.old) && (it.old != it.table.tableName || it.columns.values.any { c -> c in columnsOf(it.old) }) }
    }

    internal fun JdbcTransaction.migrate(): Outcome {
        val rebuilt = ArrayList<String>()
        val dropped = ArrayList<String>()
        val setAside = ArrayList<String>()
        val leftOut = LinkedHashMap<String, Int>()
        for (rebuild in REBUILDS) {
            if (!tableExists(rebuild.old)) continue
            val oldColumns = columnsOf(rebuild.old)
            if (rebuild.old == rebuild.table.tableName && rebuild.columns.values.none { it in oldColumns }) continue
            val name = rebuild.table.tableName
            // A table retired long ago may still hold the new name (the positional colour lists were
            // `palettes`, dropped from the model but never from a database). Nothing reads it; it is
            // kept beside the rest under a name nothing will take, rather than refusing to start.
            if (rebuild.old != name && tableExists(name)) {
                val retired = "${name}_retired"
                check(!tableExists(retired)) { "Terminology rename: $name and $retired both exist beside ${rebuild.old}" }
                exec("PRAGMA legacy_alter_table = ON", explicitStatementType = StatementType.OTHER)
                try {
                    exec("ALTER TABLE $name RENAME TO $retired")
                } finally {
                    exec("PRAGMA legacy_alter_table = OFF", explicitStatementType = StatementType.OTHER)
                }
                setAside += retired
            }
            rebuildFromModel(rebuild.old, rebuild.table, "pre_terminology") { aside ->
                val carried = rebuild.carried.filterKeys { it in oldColumns }
                carried.forEach { (column, ddl) -> exec("ALTER TABLE $name ADD COLUMN \"$column\" $ddl") }
                val copied = rebuild.table.columns.map { it.name }.mapNotNull { column ->
                    val source = rebuild.columns[column]?.takeIf { it in oldColumns } ?: column.takeIf { it in oldColumns }
                    source?.let { column to if (column in TIMINGS) timing(it) else "\"$it\"" }
                } + carried.keys.map { it to "\"$it\"" }
                val filter = rebuild.exactlyOne.filter { it in oldColumns }
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString(" + ", prefix = " WHERE ", postfix = " = 1") { "(\"$it\" IS NOT NULL)" }
                    .orEmpty()
                exec("INSERT INTO $name (${copied.joinToString { "\"${it.first}\"" }}) SELECT ${copied.joinToString { it.second }} FROM $aside$filter")
                if (filter.isNotEmpty()) {
                    var left = 0
                    exec("SELECT (SELECT count(*) FROM $aside) - (SELECT count(*) FROM $name)") { rs -> if (rs.next()) left = rs.getInt(1) }
                    if (left > 0) leftOut[name] = left
                }
                val used = copied.map { it.second.trim('"') }.toSet() + TIMINGS.values
                dropped += oldColumns.filter { it !in used }.map { "${rebuild.old}.$it" }
            }
            rebuilt += if (rebuild.old == name) name else "${rebuild.old} → $name"
        }
        val values = renameValues()
        logScriptEdits()
        // The schema reconciliation that follows must read the database as it now is.
        currentDialectMetadata.resetCaches()
        return Outcome(backup = null, tables = rebuilt, dropped = dropped, values = values, setAside = setAside, leftOut = leftOut)
    }

    /** A boolean Timing column [old] as the text it is now; null stays the FX type's own. */
    private fun timing(old: String) = "CASE \"$old\" WHEN 1 THEN '$TIMING_HEAD' WHEN 0 THEN '$TIMING_CYCLE' END"

    /**
     * The stored values §4.3 renamed, each through the map the importer reads an archive with, counted
     * by kind for the log. `composite` is left for step 4, which knows the output it named.
     */
    private fun JdbcTransaction.renameValues(): Map<String, Int> {
        val counts = LinkedHashMap<String, Int>()
        fun count(kind: String, n: Int) { if (n > 0) counts[kind] = (counts[kind] ?: 0) + n }

        for (table in listOf(DaoLookFx, DaoCueAdHocFx, DaoPaletteFx).map { it.tableName }) {
            if (!tableExists(table)) continue
            count("Orders", renameColumn(table, "spread_order", LegacyNames::order))
            count("families", renameColumn(table, "family", LegacyNames::family))
            count("palette references", renameColumn(table, "parameters", ::parameters))
        }
        for (table in listOf(DaoPaletteRows, DaoLookRows, DaoCueAttributeAssignments).map { it.tableName }) {
            if (tableExists(table)) count("palette references", renameColumn(table, "value", LegacyNames::paletteRefs))
        }
        for (table in listOf(DaoSpeedMasters, DaoFxDefinitions).map { it.tableName }) {
            if (tableExists(table)) count("families", renameColumn(table, "family", LegacyNames::family))
        }
        if (tableExists(DaoFxDefinitions.tableName)) {
            count("palette references", renameColumn(DaoFxDefinitions.tableName, "parameters", LegacyNames::definitionParameters))
        }
        if (tableExists("control_surface_bindings")) {
            count("surface bindings", renameColumn("control_surface_bindings", "target_payload", LegacyNames::bindingPayload))
            renameColumn("control_surface_bindings", "target_type", LegacyNames::bindingType)
        }
        for (table in listOf("sync_state", "sync_session_conflict")) {
            if (tableExists(table)) count("sync record keys", renameColumn(table, "table_name", LegacyNames::folder))
        }
        return counts
    }

    private val PARAMETERS = MapSerializer(String.serializer(), String.serializer())

    private fun parameters(stored: String): String {
        if (!stored.contains("tmpl:", ignoreCase = true)) return stored
        val parameters = runCatching { Json.decodeFromString(PARAMETERS, stored) }.getOrNull() ?: return stored
        return Json.encodeToString(PARAMETERS, parameters.mapValues { (_, value) -> LegacyNames.paletteRefs(value) })
    }

    /** Every row of [table] whose [column] [rename] changes, rewritten; answers how many. */
    private fun JdbcTransaction.renameColumn(table: String, column: String, rename: (String) -> String): Int {
        val changed = ArrayList<Pair<Int, String>>()
        exec("SELECT id, \"$column\" FROM $table WHERE \"$column\" IS NOT NULL") { rs ->
            while (rs.next()) {
                val stored = rs.getString(2)
                val renamed = rename(stored)
                if (renamed != stored) changed += rs.getInt(1) to renamed
            }
        }
        for ((id, value) in changed) {
            exec("UPDATE $table SET \"$column\" = ? WHERE id = ?", listOf(TextColumnType() to value, IntegerColumnType() to id))
        }
        return changed.size
    }

    /** Every script whose source names something this step renamed, with what to edit. */
    private fun JdbcTransaction.logScriptEdits() {
        if (!tableExists("scripts")) return
        exec("SELECT s.name, p.name, s.script FROM scripts s LEFT JOIN projects p ON p.id = s.project_id") { rs ->
            while (rs.next()) {
                val edits = scriptEdits(rs.getString(3) ?: continue)
                if (edits.isNotEmpty()) {
                    logger.warn("Script '{}' in '{}' needs editing: {}", rs.getString(1), rs.getString(2) ?: "no project", edits.joinToString("; "))
                }
            }
        }
    }

    /** What in a script's [source] this step renamed under it, each in words. */
    internal fun scriptEdits(source: String): List<String> = buildList {
        for ((old, now) in listOf("LINEAR" to "NORMAL", "CENTER_OUT" to "CENTRE_OUT", "EDGES_IN" to "CENTRE_IN")) {
            if (Regex("DistributionStrategy\\.$old\\b").containsMatchIn(source)) add("DistributionStrategy.$old is now SpreadOrder.$now")
        }
        if ("tmpl:" in source) add("a palette reference is pal:{uuid}, not tmpl:{uuid}")
        if (Regex("category\\s*=\\s*\"dimmer\"").containsMatchIn(source)) add("category = \"dimmer\" is now family = \"intensity\"")
        if (Regex("category\\s*=\\s*\"palette\"").containsMatchIn(source)) add("category = \"palette\" is now family = \"steps\"")
    }

}

/**
 * Record [TERMINOLOGY_RENAMED_USER_VERSION] once step 4 is behind the rename — every database, the
 * fresh one included, since it is born in the glossary's words. Last of the one-shot steps inside
 * `initDatabase`'s transaction (step 6, [AttributeNamesMigration], runs after it); see
 * [TerminologyMigration] for why the rename does not record it itself.
 */
internal fun JdbcTransaction.markTerminologyRenamed() {
    var version = 0
    exec("PRAGMA user_version") { rs -> if (rs.next()) version = rs.getInt(1) }
    if (version >= TERMINOLOGY_RENAMED_USER_VERSION) return
    check(version >= FX_LIBRARY_REWRITTEN_USER_VERSION) { "Terminology rename: step 4 has not run (user_version $version)" }
    exec("PRAGMA user_version = $TERMINOLOGY_RENAMED_USER_VERSION", explicitStatementType = StatementType.OTHER)
}
