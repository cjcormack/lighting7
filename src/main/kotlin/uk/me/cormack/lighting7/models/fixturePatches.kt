package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable

object DaoFixturePatches : IntIdTable("fixture_patches") {
    val project = reference("project_id", DaoProjects)
    val universeConfig = reference("universe_config_id", DaoUniverseConfigs)
    val fixtureTypeKey = varchar("fixture_type_key", 100)
    val key = varchar("key", 100)
    val displayName = varchar("display_name", 255)
    val startChannel = integer("start_channel")
    val sortOrder = integer("sort_order").default(0)

    init {
        uniqueIndex(project, key)
    }
}

class DaoFixturePatch(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoFixturePatch>(DaoFixturePatches)

    var project by DaoProject referencedOn DaoFixturePatches.project
    var universeConfig by DaoUniverseConfig referencedOn DaoFixturePatches.universeConfig
    var fixtureTypeKey by DaoFixturePatches.fixtureTypeKey
    var key by DaoFixturePatches.key
    var displayName by DaoFixturePatches.displayName
    var startChannel by DaoFixturePatches.startChannel
    var sortOrder by DaoFixturePatches.sortOrder
}
