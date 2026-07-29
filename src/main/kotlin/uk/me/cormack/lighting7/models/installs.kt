package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.java.javaUUID

/**
 * Singleton machine identity. One row per install — bootstrapped on first
 * startup with `friendlyName = hostname` (see `State.initDatabase`). The
 * `uuid` is the stable identifier exposed in `installs.json` exports and
 * referenced in future cloud-sync attribution metadata. Machine-local;
 * never synced to the cloud repo.
 */
object DaoInstalls : IntIdTable("installs") {
    val uuid = javaUUID("uuid").autoGenerate()
    val friendlyName = varchar("friendly_name", 100)
    val createdAtMs = long("created_at_ms")
}

class DaoInstall(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoInstall>(DaoInstalls)

    var uuid by DaoInstalls.uuid
    var friendlyName by DaoInstalls.friendlyName
    var createdAtMs by DaoInstalls.createdAtMs
}
