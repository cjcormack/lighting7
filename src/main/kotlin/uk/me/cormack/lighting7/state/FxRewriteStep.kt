package uk.me.cormack.lighting7.state

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.fx.normalizeFxType
import uk.me.cormack.lighting7.models.DaoCueAdHocFx
import uk.me.cormack.lighting7.models.DaoCues
import uk.me.cormack.lighting7.models.DaoFxDefinitions
import uk.me.cormack.lighting7.models.DaoLookFx
import uk.me.cormack.lighting7.models.DaoLooks
import uk.me.cormack.lighting7.models.DaoPaletteFx
import uk.me.cormack.lighting7.models.DaoPalettes
import uk.me.cormack.lighting7.models.DaoProjects
import uk.me.cormack.lighting7.models.DaoScripts
import uk.me.cormack.lighting7.sync.LegacyNames
import java.nio.file.Path
import java.time.Instant
import javax.sql.DataSource

/**
 * One fx-settings database step (steps 7 and 8): every stored Look, cue and palette FX that
 * [rewrite] moves, rewritten in place — its type, family, parameters and attribute, the four
 * columns those rewrites read and write — and each named in the log by its owner; and every stored
 * script and FX definition that [oldIds] or [stale] says would read differently now, named in a
 * warning and never rewritten, as is a definition taking [newId], which would run every rewritten FX.
 *
 * Runs only from [after] to [version]. A database holding nothing to rewrite is only marked; one that
 * does is backed up first to `<db>.<backupStem>-<utc>.db`, then rewritten and marked in one
 * transaction.
 */
internal class FxRewriteStep(
    loggerName: String,
    /** The log's name for the step, `FX consolidation`. */
    private val label: String,
    private val version: Int,
    private val after: Int,
    private val backupStem: String,
    /** What the closing log line says the FX were moved onto. */
    private val onto: String,
    private val rewrite: (LegacyNames.StoredFx, (String) -> LegacyNames.Definition?) -> LegacyNames.Rewrite?,
    /** The old ids a script may still quote outside `effect(…)`, which a registry lookup no longer finds. */
    oldIds: Set<String>,
    private val newId: String,
    /** What else in a script's source this step changes the meaning of, each in words. */
    private val stale: (String) -> List<String> = { emptyList() },
) {
    private val logger = LoggerFactory.getLogger(loggerName)

    /** What one run did, for the log line and the tests. */
    data class Outcome(val backup: Path?, val fx: Map<String, Int>)

    /** One FX the step rewrites: its row, what it becomes, and the words the log names it with. */
    private class Pending(val id: Int, val fx: LegacyNames.StoredFx, val said: String, val moves: Boolean)

    /**
     * Run the step if the database is below [version]. [dbPath] is the database file, the backup's
     * directory and stem; null skips the backup (an in-memory database has no file to copy). Answers
     * null when the database was already marked.
     */
    fun runIfNeeded(dataSource: DataSource, database: Database, dbPath: String?, now: Instant = Instant.now()): Outcome? {
        val (pending, warnings) = transaction(database) {
            val current = userVersion()
            if (current >= version) return@transaction null
            check(current >= after) { "$label: step $after has not run (user_version $current)" }
            pendingByHome() to staleScripts()
        } ?: return null
        for (warning in warnings) logger.warn("{}: {}", label, warning)
        if (pending.values.all { it.isEmpty() }) {
            transaction(database) { mark() }
            return Outcome(backup = null, fx = emptyMap())
        }
        val backup = dbPath?.let { backUpDatabase(dataSource, it, backupStem, now) }
        val outcome = transaction(database) {
            // Read once, in the probe: nothing else writes while the desk starts.
            val fx = FX_HOMES.associate { home ->
                val rewrites = pending.getValue(home.name)
                for (p in rewrites) {
                    home.table.update({ home.table.id eq p.id }) {
                        it[home.fxType] = p.fx.fxType
                        it[home.family] = p.fx.family
                        it[home.parameters] = p.fx.parameters
                        it[home.attribute] = p.fx.attribute
                    }
                    if (p.moves) logger.warn(p.said) else logger.info(p.said)
                }
                home.name to rewrites.size
            }
            mark()
            Outcome(backup, fx)
        }
        logger.info("{}: {} onto {}; backup {}", label, outcome.fx.entries.joinToString { "${it.value} ${it.key} FX" }, onto, backup ?: "skipped")
        return outcome
    }

    /** Every FX the step rewrites, by home, the projects and definitions read once. */
    private fun JdbcTransaction.pendingByHome(): Map<String, List<Pending>> {
        val projects = DaoProjects.selectAll().associate { it[DaoProjects.id].value to it[DaoProjects.name] }
        val definitions = DaoFxDefinitions.selectAll()
            .map { it[DaoFxDefinitions.project].value to normalizeFxType(it[DaoFxDefinitions.fxId]) }.toSet()
        return FX_HOMES.associate { home -> home.name to pending(home, projects, definitions) }
    }

    private fun JdbcTransaction.pending(home: FxHome, projects: Map<Int, String>, definitions: Set<Pair<Int, String>>): List<Pending> {
        val owners = home.owners(this)
        return home.table.selectAll().orderBy(home.table.id).mapNotNull { row ->
            val (name, projectId) = owners[row[home.owner].value] ?: ("#${row[home.owner].value}, deleted" to null)
            val stored = LegacyNames.StoredFx(
                fxType = row[home.fxType],
                family = row[home.family],
                // Only the four fields above are read or written; the rest pass through unseen.
                cycle = 0.0, order = "", phaseOffset = 0.0, direction = "", runs = null, spread = 0.0,
                parameters = row[home.parameters],
                attribute = row[home.attribute],
            )
            val result = rewrite(stored) { type ->
                if (projectId != null && (projectId to normalizeFxType(type)) in definitions) LegacyNames.Definition("SLIDER", emptySet()) else null
            } ?: return@mapNotNull null
            val project = projectId?.let { projects[it] } ?: "no project"
            val notes = if (result.notes.isEmpty()) "" else "; output moves: ${result.notes.joinToString("; ")}"
            Pending(
                row[home.table.id].value, result.fx,
                "${home.name} '$name' in '$project': ${stored.fxType} — ${result.changes.joinToString("; ")}$notes",
                result.notes.isNotEmpty(),
            )
        }
    }

    /** An old id named in quotes, other than as the id `effect(…)` takes, which still runs it. */
    private val oldIdOutsideEffect = Regex("(?<!effect\\(\\s{0,8})\"(" + oldIds.joinToString("|") { Regex.escape(it) } + ")\"")

    /** Every stored script and FX definition this step changes the meaning of, and every [newId] definition, in words. */
    private fun JdbcTransaction.staleScripts(): List<String> {
        val projects = DaoProjects.selectAll().associate { it[DaoProjects.id].value to it[DaoProjects.name] }
        fun names(text: String) = oldIdOutsideEffect.findAll(text).map { it.groupValues[1] }.toSortedSet()
        fun findings(text: String, lookup: String) = listOfNotNull(
            names(text).takeIf { it.isNotEmpty() }?.let { "names ${it.joinToString()}, which are $newId now ($lookup) — edit it" },
        ) + stale(text)
        val scripts = DaoScripts.selectAll().flatMap { row ->
            findings(row[DaoScripts.script], "effect() still runs them; a registry lookup finds nothing").map {
                "script '${row[DaoScripts.name]}' in '${projects[row[DaoScripts.project].value]}' $it"
            }
        }
        val definitions = DaoFxDefinitions.selectAll().flatMap { row ->
            val at = "FX definition '${row[DaoFxDefinitions.fxId]}' in '${projects[row[DaoFxDefinitions.project].value]}'"
            findings(row[DaoFxDefinitions.script], "a registry lookup finds nothing").map { "$at $it" } +
                listOfNotNull(
                    at.takeIf { normalizeFxType(row[DaoFxDefinitions.fxId]) == normalizeFxType(newId) }
                        ?.let { "$it takes the built-in $newId's id, which its project's rewritten FX now run as — rename it" },
                )
        }
        return scripts + definitions
    }

    private fun JdbcTransaction.mark() {
        exec("PRAGMA user_version = $version", explicitStatementType = StatementType.OTHER)
    }

    /** One FX home: its table, its owner and the four columns the rewrite reads and writes. */
    private class FxHome(
        val name: String,
        val table: IntIdTable,
        val owner: Column<EntityID<Int>>,
        val fxType: Column<String>,
        val family: Column<String>,
        val parameters: Column<Map<String, String>>,
        val attribute: Column<String?>,
        val owners: JdbcTransaction.() -> Map<Int, Pair<String, Int>>,
    )

    private companion object {
        val FX_HOMES = listOf(
            FxHome(
                "Look", DaoLookFx, DaoLookFx.look, DaoLookFx.fxType, DaoLookFx.family, DaoLookFx.parameters, DaoLookFx.attribute,
            ) { DaoLooks.selectAll().associate { it[DaoLooks.id].value to (it[DaoLooks.name] to it[DaoLooks.project].value) } },
            FxHome(
                "Cue", DaoCueAdHocFx, DaoCueAdHocFx.cue, DaoCueAdHocFx.fxType, DaoCueAdHocFx.family, DaoCueAdHocFx.parameters,
                DaoCueAdHocFx.attribute,
            ) { DaoCues.selectAll().associate { it[DaoCues.id].value to (it[DaoCues.name] to it[DaoCues.project].value) } },
            FxHome(
                "Palette", DaoPaletteFx, DaoPaletteFx.palette, DaoPaletteFx.fxType, DaoPaletteFx.family, DaoPaletteFx.parameters,
                DaoPaletteFx.attribute,
            ) { DaoPalettes.selectAll().associate { it[DaoPalettes.id].value to (it[DaoPalettes.name] to it[DaoPalettes.project].value) } },
        )
    }
}
