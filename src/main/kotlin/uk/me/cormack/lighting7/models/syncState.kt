package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable

/**
 * Per-record cloud-sync watermark. One row per `(project, tableName, recordUuid)` —
 * `lastSyncedSha` is the commit at which the record's canonical-JSON content matched
 * `lastSyncedHash`. The hash is what makes the three-way diff in
 * [uk.me.cormack.lighting7.sync.ThreeWayDiff] able to tell "I changed this since last
 * sync" from "I happen to look like remote".
 *
 * `lastSyncedIsDeleted` distinguishes "the last-synced state was a tombstone" from "the
 * last-synced state was a live record" — required for the tombstone-aware diff so a row
 * that's tombstoned on both sides reads as `NoOp` rather than ambiguous-deletion.
 *
 * Machine-local — never serialised to the cloud repo.
 */
object DaoSyncStates : IntIdTable("sync_state") {
    val project = reference("project_id", DaoProjects)
    // Named `targetTable` to avoid shadowing Exposed's `Table.tableName` property — same
    // workaround as `machineOverrides.kt`. The DB column is still `table_name`.
    val targetTable = varchar("table_name", 64)
    val recordUuid = uuid("record_uuid")
    val lastSyncedSha = varchar("last_synced_sha", 64)
    val lastSyncedHash = varchar("last_synced_hash", 64)
    val lastSyncedIsDeleted = bool("last_synced_is_deleted").default(false)

    init {
        uniqueIndex(project, targetTable, recordUuid)
    }
}

class DaoSyncState(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoSyncState>(DaoSyncStates)

    var project by DaoProject referencedOn DaoSyncStates.project
    var tableName by DaoSyncStates.targetTable
    var recordUuid by DaoSyncStates.recordUuid
    var lastSyncedSha by DaoSyncStates.lastSyncedSha
    var lastSyncedHash by DaoSyncStates.lastSyncedHash
    var lastSyncedIsDeleted by DaoSyncStates.lastSyncedIsDeleted
}
