package uk.me.cormack.lighting7.state

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.vendors.currentDialectMetadata
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.models.CueStackEntryKind
import uk.me.cormack.lighting7.models.DaoCueStackEntries
import uk.me.cormack.lighting7.models.DaoCues
import uk.me.cormack.lighting7.models.DaoPromptBookAnchors
import java.nio.file.Path
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

private val logger = LoggerFactory.getLogger("CueModelMigration")

/**
 * The cue model plan's one startup migration (D5): a stack's list moves off the cue onto
 * `cue_stack_entries`, and `cues` and `prompt_book_anchors` are rebuilt without what moved.
 *
 * ### What it does
 *
 * It runs only while `cues` still has `cue_stack_id` (`PRAGMA table_info`), so a database it has
 * already converted — and a fresh one, which has no `cues` at all — is left alone, and a second
 * start does nothing.
 *
 *  1. **A backup first**: `VACUUM INTO '<db>.pre-cue-model-<utc>.db'`, on a raw connection in
 *     autocommit, because `VACUUM` refuses to run inside a transaction and Exposed always opens one.
 *  2. **Then one transaction**, so a failure leaves the database exactly as it was:
 *     - `cue_stack_entries` is created from the model, and each cue becomes one entry in its stack:
 *       its sort order as a double, renormalised per stack to `1.0, 2.0, …` by `(sort_order, id)`,
 *       its number, its auto flag and its follow.
 *     - A MARKER cue becomes a **marker entry** labelled with its name, keeping the cue's uuid (so
 *       the archive, which still writes a marker as a MARKER cue whose uuid is the entry's, is
 *       unchanged), and the cue goes with its children: layers, effects, rows, hooks, scenery,
 *       events, and the busk pads and cue slots that named it.
 *     - Every prompt-book anchor is re-pointed from its cue to that cue's entry. An anchor whose
 *       cue no longer exists is dropped and counted.
 *     - `cues` and `prompt_book_anchors` are rebuilt from `SchemaUtils.createStatements`, so their
 *       shape is exactly the model's, and their rows copied by id. `uq_cue_number_per_stack` goes;
 *       `State.initDatabase` creates `uq_entry_number_per_stack` in its place.
 *
 * ### Why it runs before the schema is created
 *
 * `InstallBootstrap.kt` says a recovered migration runs "after the schema is created and before
 * anything reads it". This one is the exception, and runs **before**
 * `createMissingTablesAndColumns`: the reconciliation would try to add the model's `NOT NULL`
 * `prompt_book_anchors.entry_id` to a table that already has rows, which SQLite refuses, and it
 * cannot drop the moved `NOT NULL` `cues.cue_stack_id` at all — so on an unconverted database the
 * first DAO insert into `cues` would fail, the very lesson the old `StateMigrations.kt` recorded
 * ("a migration that drops a `NOT NULL` column must run before any migration that inserts through
 * the DAO"). It needs nothing the reconciliation would create, because it creates its own table and
 * rebuilds the two it changes, and it touches the rest only to delete a marker cue's rows. So it
 * goes first, and the reconciliation then finds `cues` and `prompt_book_anchors` already in the
 * model's shape.
 *
 * It reads and writes only through raw SQL: a migration must keep meaning what it meant on the day
 * it ran, whatever the model says later. The two table shapes it builds are the one exception, and
 * that is the point of building them from the model.
 *
 * `PRAGMA foreign_keys` is off on this desk (`models/fixturePatchPlacements.kt`), which is what lets
 * a table be dropped and rebuilt under the references other tables hold to it. The rebuild still
 * renames the old table aside under `legacy_alter_table`, so those references keep naming `cues`
 * rather than following the old table to its temporary name.
 */
internal object CueModelMigration {

    /** What one run did, for the log line and the tests. */
    data class Outcome(
        val backup: Path?,
        val entries: Int,
        val markers: Int,
        val anchorsRepointed: Int,
        val anchorsDropped: Int,
    )

    /**
     * Run the migration if [dataSource]'s database still needs it. [dbPath] is the database file,
     * the backup's directory and stem; null skips the backup (an in-memory database has no file to
     * copy). Answers null when there was nothing to do.
     */
    fun runIfNeeded(dataSource: DataSource, database: Database, dbPath: String?, now: Instant = Instant.now()): Outcome? {
        val needed = dataSource.connection.use { connection -> columnExists(connection, "cues", "cue_stack_id") }
        if (!needed) return null

        val backup = dbPath?.let { backUpDatabase(dataSource, it, "pre-cue-model", now) }
        val outcome = transaction(database) { migrate() }.copy(backup = backup)
        logger.info(
            "Cue model migration: {} entr(ies), {} marker(s), {} anchor(s) re-pointed, {} dropped; backup {}",
            outcome.entries, outcome.markers, outcome.anchorsRepointed, outcome.anchorsDropped, backup ?: "skipped",
        )
        return outcome
    }

    /** One old `cues` row, as much of it as the migration moves. */
    private data class OldCue(
        val id: Int,
        val uuid: UUID?,
        val stackId: Int,
        val sortOrder: Int,
        val name: String,
        val cueNumber: String?,
        val cueNumberAuto: Boolean,
        val autoAdvance: Boolean,
        val autoAdvanceDelayNanos: Long?,
        val marker: Boolean,
    )

    internal fun JdbcTransaction.migrate(): Outcome {
        check(!tableExists("cue_stack_entries")) {
            "Cue model migration: cue_stack_entries already exists beside an unconverted cues table"
        }
        val cueColumns = columnsOf("cues")

        fun column(name: String, fallback: String) = if (name in cueColumns) name else "$fallback AS $name"
        val oldCues = mutableListOf<OldCue>()
        exec(
            "SELECT id, uuid, cue_stack_id, name, " +
                "${column("sort_order", "0")}, ${column("cue_number", "NULL")}, " +
                "${column("cue_number_auto", "0")}, ${column("auto_advance", "0")}, " +
                "${column("auto_advance_delay", "NULL")}, ${column("cue_type", "'STANDARD'")} " +
                "FROM cues ORDER BY cue_stack_id, sort_order, id",
        ) { rs ->
            while (rs.next()) {
                oldCues += OldCue(
                    id = rs.getInt("id"),
                    uuid = rs.javaUuid("uuid"),
                    stackId = rs.getInt("cue_stack_id"),
                    sortOrder = rs.getInt("sort_order"),
                    name = rs.getString("name") ?: "",
                    cueNumber = rs.getString("cue_number"),
                    cueNumberAuto = rs.getBoolean("cue_number_auto"),
                    autoAdvance = rs.getBoolean("auto_advance"),
                    autoAdvanceDelayNanos = rs.getLong("auto_advance_delay").takeUnless { rs.wasNull() },
                    marker = rs.getString("cue_type") == "MARKER",
                )
            }
        }

        // ─── Entries ────────────────────────────────────────────────────────
        createFromModel(DaoCueStackEntries)
        val entryByCue = HashMap<Int, Int>()
        var markers = 0
        var position = 0
        var previousStack: Int? = null
        for (cue in oldCues) {
            position = if (cue.stackId == previousStack) position + 1 else 1
            previousStack = cue.stackId
            val kind = if (cue.marker) CueStackEntryKind.MARKER else CueStackEntryKind.CUE
            // A marker keeps the cue's uuid: the archive writes a marker entry as a MARKER cue whose
            // uuid is the entry's, so the file it was is the file it stays.
            val uuid = (if (cue.marker) cue.uuid else null) ?: UUID.randomUUID()
            exec(
                "INSERT INTO cue_stack_entries (stack_id, cue_id, kind, label, ordinal, cue_number, " +
                    "cue_number_auto, auto_advance, auto_advance_delay, uuid) VALUES (" +
                    "${cue.stackId}, ${if (cue.marker) "NULL" else cue.id}, ${sqlText(kind.name)}, " +
                    "${if (cue.marker) sqlText(cue.name) else "NULL"}, ${position.toDouble()}, " +
                    "${if (cue.marker) "NULL" else sqlText(cue.cueNumber)}, " +
                    "${sqlBool(!cue.marker && cue.cueNumberAuto)}, ${sqlBool(!cue.marker && cue.autoAdvance)}, " +
                    "${if (cue.marker) "NULL" else (cue.autoAdvanceDelayNanos?.toString() ?: "NULL")}, " +
                    "${sqlUuid(uuid)})",
            )
            entryByCue[cue.id] = lastInsertRowId()
            if (cue.marker) markers++
        }

        // ─── Markers stop being cues ─────────────────────────────────────────
        val markerIds = oldCues.filter { it.marker }.map { it.id }
        if (markerIds.isNotEmpty()) {
            val ids = markerIds.joinToString(",")
            for (table in MARKER_CHILD_TABLES) {
                if (tableExists(table) && "cue_id" in columnsOf(table)) {
                    exec("DELETE FROM $table WHERE cue_id IN ($ids)")
                }
            }
            exec("DELETE FROM cues WHERE id IN ($ids)")
        }

        // ─── Anchors, by entry ───────────────────────────────────────────────
        var repointed = 0
        var dropped = 0
        if (tableExists("prompt_book_anchors")) {
            val anchorColumns = columnsOf("prompt_book_anchors")
            rebuild(DaoPromptBookAnchors) { old ->
                exec("CREATE TEMP TABLE cue_model_entry_map (cue_id INTEGER PRIMARY KEY, entry_id INTEGER NOT NULL)")
                for ((cueId, entryId) in entryByCue) {
                    exec("INSERT INTO cue_model_entry_map (cue_id, entry_id) VALUES ($cueId, $entryId)")
                }
                val kept = listOf("id", "prompt_book_id", "region", "label", "uuid").filter { it in anchorColumns }
                exec(
                    "INSERT INTO prompt_book_anchors (${(kept + "entry_id").joinToString()}) " +
                        "SELECT ${kept.joinToString { "a.$it" }}, m.entry_id FROM $old a " +
                        "JOIN cue_model_entry_map m ON m.cue_id = a.cue_id",
                )
                exec("SELECT (SELECT count(*) FROM $old) AS total, (SELECT count(*) FROM prompt_book_anchors) AS kept") { rs ->
                    if (rs.next()) {
                        repointed = rs.getInt("kept")
                        dropped = rs.getInt("total") - repointed
                    }
                }
                exec("DROP TABLE cue_model_entry_map")
            }
        }

        // ─── Cues, without what moved ────────────────────────────────────────
        exec("DROP INDEX IF EXISTS uq_cue_number_per_stack")
        rebuild(DaoCues) { old ->
            val kept = DaoCues.columns.map { it.name }.filter { it in cueColumns }
            exec("INSERT INTO cues (${kept.joinToString()}) SELECT ${kept.joinToString()} FROM $old")
        }

        // The schema reconciliation that follows must read the database as it now is.
        currentDialectMetadata.resetCaches()
        return Outcome(
            backup = null,
            entries = oldCues.size,
            markers = markers,
            anchorsRepointed = repointed,
            anchorsDropped = dropped,
        )
    }

    /**
     * The tables whose rows a marker cue takes with it — its children, its scenery and events, and
     * the busk pads and cue slots that named it (a pad or a slot on a marker never fired anything).
     */
    private val MARKER_CHILD_TABLES = listOf(
        "cue_layers", "cue_ad_hoc_effects", "cue_property_assignments", "cue_triggers",
        "cue_scenery", "cue_events", "busk_pads", "cue_slots",
    )

    /** [table] rebuilt in the model's shape, its old self renamed aside as `<name>_pre_cue_model` ([rebuildFromModel]). */
    private fun JdbcTransaction.rebuild(table: Table, copy: JdbcTransaction.(old: String) -> Unit) =
        rebuildFromModel(table.tableName, table, "pre_cue_model", copy)

    // ─── Raw SQL helpers ─────────────────────────────────────────────────────

    private fun columnExists(connection: Connection, table: String, column: String): Boolean =
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA table_info($table)").use { rs ->
                var found = false
                while (rs.next()) if (rs.getString("name") == column) found = true
                found
            }
        }

    private fun JdbcTransaction.lastInsertRowId(): Int {
        var id = 0
        exec("SELECT last_insert_rowid() AS id") { rs -> if (rs.next()) id = rs.getInt("id") }
        return id
    }

    private fun sqlBool(value: Boolean): String = if (value) "1" else "0"

    /**
     * A `javaUUID` column literal — a **blob**, as Exposed binds one. A text uuid would read back
     * through the DAO but never compare equal to a bound uuid (the old migrations' lesson).
     */
    private fun sqlUuid(value: UUID): String =
        java.nio.ByteBuffer.allocate(16)
            .putLong(value.mostSignificantBits)
            .putLong(value.leastSignificantBits)
            .array()
            .joinToString(separator = "", prefix = "x'", postfix = "'") { "%02x".format(it) }

    /**
     * Read a `javaUUID` column from raw SQL. Never `getString`: on SQLite the column is a 16-byte
     * blob, and reading it as text corrupts every byte above 0x7F. A well-formed text uuid is
     * tolerated.
     */
    private fun ResultSet.javaUuid(column: String): UUID? {
        val bytes = getBytes(column) ?: return null
        if (bytes.size == 16) {
            val buffer = java.nio.ByteBuffer.wrap(bytes)
            return UUID(buffer.long, buffer.long)
        }
        return runCatching { UUID.fromString(String(bytes, Charsets.UTF_8)) }.getOrNull()
    }
}
