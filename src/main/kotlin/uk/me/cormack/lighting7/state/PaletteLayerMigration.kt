package uk.me.cormack.lighting7.state

import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.state.PaletteLayerConversion.apply
import uk.me.cormack.lighting7.state.PaletteLayerConversion.plan
import java.nio.file.Path
import java.time.Instant
import javax.sql.DataSource

private val logger = LoggerFactory.getLogger("PaletteLayerMigration")

/** `PRAGMA user_version` once [PaletteLayerMigration] has run, the step after [MOVE_IN_DARK_ROW_FLAG_USER_VERSION]. */
internal const val PALETTE_LAYER_USER_VERSION = 10

/**
 * Database step 10 (palette-references plan D17): every plain cue palette layer becomes palette
 * references on the cue's own rows, by [PaletteLayerConversion], which the importer shares for a v46
 * archive. Every other palette layer stays a layer and keeps working, named in the log with why.
 *
 * Runs after step 9. A database whose palette layers all stay (a fresh one among them) is only
 * marked; one that converts some is backed up first to `<db>.pre-palette-references-<utc>.db`, then
 * converted and marked in one transaction, each cue named in the log. Never twice: below
 * [PALETTE_LAYER_USER_VERSION] is the only way in.
 */
internal object PaletteLayerMigration {
    /** What the step did: the backup taken, if any, the cues converted and the layers kept, by name. */
    data class Outcome(val backup: Path?, val cues: List<String>, val kept: List<String>)

    /**
     * Run the step if the database is below [PALETTE_LAYER_USER_VERSION]. [dbPath] is the database
     * file, the backup's directory and stem; null skips the backup. Answers null when the database was
     * already marked.
     */
    fun runIfNeeded(dataSource: DataSource, database: Database, dbPath: String?, now: Instant = Instant.now()): Outcome? {
        val plans = transaction(database) {
            val version = userVersion()
            if (version >= PALETTE_LAYER_USER_VERSION) return@transaction null
            check(version >= MOVE_IN_DARK_ROW_FLAG_USER_VERSION) { "Palette layers: step 9 has not run (user_version $version)" }
            plan()
        } ?: return null
        val kept = logKept(plans, logger)
        if (plans.none { it.decision.changes }) {
            transaction(database) { mark() }
            return Outcome(backup = null, cues = emptyList(), kept = kept)
        }
        val backup = dbPath?.let { backUpDatabase(dataSource, it, "pre-palette-references", now) }
        val cues = transaction(database) {
            val fresh = plan()
            apply(fresh)
            mark()
            fresh.filter { it.decision.changes }
        }
        logConverted(cues, logger)
        logger.info("Palette layers: {} cue(s) now reference their palettes; backup {}", cues.size, backup ?: "skipped")
        return Outcome(backup, cues.map { it.cueName }, kept)
    }

    private fun JdbcTransaction.mark() {
        exec("PRAGMA user_version = $PALETTE_LAYER_USER_VERSION", explicitStatementType = StatementType.OTHER)
    }
}

/** Log every palette layer [plans] keep, answering the lines. */
internal fun logKept(plans: List<PaletteLayerConversion.CuePlan>, logger: Logger): List<String> =
    plans.flatMap { plan ->
        plan.decision.kept.map { kept ->
            "cue '${plan.cueName}' in '${plan.projectName}' keeps palette '${kept.palette}' as a layer: ${kept.reason}"
                .also { logger.info("Palette layers: {}", it) }
        }
    }

/** Log every cue [plans] converted. */
internal fun logConverted(plans: List<PaletteLayerConversion.CuePlan>, logger: Logger) {
    for (plan in plans) {
        logger.info(
            "Palette layers: cue '{}' in '{}': {} layer(s) became {} reference row(s)",
            plan.cueName, plan.projectName, plan.decision.converted.size, plan.decision.rows.size,
        )
    }
}
