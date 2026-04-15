package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable

// ─── Enums ──────────────────────────────────────────────────────────────

enum class ShowEntryType { STACK, MARKER }

// ─── Show Entries table ─────────────────────────────────────────────────

object DaoShowEntries : IntIdTable("show_entries") {
    val project = reference("project_id", DaoProjects)
    val cueStack = reference("cue_stack_id", DaoCueStacks).nullable()
    val entryType = varchar("entry_type", 20).default("STACK")
    val sortOrder = integer("sort_order")
    val label = varchar("label", 255).nullable()
}

class DaoShowEntry(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoShowEntry>(DaoShowEntries)

    var project by DaoProject referencedOn DaoShowEntries.project
    var cueStack by DaoCueStack optionalReferencedOn DaoShowEntries.cueStack
    var entryType by DaoShowEntries.entryType
    var sortOrder by DaoShowEntries.sortOrder
    var label by DaoShowEntries.label
}
