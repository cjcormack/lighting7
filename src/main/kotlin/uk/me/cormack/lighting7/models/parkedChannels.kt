package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.java.javaUUID

object DaoParkedChannels : IntIdTable("parked_channels") {
    val project = reference("project_id", DaoProjects)
    val universe = integer("universe")
    val channel = integer("channel")
    val value = integer("value")
    val uuid = javaUUID("uuid").autoGenerate()

    init {
        uniqueIndex(project, universe, channel)
    }
}

class DaoParkedChannel(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoParkedChannel>(DaoParkedChannels)

    var project by DaoProject referencedOn DaoParkedChannels.project
    var universe by DaoParkedChannels.universe
    var channel by DaoParkedChannels.channel
    var value by DaoParkedChannels.value
    var uuid by DaoParkedChannels.uuid
}
