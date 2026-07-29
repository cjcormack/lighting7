package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.java.javaUUID

/**
 * Per-record per-field machine-local override values. Generic by design — any
 * field that's specific to a single install (controller IPs being the
 * canonical example) lives here keyed by `(projectId, tableName, recordUuid,
 * fieldName)` rather than as a column on the synced DAO. `valueJson` holds
 * a canonically-encoded JSON value (use `sync/Overrides.kt` for typed
 * access). Never synced — overrides do not leave the local SQLite DB.
 */
object DaoMachineOverrides : IntIdTable("machine_overrides") {
    val project = reference("project_id", DaoProjects)
    // Named `targetTable` to avoid shadowing Exposed's `Table.tableName` property.
    val targetTable = varchar("table_name", 64)
    val recordUuid = javaUUID("record_uuid")
    val fieldName = varchar("field_name", 64)
    val valueJson = text("value_json")

    init {
        uniqueIndex(project, targetTable, recordUuid, fieldName)
    }
}

class DaoMachineOverride(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoMachineOverride>(DaoMachineOverrides)

    var project by DaoProject referencedOn DaoMachineOverrides.project
    var targetTable by DaoMachineOverrides.targetTable
    var recordUuid by DaoMachineOverrides.recordUuid
    var fieldName by DaoMachineOverrides.fieldName
    var valueJson by DaoMachineOverrides.valueJson
}
