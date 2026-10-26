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

private val logger = LoggerFactory.getLogger("FxConsolidationMigration")

/** `PRAGMA user_version` once [FxConsolidationMigration] has run, the step after [ATTRIBUTE_NAMES_USER_VERSION]. */
internal const val FX_CONSOLIDATED_USER_VERSION = 7

/**
 * Database step 7 (fx-settings plan session 2): every stored Look, cue and palette FX on one of the
 * level waves Wave replaced — Sine Wave, the ramps, Square Wave, Pulse, Dimmer Strobe and the four
 * beam waves — rewritten onto Wave by [LegacyNames.consolidateFx], which the importer shares, and
 * each named in the log by its owner. A beam FX that named no attribute is given the one it drove.
 * What each head does is unchanged — `LegacyEquivalenceTest` runs every old script beside Wave — but
 * for one case the log names: such an FX on a group where not every head has its attribute now runs
 * on the heads that do, where before it ran on none of that group (the plan's recorded exception).
 *
 * Runs after step 6. A database holding none of the old ids (a fresh one among them) is only
 * marked; one that holds some is backed up first to `<db>.pre-fx-consolidation-<utc>.db`, then
 * rewritten and marked in one transaction. Never twice: below [FX_CONSOLIDATED_USER_VERSION] is the
 * only way in, and the rewrite reads no Wave besides.
 *
 * A script naming an old id is left alone: `effect("SineWave", …)` still runs, as Wave. One that
 * names one any other way — `fxRegistry.getRegistration("Pulse")`, the way an FX definition builds on
 * a built-in — finds nothing now, so each stored script and FX definition that does is named in a
 * warning, never rewritten (as step 6 names an old centre). So is a project FX definition with the id
 * `Wave`, which would take the place of the FX its level waves now run as; none can be made today.
 */
internal object FxConsolidationMigration {

    /** What one run did, for the log line and the tests. */
    data class Outcome(val backup: Path?, val fx: Map<String, Int>)

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

    private val FX_HOMES = listOf(
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

    /** One FX the step rewrites: its row, what it becomes, and the words the log names it with. */
    private class Pending(val id: Int, val fx: LegacyNames.StoredFx, val said: String, val moves: Boolean)

    /**
     * Run the step if the database is below [FX_CONSOLIDATED_USER_VERSION]. [dbPath] is the database
     * file, the backup's directory and stem; null skips the backup (an in-memory database has no file
     * to copy). Answers null when the database was already marked.
     */
    fun runIfNeeded(dataSource: DataSource, database: Database, dbPath: String?, now: Instant = Instant.now()): Outcome? {
        val (pending, stale) = transaction(database) {
            val version = userVersion()
            if (version >= FX_CONSOLIDATED_USER_VERSION) return@transaction null
            check(version >= ATTRIBUTE_NAMES_USER_VERSION) { "FX consolidation: step 6 has not run (user_version $version)" }
            FX_HOMES.any { pending(it).isNotEmpty() } to staleScripts()
        } ?: return null
        for (warning in stale) logger.warn("FX consolidation: {}", warning)
        if (!pending) {
            transaction(database) { mark() }
            return Outcome(backup = null, fx = emptyMap())
        }
        val backup = dbPath?.let { backUpDatabase(dataSource, it, "pre-fx-consolidation", now) }
        val outcome = transaction(database) {
            val fx = FX_HOMES.associate { home ->
                val rewrites = pending(home)
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
        logger.info(
            "FX consolidation: {} onto Wave; backup {}",
            outcome.fx.entries.joinToString { "${it.value} ${it.key} FX" }, backup ?: "skipped",
        )
        return outcome
    }

    /** Every FX of [home] on an old level wave, rewritten. */
    private fun JdbcTransaction.pending(home: FxHome): List<Pending> {
        val owners = home.owners(this)
        val projects = DaoProjects.selectAll().associate { it[DaoProjects.id].value to it[DaoProjects.name] }
        val definitions = DaoFxDefinitions.selectAll()
            .map { it[DaoFxDefinitions.project].value to normalizeFxType(it[DaoFxDefinitions.fxId]) }.toSet()
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
            val rewrite = LegacyNames.consolidateFx(stored) { type ->
                if (projectId != null && (projectId to normalizeFxType(type)) in definitions) LegacyNames.Definition("SLIDER", emptySet()) else null
            } ?: return@mapNotNull null
            val project = projectId?.let { projects[it] } ?: "no project"
            val notes = if (rewrite.notes.isEmpty()) "" else "; output moves: ${rewrite.notes.joinToString("; ")}"
            Pending(row[home.table.id].value, rewrite.fx, "${home.name} '$name' in '$project': ${stored.fxType} — ${rewrite.changes.joinToString("; ")}$notes", rewrite.notes.isNotEmpty())
        }
    }

    /** A level wave's id named in quotes, other than as the id `effect(…)` takes, which still runs it. */
    private val OLD_ID_OUTSIDE_EFFECT = Regex(
        "(?<!effect\\(\\s{0,8})\"(" + LegacyNames.waveIds.joinToString("|") { Regex.escape(it) } + ")\"",
    )

    /** Every stored script and FX definition that would find nothing for an old id now, and every `Wave` definition, in words. */
    private fun JdbcTransaction.staleScripts(): List<String> {
        val projects = DaoProjects.selectAll().associate { it[DaoProjects.id].value to it[DaoProjects.name] }
        fun names(text: String) = OLD_ID_OUTSIDE_EFFECT.findAll(text).map { it.groupValues[1] }.toSortedSet()
        val scripts = DaoScripts.selectAll().mapNotNull { row ->
            names(row[DaoScripts.script]).takeIf { it.isNotEmpty() }?.let {
                "script '${row[DaoScripts.name]}' in '${projects[row[DaoScripts.project].value]}' names ${it.joinToString()}, " +
                    "which are Wave now (effect() still runs them; a registry lookup finds nothing) — edit it"
            }
        }
        val definitions = DaoFxDefinitions.selectAll().flatMap { row ->
            val at = "FX definition '${row[DaoFxDefinitions.fxId]}' in '${projects[row[DaoFxDefinitions.project].value]}'"
            listOfNotNull(
                names(row[DaoFxDefinitions.script]).takeIf { it.isNotEmpty() }?.let {
                    "$at names ${it.joinToString()}, which are Wave now (a registry lookup finds nothing) — edit it"
                },
                at.takeIf { normalizeFxType(row[DaoFxDefinitions.fxId]) == normalizeFxType(LegacyNames.WAVE) }
                    ?.let { "$it takes the built-in Wave's id, which its project's level waves now run as — rename it" },
            )
        }
        return scripts + definitions
    }

    private fun JdbcTransaction.mark() {
        exec("PRAGMA user_version = $FX_CONSOLIDATED_USER_VERSION", explicitStatementType = StatementType.OTHER)
    }
}
