package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.java.javaUUID

// ─── Cue Slots table ──────────────────────────────────────────────────

/**
 * One tile of the FX cue slots overlay — the paged grid of eight above the Show view.
 *
 * A slot holds **exactly one** of [cue] / [look]. The overlay has no selection, so it can hold only
 * what needs none: a cue, or a Look with no deferred effect — its rows are always bound, so a Look
 * slot presses the Look onto its own fixtures (`docs/plans/busk-layout-plan.md` D7). A cue *stack*
 * used to be a third arm; it went with the overlay's own assign flow, and nothing else ever wanted
 * a stack on a tile.
 *
 * The rule is enforced by the routes and the importer, not the schema: the `CASCADE` option below
 * is not honoured by SQLite without a per-connection pragma, so a cue's delete sweeps its slots
 * itself, and `look_id` carries no option at all.
 */
object DaoCueSlots : IntIdTable("cue_slots") {
    val project = reference("project_id", DaoProjects)
    val page = integer("page")
    val slotIndex = integer("slot_index")
    val cue = optReference("cue_id", DaoCues, onDelete = ReferenceOption.CASCADE)
    val look = optReference("look_id", DaoLooks)
    val uuid = javaUUID("uuid").autoGenerate()

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
    var look by DaoLook optionalReferencedOn DaoCueSlots.look
    var uuid by DaoCueSlots.uuid
}
