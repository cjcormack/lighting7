package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass

/**
 * A region's job on a control surface (surface-setup plan D11): a [uk.me.cormack.lighting7.midi.JobKind]
 * and its ordered items, stored and live — reordering the items moves the controls. Keyed by
 * `(project, device type, region, layer)`, layer null for both. A control bound by hand beats the
 * job ([uk.me.cormack.lighting7.midi.ControlSurfaceBindingService.resolve]).
 *
 * [kind] and [items] are kept as written, so a kind or an item this build cannot read is kept byte
 * for byte and derives nothing.
 */
object DaoSurfaceJobs : IntIdTable("surface_jobs") {
    val project = reference("project_id", DaoProjects)
    val uuid = javaUUID("uuid").autoGenerate()

    /** `@ControlSurfaceType.typeKey`, as a binding names it. */
    val deviceTypeKey = varchar("device_type_key", 100)

    /** The profile's job region id. */
    val region = varchar("region", 100)

    /** The layer, a profile bank id; null for both. */
    val bank = varchar("bank", 100).nullable()

    /** The job kind's id. */
    val kind = varchar("kind", 50)

    /** The items, a JSON array of `JobItem`s, a gap as null. */
    val items = text("items")

    init {
        uniqueIndex("uq_surface_job_region", project, deviceTypeKey, region, bank)
    }
}

class DaoSurfaceJob(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoSurfaceJob>(DaoSurfaceJobs)

    var project by DaoProject referencedOn DaoSurfaceJobs.project
    var uuid by DaoSurfaceJobs.uuid
    var deviceTypeKey by DaoSurfaceJobs.deviceTypeKey
    var region by DaoSurfaceJobs.region
    var bank by DaoSurfaceJobs.bank
    var kind by DaoSurfaceJobs.kind
    var items by DaoSurfaceJobs.items
}
