package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.ReferenceOption

// ─── Cue Slots table ──────────────────────────────────────────────────

object DaoCueSlots : IntIdTable("cue_slots") {
    val project = reference("project_id", DaoProjects)
    val page = integer("page")
    val slotIndex = integer("slot_index")
    val cue = optReference("cue_id", DaoCues, onDelete = ReferenceOption.CASCADE)
    val cueStack = optReference("cue_stack_id", DaoCueStacks, onDelete = ReferenceOption.CASCADE)
    val uuid = uuid("uuid").autoGenerate()

    init {
        uniqueIndex(project, page, slotIndex)
    }
}

class DaoCueSlot(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoCueSlot>(DaoCueSlots)

    var project by DaoProject referencedOn DaoCueSlots.project
    var page by DaoCueSlots.page
    var slotIndex by DaoCueSlots.slotIndex
    var cue by DaoCue optionalReferencedOn DaoCueSlots.cue
    var cueStack by DaoCueStack optionalReferencedOn DaoCueSlots.cueStack
    var uuid by DaoCueSlots.uuid
}
