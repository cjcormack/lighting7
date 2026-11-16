package uk.me.cormack.lighting7.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.me.cormack.lighting7.models.DaoSyncConfigs
import uk.me.cormack.lighting7.models.DaoSyncLogEntries
import uk.me.cormack.lighting7.models.nowUtc
import java.time.Instant

/**
 * Sync's outcome across every project, in memory, for the heartbeat (cloud-p3 plan D2): the last run,
 * the last good run, and the projects failing now.
 *
 * One rule, live and seeded: each activity-log row a run writes moves it ([logged], called by
 * [SyncLogger] as it writes one, and replayed by [seed] at startup), and so does a project's
 * `last_synced_at`, which every good run writes, quiet auto runs that log nothing included ([ran]
 * live, the column when seeding). A run stopped on conflicts counts as failing (`CONFLICTS_PENDING`)
 * until it is applied or aborted; a refusal that says the project is not set up to sync
 * ([NOT_A_RUN]) is no run at all; a disconnected or deleted project's failure no longer counts.
 */
class SyncHealth(private val clock: () -> Instant = ::nowUtc) {
    data class Failure(val since: Instant, val code: String)

    data class Summary(
        val lastRunAt: Instant? = null,
        val lastOkAt: Instant? = null,
        /** The oldest failure among the projects failing now. */
        val failing: Failure? = null,
    )

    private val lock = Any()
    private var lastRunAt: Instant? = null
    private var lastOkAt: Instant? = null
    private val failures = HashMap<Int, Failure>()
    private val deleted = HashSet<Int>()

    private val _state = MutableStateFlow(Summary())
    val state: StateFlow<Summary> = _state.asStateFlow()

    /** A good run of [projectId] that wrote no log row (a quiet auto run). */
    fun ran(projectId: Int) {
        synchronized(lock) {
            if (projectId in deleted) return
            ok(projectId, clock())
            publish()
        }
    }

    /** [projectId]'s activity log gained a row. */
    fun logged(projectId: Int, event: String, message: String, at: Instant) {
        synchronized(lock) {
            if (projectId in deleted) return
            if (apply(projectId, event, message, at)) publish()
        }
    }

    /** [projectId] was deleted: its failure goes, and nothing a run still in flight reports brings it back. */
    fun deleted(projectId: Int) {
        synchronized(lock) {
            deleted += projectId
            if (failures.remove(projectId) != null) publish()
        }
    }

    /** Read once at startup: the log's run rows and each project's `last_synced_at`, in time order. */
    fun seed(database: Database) {
        data class Row(val projectId: Int, val at: Instant, val event: String, val message: String)
        val (rows, attached) = transaction(database) {
            val logged = DaoSyncLogEntries.selectAll()
                .where { DaoSyncLogEntries.event inList RUN_EVENTS }
                .orderBy(DaoSyncLogEntries.id to SortOrder.ASC)
                .map { Row(it[DaoSyncLogEntries.project].value, it[DaoSyncLogEntries.ts], it[DaoSyncLogEntries.event], it[DaoSyncLogEntries.message]) }
            val configs = DaoSyncConfigs.selectAll().toList()
            val synced = configs.mapNotNull { row ->
                row[DaoSyncConfigs.lastSyncedAt]?.let { Row(row[DaoSyncConfigs.project].value, it, SYNCED, "") }
            }
            // Stable: a run's `last_synced_at` is written before the row that logs it.
            (synced + logged).sortedBy { it.at } to
                configs.filter { !it[DaoSyncConfigs.repoUrl].isNullOrBlank() }.map { it[DaoSyncConfigs.project].value }.toSet()
        }
        synchronized(lock) {
            rows.forEach { apply(it.projectId, it.event, it.message, it.at) }
            failures.keys.retainAll(attached)
            publish()
        }
    }

    /** One row's effect; true when it changed anything. */
    private fun apply(projectId: Int, event: String, message: String, at: Instant): Boolean {
        when (event) {
            in OK_EVENTS, SYNCED -> ok(projectId, at)
            SyncLogEvent.RUN_FAILED -> {
                val code = codeOf(message)
                if (code in NOT_A_RUN) return false
                fail(projectId, code, at)
            }
            SyncLogEvent.CONFLICTS_PENDING -> fail(projectId, SyncOutcome.CONFLICTS_PENDING.name, at)
            SyncLogEvent.SESSION_ABORTED, SyncLogEvent.REPO_DISCONNECTED -> return failures.remove(projectId) != null
            else -> return false
        }
        return true
    }

    private fun ok(projectId: Int, at: Instant) {
        lastRunAt = maxOf(lastRunAt ?: at, at)
        lastOkAt = maxOf(lastOkAt ?: at, at)
        failures.remove(projectId)
    }

    /** A project already failing keeps its first failure's time. */
    private fun fail(projectId: Int, code: String, at: Instant) {
        lastRunAt = maxOf(lastRunAt ?: at, at)
        failures[projectId] = failures[projectId]?.copy(code = code) ?: Failure(at, code)
    }

    private fun publish() {
        _state.value = Summary(lastRunAt, lastOkAt, failures.values.minByOrNull { it.since })
    }

    companion object {
        /** Log events that mean a run completed. */
        private val OK_EVENTS = listOf(SyncLogEvent.RUN_DONE, SyncLogEvent.APPLY_DONE, SyncLogEvent.AUTO_SYNC_RECOVERED)

        /** Every log event [apply] reads. */
        private val RUN_EVENTS = OK_EVENTS + listOf(
            SyncLogEvent.RUN_FAILED, SyncLogEvent.CONFLICTS_PENDING, SyncLogEvent.SESSION_ABORTED, SyncLogEvent.REPO_DISCONNECTED,
        )

        /** A project's `last_synced_at`, as a row of the seed's timeline. */
        private const val SYNCED = "last_synced_at"

        /**
         * Refusals that say the project is not set up to sync, or that a session already waits on the
         * operator (which is failing as `CONFLICTS_PENDING` already): none is a failing sync.
         */
        internal val NOT_A_RUN = setOf(
            SyncErrorCode.REPO_URL_MISSING, SyncErrorCode.SYNC_DISABLED, SyncErrorCode.SYNC_OFF, SyncErrorCode.NO_REPO,
            SyncErrorCode.VENUE_DRAFT_NOT_SYNCED, SyncErrorCode.CLOUD_NOT_ADOPTED, SyncErrorCode.SESSION_PENDING,
        ).map { it.name }.toSet()

        /** The code a failure row starts with (`AUTH_FAILED: …`), else a generic one. */
        internal fun codeOf(message: String): String =
            message.substringBefore(':').trim().takeIf { it.isNotEmpty() && it.all { c -> c.isUpperCase() || c == '_' || c.isDigit() } }
                ?: UNKNOWN_CODE

        const val UNKNOWN_CODE = "SYNC_FAILED"
    }
}
