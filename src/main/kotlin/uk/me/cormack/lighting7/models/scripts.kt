package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import uk.me.cormack.lighting7.models.Project.Companion.referrersOn

object Scripts : IntIdTable() {
    val name = varchar("name", 255)
    val script = text("script")
    val project = reference("project_id", Projects)

    init {
        uniqueIndex(project, name)
    }
}

class Script(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<Script>(Scripts)

    var name by Scripts.name
    var script by Scripts.script
    var project by Project referencedOn Scripts.project

    val scenes by Scene referrersOn Scenes.script
}
