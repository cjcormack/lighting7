package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.java.javaUUID

object DaoUniverseConfigs : IntIdTable("universe_configs") {
    val project = reference("project_id", DaoProjects)
    val subnet = integer("subnet").default(0)
    val universe = integer("universe")
    val controllerType = varchar("controller_type", 20).default("ARTNET")
    val address = varchar("address", 255).nullable()
    val uuid = javaUUID("uuid").autoGenerate()

    init {
        uniqueIndex(project, subnet, universe)
    }
}

class DaoUniverseConfig(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoUniverseConfig>(DaoUniverseConfigs)

    var project by DaoProject referencedOn DaoUniverseConfigs.project
    var subnet by DaoUniverseConfigs.subnet
    var universe by DaoUniverseConfigs.universe
    var controllerType by DaoUniverseConfigs.controllerType
    var address by DaoUniverseConfigs.address
    var uuid by DaoUniverseConfigs.uuid

    val fixturePatches by DaoFixturePatch referrersOn DaoFixturePatches.universeConfig
}
