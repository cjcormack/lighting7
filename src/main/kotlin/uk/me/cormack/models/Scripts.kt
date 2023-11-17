package uk.me.cormack.models

import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable

object Scripts : IntIdTable() {
    val name = varchar("name", 255)
    val script = text("script")
    val project = reference("project_id", Projects)
}

object Projects: IntIdTable() {
    val name = varchar("name", 50).uniqueIndex()
    val description = varchar("description", 255).nullable()
}

class Script(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<Script>(Scripts)

    var name by Scripts.name
    var script by Scripts.script
    var project by Project referencedOn Scripts.project
}

class Project(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<Project>(Projects)

    var name by Projects.name
    var description by Projects.description
    val scripts by Script referrersOn Scripts.project
}
