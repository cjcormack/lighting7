package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable

// ─── Enums ──────────────────────────────────────────────────────────────

enum class ShowSessionType { SHOW, SETLIST }
enum class ShowSessionEntryType { STACK, MARKER }

// ─── Show Sessions table ────────────────────────────────────────────────

object DaoShowSessions : IntIdTable("show_sessions") {
    val project = reference("project_id", DaoProjects)
    val name = varchar("name", 255)
    val sessionType = varchar("session_type", 20).default("SHOW")
    // Plain integer to avoid circular FK with DaoShowSessionEntries.
    // The deferrable FK constraint is added via manual SQL in State.kt.
    val activeEntryId = integer("active_entry_id").nullable()
    val createdAt = long("created_at").clientDefault { System.currentTimeMillis() }
    val updatedAt = long("updated_at").clientDefault { System.currentTimeMillis() }
}

class DaoShowSession(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoShowSession>(DaoShowSessions)

    var project by DaoProject referencedOn DaoShowSessions.project
    var name by DaoShowSessions.name
    var sessionType by DaoShowSessions.sessionType
    var activeEntryId by DaoShowSessions.activeEntryId
    var createdAt by DaoShowSessions.createdAt
    var updatedAt by DaoShowSessions.updatedAt
    val entries by DaoShowSessionEntry referrersOn DaoShowSessionEntries.showSession
}

// ─── Show Session Entries table ─────────────────────────────────────────

object DaoShowSessionEntries : IntIdTable("show_session_entries") {
    val showSession = reference("show_session_id", DaoShowSessions)
    val cueStack = reference("cue_stack_id", DaoCueStacks).nullable()
    val entryType = varchar("entry_type", 20).default("STACK")
    val sortOrder = integer("sort_order")
    val label = varchar("label", 255).nullable()
}

class DaoShowSessionEntry(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoShowSessionEntry>(DaoShowSessionEntries)

    var showSession by DaoShowSession referencedOn DaoShowSessionEntries.showSession
    var cueStack by DaoCueStack optionalReferencedOn DaoShowSessionEntries.cueStack
    var entryType by DaoShowSessionEntries.entryType
    var sortOrder by DaoShowSessionEntries.sortOrder
    var label by DaoShowSessionEntries.label
}
