package uk.me.cormack.lighting7.state

import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.routes.autoNumberChanges
import uk.me.cormack.lighting7.routes.cueEntriesInOrder
import uk.me.cormack.lighting7.routes.flushPendingEntryWrites
import uk.me.cormack.lighting7.routes.renumberAutoEntries
import uk.me.cormack.lighting7.routes.typedNumber
import java.nio.file.Path
import java.time.Instant
import javax.sql.DataSource

private val logger = LoggerFactory.getLogger("AutoNumberTidyMigration")

/** `PRAGMA user_version` once [AutoNumberTidyMigration] has run, the step after [SURFACE_GRAMMAR_USER_VERSION]. */
internal const val AUTO_NUMBER_TIDY_USER_VERSION = 16

/**
 * Database step 16 (venue-rig-fixes plan D9): every stack's auto numbers derived from position and
 * its typed numbers ([renumberAutoEntries]). Typed numbers are never touched.
 *
 * Runs after step 15. A database whose auto numbers already read so (a fresh one among them) is only
 * marked; one that changes some is backed up first to `<db>.pre-auto-numbers-<utc>.db`, then changed
 * and marked in one transaction, each change named in the log by stack and cue. Never twice: below
 * [AUTO_NUMBER_TIDY_USER_VERSION] is the only way in.
 */
internal object AutoNumberTidyMigration {
    /** One auto number the step changes; [from] or [to] null is a blank. */
    data class Change(val project: String, val stack: String, val cue: String, val from: String?, val to: String?)

    /** What the step did: the backup taken, if any, and every number it changed. */
    data class Outcome(val backup: Path?, val changes: List<Change>)

    fun runIfNeeded(dataSource: DataSource, database: Database, dbPath: String?, now: Instant = Instant.now()): Outcome? {
        val needed = transaction(database) {
            val version = userVersion()
            if (version >= AUTO_NUMBER_TIDY_USER_VERSION) return@transaction null
            check(version >= SURFACE_GRAMMAR_USER_VERSION) { "Auto numbers: step 15 has not run (user_version $version)" }
            stacks().any { autoNumberChanges(it).isNotEmpty() }
        } ?: return null
        if (!needed) {
            transaction(database) { mark() }
            return Outcome(backup = null, changes = emptyList())
        }
        val backup = dbPath?.let { backUpDatabase(dataSource, it, "pre-auto-numbers", now) }
        val changes = transaction(database) {
            val written = stacks().flatMap(::renumber)
            mark()
            written
        }
        for (change in changes) {
            logger.info(
                "Auto numbers: cue '{}' in stack '{}' of '{}' was {} and is now {}",
                change.cue, change.stack, change.project, change.from?.let { "Q$it" } ?: "blank", change.to?.let { "Q$it" } ?: "blank",
            )
        }
        logger.info("Auto numbers: {} number(s) changed; backup {}", changes.size, backup ?: "skipped")
        return Outcome(backup, changes)
    }

    private fun stacks(): List<DaoCueStack> = DaoCueStack.all().sortedBy { it.id.value }

    /** Derive [stack]'s auto numbers again, answering each number that changed, in stack order. */
    private fun renumber(stack: DaoCueStack): List<Change> {
        val before = cueEntriesInOrder(stack).filter { it.typedNumber == null }.associate { it.id.value to it.cueNumber }
        renumberAutoEntries(stack)
        flushPendingEntryWrites()
        return cueEntriesInOrder(stack)
            .filter { it.id.value in before && it.cueNumber != before[it.id.value] }
            .map { Change(stack.project.name, stack.name, it.cue?.name.orEmpty(), before[it.id.value], it.cueNumber) }
    }

    private fun JdbcTransaction.mark() {
        exec("PRAGMA user_version = $AUTO_NUMBER_TIDY_USER_VERSION", explicitStatementType = StatementType.OTHER)
    }
}
