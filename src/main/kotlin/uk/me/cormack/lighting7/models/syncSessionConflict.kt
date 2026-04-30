package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable

/**
 * One conflicting record inside a [DaoSyncSession]. Today `conflictKind` is always
 * `"EDIT_EDIT"`; `EDIT_DELETE` / `DELETE_EDIT` arrive with tombstone support later.
 *
 * `localJson` / `remoteJson` / `baseJson` snapshot the canonical-JSON contents at
 * session-open time so resolution remains stable even if the working tree moves
 * underneath us — the three-pane diff renders straight from these columns.
 *
 * `manualValueJson` carries the user-edited replacement payload when `resolution =
 * MANUAL`; the apply path overlays it verbatim onto the working tree. Single-file
 * records only — multi-file records (scripts) reject MANUAL at the route layer.
 *
 * Machine-local — never serialised.
 */
object DaoSyncSessionConflicts : IntIdTable("sync_session_conflict") {
    val session = reference("session_id", DaoSyncSessions)
    // `targetTable` rather than `tableName` to avoid shadowing `Table.tableName` —
    // same workaround as `syncState.kt` / `machineOverrides.kt`.
    val targetTable = varchar("table_name", 64)
    val recordUuid = uuid("record_uuid")
    /** `EDIT_EDIT` only in Phase 5. */
    val conflictKind = varchar("conflict_kind", 32)
    /** `LOCAL`, `REMOTE`, `MANUAL`, or null (unresolved). */
    val resolution = varchar("resolution", 16).nullable()
    val localJson = text("local_json").nullable()
    val remoteJson = text("remote_json").nullable()
    val baseJson = text("base_json").nullable()
    /** User-edited replacement content. Required when `resolution = MANUAL`; null otherwise. */
    val manualValueJson = text("manual_value_json").nullable()

    init {
        uniqueIndex(session, targetTable, recordUuid)
    }
}

class DaoSyncSessionConflict(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoSyncSessionConflict>(DaoSyncSessionConflicts)

    var session by DaoSyncSession referencedOn DaoSyncSessionConflicts.session
    var tableName by DaoSyncSessionConflicts.targetTable
    var recordUuid by DaoSyncSessionConflicts.recordUuid
    var conflictKind by DaoSyncSessionConflicts.conflictKind
    var resolution by DaoSyncSessionConflicts.resolution
    var localJson by DaoSyncSessionConflicts.localJson
    var remoteJson by DaoSyncSessionConflicts.remoteJson
    var baseJson by DaoSyncSessionConflicts.baseJson
    var manualValueJson by DaoSyncSessionConflicts.manualValueJson
}
