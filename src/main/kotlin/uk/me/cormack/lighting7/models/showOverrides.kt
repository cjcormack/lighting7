package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass

/**
 * A show's **facet overrides** (venue plan §3.2, D4, D5): one row per facet of a venue patch the show
 * changes for itself — the panto hanging a house mover on its own boom. The patch is named by its
 * uuid ([recordUuid]), a record's identity across the venue's versions, so an override outlives an
 * Update to a version that still holds the patch. `RigScope` lays [value] over the pinned snapshot's
 * patch; the rest of the patch — its identity (name, key, type, head number) and every facet the
 * show does not override — stays the venue's.
 *
 * Each facet is **whole**: [value] is the canonical JSON of every field the facet covers, the primary
 * placement's and each extra placement's (`models/patchFacets.kt`), never a partial edit. [base] is the
 * venue's value of the same facet when the override was made; the pinned version's value differing
 * from it is **drift** — the venue changed something the show had overridden.
 *
 * Only patches take overrides; the show's own rows are edited, not overridden, and a venue rigging,
 * element, region, group or viewpoint can only be hidden (`FU-VENUE-ELEMENT-OVERRIDES`).
 *
 * **Portable** with the show, as `showOverrides/{uuid}.json` (`formatVersion` 24, venue plan session 5,
 * no bump — session 4 reserved the folder): a clone keeps them, remapping the override's own [uuid] and
 * any show-owned universe or rigging its value names; [recordUuid] names a venue record and is kept.
 */
object DaoShowOverrides : IntIdTable("show_overrides") {
    val project = reference("project_id", DaoProjects)
    val recordUuid = javaUUID("record_uuid")
    val facet = enumerationByName<PatchFacet>("facet", 16)
    /** The facet's fields as the show has them, canonical JSON. */
    val value = text("value")
    /** The venue's value of the facet when it was overridden (or last kept), canonical JSON. */
    val base = text("base")
    /** The override's own identity in the show's sync export. */
    val uuid = javaUUID("uuid").autoGenerate()

    init {
        uniqueIndex(project, recordUuid, facet)
    }
}

/**
 * The four parts of a venue patch a show can make its own (venue plan D4). Each is whole; together
 * they are everything about a unit but its identity. `models/patchFacets.kt` says which fields each is.
 */
enum class PatchFacet { ADDRESS, POSITION, FOCUS, MEDIA }

class DaoShowOverride(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoShowOverride>(DaoShowOverrides)

    var project by DaoProject referencedOn DaoShowOverrides.project
    var recordUuid by DaoShowOverrides.recordUuid
    var facet by DaoShowOverrides.facet
    var value by DaoShowOverrides.value
    var base by DaoShowOverrides.base
    var uuid by DaoShowOverrides.uuid
}
