package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable

/**
 * Singleton machine identity. One row per install — bootstrapped on first
 * startup with `friendlyName = hostname` (see `State.initDatabase`). The
 * `uuid` is the stable identifier exposed in `installs.json` exports and
 * referenced in future cloud-sync attribution metadata. Machine-local;
 * never synced to the cloud repo.
 */
object DaoInstalls : IntIdTable("installs") {
    val uuid = uuid("uuid").autoGenerate()
    val friendlyName = varchar("friendly_name", 100)
    val createdAtMs = long("created_at_ms")
}

class DaoInstall(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoInstall>(DaoInstalls)

    var uuid by DaoInstalls.uuid
    var friendlyName by DaoInstalls.friendlyName
    var createdAtMs by DaoInstalls.createdAtMs
}
