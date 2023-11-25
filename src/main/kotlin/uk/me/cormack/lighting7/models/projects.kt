package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable

object Projects: IntIdTable() {
    val name = varchar("name", 50).uniqueIndex()
    val description = varchar("description", 255).nullable()
}

class Project(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<Project>(Projects)

    var name by Projects.name
    var description by Projects.description

    val scripts by Script referrersOn Scripts.project
    val scenes by Scene referrersOn Scenes.project
}
