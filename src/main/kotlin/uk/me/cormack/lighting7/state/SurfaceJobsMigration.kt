package uk.me.cormack.lighting7.state

import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.midi.SurfaceStripJobs
import uk.me.cormack.lighting7.models.DaoControlSurfaceBinding
import uk.me.cormack.lighting7.models.DaoProject
import java.nio.file.Path
import java.time.Instant
import javax.sql.DataSource

private val logger = LoggerFactory.getLogger("SurfaceJobsMigration")

/** `PRAGMA user_version` once [SurfaceJobsMigration] has run, the step after [AUTO_NUMBER_TIDY_USER_VERSION]. */
internal const val SURFACE_JOBS_USER_VERSION = 17

/**
 * Database step 17: every project's channel strips — binding rows at
 * strip ids — become jobs through [SurfaceStripJobs.convert], the translate the archive importer
 * shares: per device type, layer and region a *Groups* or *Fixtures as channel strips* job, gaps
 * kept, a strip of the other kind its controls' own bindings.
 *
 * Runs after step 16 and after the reconciliation, which has made `surface_jobs`. A database with no
 * strip row is only marked; one with some is backed up first to `<db>.pre-surface-jobs-<utc>.db`,
 * then converted and marked in one transaction. Never twice: below [SURFACE_JOBS_USER_VERSION] is
 * the only way in, and a converted project has no strip row left besides.
 */
internal object SurfaceJobsMigration {
    /** What the step did: the jobs it made, the bindings it gave controls, the rows it dropped, the backup. */
    data class Outcome(val jobs: Int, val bindings: Int, val dropped: Int, val backup: Path?)

    fun runIfNeeded(dataSource: DataSource, database: Database, dbPath: String?, now: Instant = Instant.now()): Outcome? {
        val needed = transaction(database) {
            val version = userVersion()
            if (version >= SURFACE_JOBS_USER_VERSION) return@transaction null
            check(version >= AUTO_NUMBER_TIDY_USER_VERSION) { "Surface jobs: step 16 has not run (user_version $version)" }
            DaoControlSurfaceBinding.all().any { SurfaceStripJobs.isStrip(it.deviceTypeKey, it.controlId) }
        } ?: return null
        if (!needed) {
            transaction(database) { mark() }
            return Outcome(0, 0, 0, backup = null)
        }
        val backup = dbPath?.let { backUpDatabase(dataSource, it, "pre-surface-jobs", now) }
        val plans = transaction(database) {
            val done = DaoProject.all().sortedBy { it.id.value }.map { SurfaceStripJobs.convert(it) }
            mark()
            done
        }
        val outcome = Outcome(
            jobs = plans.sumOf { it.jobs.size },
            bindings = plans.sumOf { it.bindings.size },
            dropped = plans.sumOf { it.unreadable.size },
            backup = backup,
        )
        logger.info(
            "Surface jobs: {} job(s) made, {} binding(s) given their own, {} strip row(s) dropped unread; backup {}",
            outcome.jobs, outcome.bindings, outcome.dropped, backup ?: "skipped",
        )
        return outcome
    }

    private fun JdbcTransaction.mark() {
        exec("PRAGMA user_version = $SURFACE_JOBS_USER_VERSION", explicitStatementType = StatementType.OTHER)
    }
}
