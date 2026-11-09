package uk.me.cormack.lighting7.state

import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoCueStackEntry
import uk.me.cormack.lighting7.routes.canonicalCueNumber
import uk.me.cormack.lighting7.routes.cueEntriesInOrder
import uk.me.cormack.lighting7.routes.flushPendingEntryWrites
import uk.me.cormack.lighting7.routes.renumberAutoEntries
import java.nio.file.Path
import java.time.Instant
import javax.sql.DataSource

private val logger = LoggerFactory.getLogger("CueNumberMigration")

/** `PRAGMA user_version` once [CueNumberMigration] has run, the step after [PALETTE_LAYER_USER_VERSION]. */
internal const val CUE_NUMBER_USER_VERSION = 11

/**
 * Database step 11: every cue number becomes a number (`routes/cueNumbering.kt`). A number that
 * already is one is written in its canonical spelling (`2.10` is `2.1`, `007` is `7`); one that is
 * not — `S1-3`, `14A`, `intro`, `1.2.3` — is cleared, and so is the later of two spellings of one
 * number in a stack, so each of those entries is numbered from its place again. Every stack it
 * touched then has its auto numbers derived again from position.
 *
 * Runs after step 10. A database whose numbers are all canonical (a fresh one among them) is only
 * marked; one that changes some is backed up first to `<db>.pre-cue-numbers-<utc>.db`, then changed
 * and marked in one transaction, each change named in the log. Never twice: below
 * [CUE_NUMBER_USER_VERSION] is the only way in.
 */
internal object CueNumberMigration {
    /** One entry's number as the step changes it: [to] null clears it, [heldBy] the number another entry keeps. */
    data class Change(val project: String, val stack: String, val from: String, val to: String?, val heldBy: String? = null)

    private data class Target(val entry: DaoCueStackEntry, val to: String?, val heldBy: String?)

    /** What the step did: the backup taken, if any, and every number it changed. */
    data class Outcome(val backup: Path?, val changes: List<Change>)

    fun runIfNeeded(dataSource: DataSource, database: Database, dbPath: String?, now: Instant = Instant.now()): Outcome? {
        val planned = transaction(database) {
            val version = userVersion()
            if (version >= CUE_NUMBER_USER_VERSION) return@transaction null
            check(version >= PALETTE_LAYER_USER_VERSION) { "Cue numbers: step 10 has not run (user_version $version)" }
            plan().flatMap { it.second }
        } ?: return null
        if (planned.isEmpty()) {
            transaction(database) { mark() }
            return Outcome(backup = null, changes = emptyList())
        }
        val backup = dbPath?.let { backUpDatabase(dataSource, it, "pre-cue-numbers", now) }
        val changes = transaction(database) {
            val plans = plan()
            for ((stack, _) in plans) apply(stack)
            mark()
            plans.flatMap { it.second }
        }
        for (change in changes) {
            logger.info(
                "Cue numbers: '{}' in stack '{}' of '{}' {}",
                change.from, change.stack, change.project,
                when {
                    change.to != null -> "is now '${change.to}'"
                    change.heldBy != null -> "is '${change.heldBy}', which another cue there keeps; numbered from its place"
                    else -> "is not a number; numbered from its place"
                },
            )
        }
        logger.info("Cue numbers: {} number(s) changed; backup {}", changes.size, backup ?: "skipped")
        return Outcome(backup, changes)
    }

    /** Every stack with a number to change, and its changes. */
    private fun plan(): List<Pair<DaoCueStack, List<Change>>> = DaoCueStack.all().mapNotNull { stack ->
        val changes = targets(stack).map { Change(stack.project.name, stack.name, it.entry.cueNumber!!, it.to, it.heldBy) }
        changes.takeIf { it.isNotEmpty() }?.let { stack to it }
    }

    /**
     * [stack]'s numbered cue entries whose number changes, each with what it becomes. Of two explicit
     * spellings of one number the entry with the lower uuid keeps it, as the importer's
     * `resolveNumberClashes` decides, so a desk that migrates and one that imports an older archive
     * agree; an auto number an explicit one holds is cleared.
     */
    private fun targets(stack: DaoCueStack): List<Target> = buildList {
        val seen = HashSet<String>()
        val entries = cueEntriesInOrder(stack).filter { !it.cueNumber.isNullOrEmpty() }
        for (entry in entries.sortedWith(compareBy({ it.cueNumberAuto }, { it.uuid.toString() }))) {
            val canonical = canonicalCueNumber(entry.cueNumber!!)
            val kept = canonical?.takeIf { seen.add(it) }
            if (kept != entry.cueNumber) add(Target(entry, kept, heldBy = canonical.takeIf { kept == null }))
        }
    }

    private fun apply(stack: DaoCueStack) {
        val targets = targets(stack)
        targets.forEach { it.entry.cueNumber = null }
        flushPendingEntryWrites()
        targets.forEach { it.entry.cueNumber = it.to }
        renumberAutoEntries(stack)
    }

    private fun JdbcTransaction.mark() {
        exec("PRAGMA user_version = $CUE_NUMBER_USER_VERSION", explicitStatementType = StatementType.OTHER)
    }
}
