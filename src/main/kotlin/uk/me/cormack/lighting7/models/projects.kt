package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable

object DaoProjects: IntIdTable("projects") {
    val name = varchar("name", 50).uniqueIndex()
    val description = varchar("description", 255).nullable()
}

class DaoProject(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoProject>(DaoProjects)

    var name by DaoProjects.name
    var description by DaoProjects.description

    val scripts by DaoScript referrersOn DaoScripts.project
    val scenes by DaoScene referrersOn DaoScenes.project
}
