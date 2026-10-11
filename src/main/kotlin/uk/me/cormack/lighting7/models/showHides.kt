package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass

/**
 * A show's **hides** (venue plan §3.2, D5): the venue records a show on a venue leaves out of its
 * rig — a house unit it doesn't use, a bar it has struck. One row per hidden record, named by its
 * kind and its uuid (a uuid is a record's identity across the venue's versions, so a hide outlives
 * an Update to a version that still holds the record). `RigScope` subtracts them from the pinned
 * snapshot's rows. A hide names a **venue** record only: a show's own row is deleted, not hidden.
 *
 * **Portable** with the show, as `showHides/{uuid}.json` (`formatVersion` 24, venue plan session 4):
 * a clone keeps them, remapping only the hide's own [uuid] — [recordUuid] names a venue record.
 */
object DaoShowHides : IntIdTable("show_hides") {
    val project = reference("project_id", DaoProjects)
    val recordKind = enumerationByName<HideKind>("record_kind", 16)
    val recordUuid = javaUUID("record_uuid")
    /** The hide's own identity in the show's sync export. */
    val uuid = javaUUID("uuid").autoGenerate()

    init {
        uniqueIndex(project, recordKind, recordUuid)
    }
}

/** The six kinds of venue record a show can hide (venue plan §3.2). Universes are never hidden. */
enum class HideKind { PATCH, RIGGING, ELEMENT, REGION, GROUP, VIEWPOINT }

class DaoShowHide(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoShowHide>(DaoShowHides)

    var project by DaoProject referencedOn DaoShowHides.project
    var recordKind by DaoShowHides.recordKind
    var recordUuid by DaoShowHides.recordUuid
    var uuid by DaoShowHides.uuid
}
