package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable

object DaoScenes : IntIdTable("scenes") {
    val name = varchar("name", 255)
    val script = reference("script_id", DaoScripts)
    val project = reference("project_id", DaoProjects)

    init {
        uniqueIndex(project, name)
    }
}

class DaoScene(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoScene>(DaoScenes)

    var name by DaoScenes.name
    var script by DaoScript referencedOn DaoScenes.script
    var project by DaoProject referencedOn DaoScenes.project
}
