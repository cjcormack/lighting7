package uk.me.cormack.lighting7.state

import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.vendors.currentDialectMetadata
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.models.DaoBuskPads
import java.nio.file.Path
import java.time.Instant
import javax.sql.DataSource

private val logger = LoggerFactory.getLogger("BuskPadStackMigration")

/** `PRAGMA user_version` once [BuskPadStackMigration] has run, the step after [RETIRED_REMOTE_ACCESS_USER_VERSION]. */
internal const val BUSK_PAD_STACK_USER_VERSION = 14

/**
 * Database step 14 (busk-stack plan D9): a busk pad may name a cue stack. The reconciliation adds
 * `busk_pads.cue_stack_id`, but SQLite cannot alter a CHECK, and the terminology step rebuilt the
 * table with `busk_pad_exactly_one_ref` over three arms — so a row naming only a stack would fail it.
 * While that CHECK does not name `cue_stack_id`, the table is rebuilt from the model, rows copied by
 * id; a database whose CHECK already names it, or that has none, is only marked.
 *
 * Runs after step 13 and after the reconciliation. A table holding pads is backed up first to
 * `<db>.pre-busk-stack-<utc>.db`; an empty one has nothing to lose. Never twice: below
 * [BUSK_PAD_STACK_USER_VERSION] is the only way in.
 */
internal object BuskPadStackMigration {
    private const val TABLE = "busk_pads"
    private const val CHECK = "busk_pad_exactly_one_ref"

    /** What the step did: whether it rebuilt the table, the pads it carried, and the backup taken. */
    data class Outcome(val rebuilt: Boolean, val pads: Int, val backup: Path?)

    /**
     * Run the step if the database is below [BUSK_PAD_STACK_USER_VERSION]. [dbPath] is the database
     * file, the backup's directory and stem; null skips the backup. Answers null when the database
     * was already marked.
     */
    fun runIfNeeded(dataSource: DataSource, database: Database, dbPath: String?, now: Instant = Instant.now()): Outcome? {
        val found = transaction(database) {
            val version = userVersion()
            if (version >= BUSK_PAD_STACK_USER_VERSION) return@transaction null
            check(version >= RETIRED_REMOTE_ACCESS_USER_VERSION) { "Busk pad stacks: step 13 has not run (user_version $version)" }
            val stale = checkExpression(tableSql())?.let { "cue_stack_id" !in it } ?: false
            stale to (if (stale) padCount() else 0)
        } ?: return null
        val (rebuild, pads) = found
        val backup = if (rebuild && pads > 0) dbPath?.let { backUpDatabase(dataSource, it, "pre-busk-stack", now) } else null
        transaction(database) {
            if (rebuild) {
                rebuildFromModel(TABLE, DaoBuskPads, "pre_busk_stack") { aside ->
                    val old = columnsOf(aside)
                    val copied = DaoBuskPads.columns.map { it.name }.filter { it in old }
                    val list = copied.joinToString { "\"$it\"" }
                    exec("INSERT INTO $TABLE ($list) SELECT $list FROM $aside")
                }
                currentDialectMetadata.resetCaches()
            }
            exec("PRAGMA user_version = $BUSK_PAD_STACK_USER_VERSION", explicitStatementType = StatementType.OTHER)
        }
        if (rebuild) logger.info("Busk pad stacks: rebuilt {} with a four-arm CHECK, {} pads carried; backup {}", TABLE, pads, backup ?: "skipped")
        return Outcome(rebuild, pads, backup)
    }

    private fun JdbcTransaction.tableSql(): String? {
        var sql: String? = null
        exec("SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ${sqlText(TABLE)}") { rs -> if (rs.next()) sql = rs.getString(1) }
        return sql
    }

    private fun JdbcTransaction.padCount(): Int {
        var count = 0
        exec("SELECT COUNT(*) FROM $TABLE") { rs -> if (rs.next()) count = rs.getInt(1) }
        return count
    }

    /**
     * The expression of the [CHECK] constraint in [ddl], or null when there is none. Read by its
     * parentheses rather than by position, since `ALTER TABLE … ADD COLUMN` writes the new column into
     * the DDL after the constraints.
     */
    internal fun checkExpression(ddl: String?): String? {
        if (ddl == null) return null
        val at = ddl.indexOf(CHECK).takeIf { it >= 0 } ?: return null
        val open = ddl.indexOf('(', at).takeIf { it >= 0 } ?: return null
        var depth = 0
        for (i in open until ddl.length) {
            when (ddl[i]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return ddl.substring(open, i + 1)
            }
        }
        return null
    }
}
