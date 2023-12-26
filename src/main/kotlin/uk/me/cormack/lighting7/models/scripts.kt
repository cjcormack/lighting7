package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable

object DaoScripts : IntIdTable("scripts") {
    val name = varchar("name", 255)
    val script = text("script")
    val project = reference("project_id", DaoProjects)

    init {
        uniqueIndex(project, name)
    }
}

class DaoScript(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoScript>(DaoScripts)

    var name by DaoScripts.name
    var script by DaoScripts.script
    var project by DaoProject referencedOn DaoScripts.project

    val scenes by DaoScene referrersOn DaoScenes.script
}
