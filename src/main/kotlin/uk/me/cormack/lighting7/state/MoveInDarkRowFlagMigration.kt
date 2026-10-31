package uk.me.cormack.lighting7.state

import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.vendors.currentDialectMetadata
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.models.CueMoveInDarkMode
import java.nio.file.Path
import java.time.Instant
import javax.sql.DataSource

private val logger = LoggerFactory.getLogger("MoveInDarkRowFlagMigration")

/** `PRAGMA user_version` once [MoveInDarkRowFlagMigration] has run, the step after [FX_SIBLINGS_USER_VERSION]. */
internal const val MOVE_IN_DARK_ROW_FLAG_USER_VERSION = 9

/**
 * Database step 9 (move-in-dark plan D13): the per-row `cue_attribute_assignments.move_in_dark` flag
 * retired. Every cue with a flagged row takes the cue setting *Early* — the look-ahead that does what
 * the flag did and more — and the column is dropped. The importer lifts an older archive's flag the
 * same way.
 *
 * Runs after step 8, once the schema has given `cues` its `move_in_dark_mode` column. A database with
 * no such column (a fresh one) is only marked; one with the column but no flagged row loses the
 * column and is marked; one that flags some is backed up first to `<db>.pre-move-in-dark-<utc>.db`,
 * then converted and marked in one transaction, each cue named in the log. Never twice: below
 * [MOVE_IN_DARK_ROW_FLAG_USER_VERSION] is the only way in.
 */
internal object MoveInDarkRowFlagMigration {
    /** What the step did: the backup taken, if any, and the cues set *Early*, by name. */
    data class Outcome(val backup: Path?, val cues: List<String>)

    /**
     * Run the step if the database is below [MOVE_IN_DARK_ROW_FLAG_USER_VERSION]. [dbPath] is the
     * database file, the backup's directory and stem; null skips the backup. Answers null when the
     * database was already marked.
     */
    fun runIfNeeded(dataSource: DataSource, database: Database, dbPath: String?, now: Instant = Instant.now()): Outcome? {
        val flagged = transaction(database) {
            val version = userVersion()
            if (version >= MOVE_IN_DARK_ROW_FLAG_USER_VERSION) return@transaction null
            check(version >= FX_SIBLINGS_USER_VERSION) { "Move in dark row flag: step 8 has not run (user_version $version)" }
            if (!hasFlagColumn()) return@transaction emptyList<Pair<Int, String>>().also { mark() }
            flaggedCues()
        } ?: return null
        if (flagged.isEmpty()) {
            transaction(database) {
                if (hasFlagColumn()) dropFlagColumn()
                mark()
            }
            return Outcome(backup = null, cues = emptyList())
        }
        val backup = dbPath?.let { backUpDatabase(dataSource, it, "pre-move-in-dark", now) }
        val outcome = transaction(database) {
            val cues = flaggedCues()
            val ids = cues.joinToString(",") { it.first.toString() }
            exec(
                "UPDATE cues SET move_in_dark_mode = '${CueMoveInDarkMode.EARLY.name}' " +
                    "WHERE id IN ($ids) AND move_in_dark_mode = '${CueMoveInDarkMode.STACK.name}'",
            )
            dropFlagColumn()
            mark()
            Outcome(backup, cues.map { it.second })
        }
        for (cue in outcome.cues) logger.info("Move in dark: cue '{}' flagged a row, so moves its heads Early", cue)
        logger.info("Move in dark: {} cue(s) set Early and the row flag dropped; backup {}", outcome.cues.size, backup ?: "skipped")
        return outcome
    }

    private fun JdbcTransaction.hasFlagColumn(): Boolean = "move_in_dark" in columnsOf("cue_attribute_assignments")

    /** Every cue with a flagged row, by id, with its name, in id order. */
    private fun JdbcTransaction.flaggedCues(): List<Pair<Int, String>> {
        val cues = mutableListOf<Pair<Int, String>>()
        exec(
            "SELECT DISTINCT c.id, c.name FROM cues c JOIN cue_attribute_assignments a ON a.cue_id = c.id " +
                "WHERE a.move_in_dark <> 0 ORDER BY c.id",
        ) { rs ->
            while (rs.next()) cues += rs.getInt(1) to rs.getString(2)
        }
        return cues
    }

    private fun JdbcTransaction.dropFlagColumn() {
        exec("ALTER TABLE cue_attribute_assignments DROP COLUMN move_in_dark")
        currentDialectMetadata.resetCaches()
    }

    private fun JdbcTransaction.mark() {
        exec("PRAGMA user_version = $MOVE_IN_DARK_ROW_FLAG_USER_VERSION", explicitStatementType = StatementType.OTHER)
    }
}
