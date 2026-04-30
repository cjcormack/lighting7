package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable

object DaoParkedChannels : IntIdTable("parked_channels") {
    val project = reference("project_id", DaoProjects)
    val universe = integer("universe")
    val channel = integer("channel")
    val value = integer("value")
    val uuid = uuid("uuid").autoGenerate()

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
