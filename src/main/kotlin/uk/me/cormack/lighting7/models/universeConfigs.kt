package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable

object DaoUniverseConfigs : IntIdTable("universe_configs") {
    val project = reference("project_id", DaoProjects)
    val subnet = integer("subnet").default(0)
    val universe = integer("universe")
    val controllerType = varchar("controller_type", 20).default("ARTNET")
    val address = varchar("address", 255).nullable()

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

    val fixturePatches by DaoFixturePatch referrersOn DaoFixturePatches.universeConfig
}
