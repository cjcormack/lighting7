package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable

object Scenes : IntIdTable() {
    val name = varchar("name", 255)
    val script = reference("script_id", Scripts)
    val project = reference("project_id", Projects)

    init {
        uniqueIndex(project, name)
    }
}

class Scene(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<Scene>(Scenes)

    var name by Scenes.name
    var script by Script referencedOn Scenes.script
    var project by Project referencedOn Scenes.project
}
