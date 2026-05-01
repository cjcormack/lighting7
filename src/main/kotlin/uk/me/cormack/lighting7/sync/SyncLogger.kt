package uk.me.cormack.lighting7.sync

import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoSyncLogEntries
import uk.me.cormack.lighting7.models.DaoSyncLogEntry
import uk.me.cormack.lighting7.plugins.CloudSyncLogAppendedOutMessage
import uk.me.cormack.lighting7.state.State

/**
 * Persisted activity log for cloud sync. Each call writes one [DaoSyncLogEntry], prunes the
 * project's oldest rows back down to [MAX_ENTRIES_PER_PROJECT], and broadcasts a
 * `cloudSyncLogAppended` WS message so the UI's activity feed updates live.
 *
 * Synchronous SQLite writes; callers are already off the request thread (route handlers
 * use `withContext(Dispatchers.IO)`, the engine runs inside its own IO block, the
 * auto-sync scheduler dispatches on `Dispatchers.IO`).
 */
class SyncLogger(private val state: State) {

    fun info(projectId: Int, event: String, message: String) =
        write(projectId, SyncLogLevel.INFO, event, message)

    fun warn(projectId: Int, event: String, message: String) =
        write(projectId, SyncLogLevel.WARN, event, message)

    fun error(projectId: Int, event: String, message: String) =
        write(projectId, SyncLogLevel.ERROR, event, message)

    /**
     * Page the activity log from newest to oldest. [beforeId] is the cursor for "older
     * than" — pass the smallest id from the previous page. Cap [limit] at
     * [MAX_LIST_LIMIT] to keep responses bounded.
     */
    fun list(projectId: Int, limit: Int = DEFAULT_LIST_LIMIT, beforeId: Int? = null): List<SyncLogEntryDto> {
        val capped = limit.coerceIn(1, MAX_LIST_LIMIT)
        return transaction(state.database) {
            DaoSyncLogEntry.find {
                with(SqlExpressionBuilder) {
                    if (beforeId != null) {
                        (DaoSyncLogEntries.project eq projectId) and (DaoSyncLogEntries.id less beforeId)
                    } else {
                        DaoSyncLogEntries.project eq projectId
                    }
                }
            }
                .orderBy(DaoSyncLogEntries.id to SortOrder.DESC)
                .limit(capped)
                .map { it.toDto() }
        }
    }

    private fun write(projectId: Int, level: SyncLogLevel, event: String, message: String) {
        val ts = System.currentTimeMillis()
        val dto = transaction(state.database) {
            val project = DaoProject.findById(projectId) ?: return@transaction null
            val row = DaoSyncLogEntry.new {
                this.project = project
                this.tsMs = ts
                this.level = level.name
                this.event = event
                this.message = message
            }
            // Prune to MAX_ENTRIES_PER_PROJECT: peek at the row exactly one past the cap
            // (newest-first), and if it exists everything older than its id is dropped in
            // a single DELETE. No-op when the project's row count is at or below the cap.
            val cutoff = DaoSyncLogEntry.find { DaoSyncLogEntries.project eq projectId }
                .orderBy(DaoSyncLogEntries.id to SortOrder.DESC)
                .limit(1)
                .offset(MAX_ENTRIES_PER_PROJECT.toLong())
                .firstOrNull()
            if (cutoff != null) {
                val cutoffId: Int = cutoff.id.value
                DaoSyncLogEntries.deleteWhere {
                    with(SqlExpressionBuilder) {
                        (DaoSyncLogEntries.project eq projectId) and (DaoSyncLogEntries.id lessEq cutoffId)
                    }
                }
            }
            row.toDto()
        } ?: return

        try {
            state.emitCloudSyncEvent(
                CloudSyncLogAppendedOutMessage(
                    projectId = projectId,
                    entry = dto,
                ),
            )
        } catch (t: Throwable) {
            // Logger failures must not break the sync pipeline.
            logger.warn("Failed to broadcast cloudSyncLogAppended: {}", t.message)
        }
    }

    private fun DaoSyncLogEntry.toDto(): SyncLogEntryDto = SyncLogEntryDto(
        id = id.value,
        tsMs = tsMs,
        level = level,
        event = event,
        message = message,
    )

    companion object {
        const val MAX_ENTRIES_PER_PROJECT = 500
        const val DEFAULT_LIST_LIMIT = 100
        const val MAX_LIST_LIMIT = 500

        private val logger = LoggerFactory.getLogger(SyncLogger::class.java)
    }
}

/** Stable severity for [DaoSyncLogEntry.level]. Persisted as `.name`. */
enum class SyncLogLevel { INFO, WARN, ERROR }

/**
 * Stable event codes for [SyncLogger]. The UI branches on these; treat them like
 * [SyncErrorCode] for stability.
 */
object SyncLogEvent {
    const val RUN_STARTED = "RUN_STARTED"
    const val RUN_DONE = "RUN_DONE"
    const val RUN_FAILED = "RUN_FAILED"
    const val SNAPSHOT_TAKEN = "SNAPSHOT_TAKEN"
    const val SNAPSHOT_NOOP = "SNAPSHOT_NOOP"
    const val CONFLICTS_PENDING = "CONFLICTS_PENDING"
    const val APPLY_DONE = "APPLY_DONE"
    const val APPLY_FAILED = "APPLY_FAILED"
    const val SESSION_ABORTED = "SESSION_ABORTED"
    const val AUTO_SYNC_TICK = "AUTO_SYNC_TICK"
    const val AUTO_SYNC_SKIPPED = "AUTO_SYNC_SKIPPED"
}

/** Wire shape for a single [DaoSyncLogEntry] row. */
@Serializable
data class SyncLogEntryDto(
    val id: Int,
    val tsMs: Long,
    val level: String,
    val event: String,
    val message: String,
)

