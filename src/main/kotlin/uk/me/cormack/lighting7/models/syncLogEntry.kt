package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable

/**
 * Cloud-sync activity log — one row per noteworthy event (run started, run done, snapshot
 * taken, auto-sync skipped, push rejected, …). Persisted so the UI can render a scrolling
 * activity feed even after the WebSocket reconnects.
 *
 * Capped per project at [uk.me.cormack.lighting7.sync.SyncLogger.MAX_ENTRIES_PER_PROJECT];
 * older rows are pruned on every write.
 *
 * Machine-local — never serialised to the cloud repo.
 */
object DaoSyncLogEntries : IntIdTable("sync_log_entry") {
    val project = reference("project_id", DaoProjects)
    val tsMs = long("ts_ms")
    /** One of [uk.me.cormack.lighting7.sync.SyncLogLevel] — persisted as `.name`. */
    val level = varchar("level", 16)
    /** Stable event code (e.g. `RUN_STARTED`, `RUN_DONE`, `PUSH_REJECTED`). UI branches on this. */
    val event = varchar("event", 64)
    val message = text("message")

    init {
        // Composite index over the access pattern: filter by project, order/cursor by id.
        // Used by both `SyncLogger.list` (paginated cursor read) and the prune query.
        index(false, project, id)
    }
}

class DaoSyncLogEntry(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoSyncLogEntry>(DaoSyncLogEntries)

    var project by DaoProject referencedOn DaoSyncLogEntries.project
    var tsMs by DaoSyncLogEntries.tsMs
    var level by DaoSyncLogEntries.level
    var event by DaoSyncLogEntries.event
    var message by DaoSyncLogEntries.message
}
