package uk.me.cormack.lighting7.state

import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.vendors.currentDialectMetadata
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.midi.LegacyRefs
import uk.me.cormack.lighting7.midi.SurfaceGrammar
import java.nio.file.Path
import java.time.Instant
import javax.sql.DataSource

private val logger = LoggerFactory.getLogger("SurfaceGrammarMigration")

/** `PRAGMA user_version` once [SurfaceGrammarMigration] has run, the step after [BUSK_PAD_STACK_USER_VERSION]. */
internal const val SURFACE_GRAMMAR_USER_VERSION = 15

/**
 * Database step 15 (surface-setup plan session 1): every surface binding in the grammar — a
 * `Binding`, `{does, on, how}`, at a slot with a part.
 *
 *  - Each row's payload goes through [SurfaceGrammar.row], the translate the archive reader shares
 *    (P4): an encoder holding a button variant becomes its push, an int-only cue or stack its uuid in
 *    the row's project, a flash's raw max a percentage, each on/off form its toggle with a How. A row
 *    that cannot be translated — an int naming nothing, a type this build never knew — keeps its
 *    bytes, and reads as unknown.
 *  - `uq_surface_binding_slot`, which had no part, is dropped: the reconciliation has already added
 *    the `part` column and `uq_surface_binding_part_slot` beside it, and SQLite drops an index
 *    without rebuilding its table.
 *
 * Runs after step 14 and after the reconciliation. A database with a binding to translate is backed
 * up first to `<db>.pre-surface-grammar-<utc>.db`; one with none is only marked (its stale index
 * dropped).
 * Never twice: below [SURFACE_GRAMMAR_USER_VERSION] is the only way in, and the translate leaves a
 * translated row alone besides.
 */
internal object SurfaceGrammarMigration {
    private const val TABLE = "control_surface_bindings"
    private const val STALE_INDEX = "uq_surface_binding_slot"

    /** What the step did: the rows it translated, the rows it kept as they were, and the backup taken. */
    data class Outcome(val translated: Int, val kept: Int, val backup: Path?)

    private class Row(val id: Int, val projectId: Int, val deviceTypeKey: String, val controlId: String, val type: String, val payload: String)

    /**
     * Run the step if the database is below [SURFACE_GRAMMAR_USER_VERSION]. [dbPath] is the database
     * file, the backup's directory and stem; null skips the backup. Answers null when the database
     * was already marked.
     */
    fun runIfNeeded(dataSource: DataSource, database: Database, dbPath: String?, now: Instant = Instant.now()): Outcome? {
        val plan = transaction(database) {
            val version = userVersion()
            if (version >= SURFACE_GRAMMAR_USER_VERSION) return@transaction null
            check(version >= BUSK_PAD_STACK_USER_VERSION) { "Surface grammar: step 14 has not run (user_version $version)" }
            val refs = HashMap<Int, LegacyRefs>()
            val translated = mutableListOf<Pair<Int, SurfaceGrammar.Row>>()
            val kept = mutableListOf<Row>()
            for (row in rows()) {
                if (!SurfaceGrammar.isOld(row.payload)) continue
                val ints = refs.getOrPut(row.projectId) { LegacyRefs.inTransaction(row.projectId) }
                val next = SurfaceGrammar.row(row.deviceTypeKey, row.controlId, row.payload, ints)
                if (next == null) kept += row else translated += row.id to next
            }
            translated to kept
        } ?: return null
        val (translated, kept) = plan
        val backup = if (translated.isNotEmpty()) dbPath?.let { backUpDatabase(dataSource, it, "pre-surface-grammar", now) } else null
        transaction(database) {
            exec("DROP INDEX IF EXISTS $STALE_INDEX")
            currentDialectMetadata.resetCaches()
            for ((id, next) in translated) {
                exec(
                    "UPDATE $TABLE SET target_type = ${sqlText(next.targetType)}, target_payload = ${sqlText(next.payload)}, " +
                        "part = ${sqlText(next.part.name)} WHERE id = $id",
                )
            }
            exec("PRAGMA user_version = $SURFACE_GRAMMAR_USER_VERSION", explicitStatementType = StatementType.OTHER)
        }
        for (row in kept) {
            logger.warn(
                "Surface grammar: binding {} ({} on {}) kept as it is and reads as unknown: it names nothing this build can translate",
                row.id, row.type, row.controlId,
            )
        }
        if (translated.isNotEmpty() || kept.isNotEmpty()) {
            logger.info("Surface grammar: {} bindings translated, {} kept as they were; backup {}", translated.size, kept.size, backup ?: "skipped")
        }
        return Outcome(translated.size, kept.size, backup)
    }

    private fun JdbcTransaction.rows(): List<Row> {
        val rows = mutableListOf<Row>()
        exec("SELECT id, project_id, device_type_key, control_id, target_type, target_payload FROM $TABLE ORDER BY id") { rs ->
            while (rs.next()) {
                rows += Row(rs.getInt(1), rs.getInt(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6))
            }
        }
        return rows
    }
}
