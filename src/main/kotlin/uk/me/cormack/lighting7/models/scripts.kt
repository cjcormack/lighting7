package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import uk.me.cormack.lighting7.scripts.ScriptType

object DaoScripts : IntIdTable("scripts") {
    val name = varchar("name", 255)
    val script = text("script")
    val project = reference("project_id", DaoProjects)
    val scriptType = enumerationByName<ScriptType>("script_type", 50).default(ScriptType.GENERAL)
    val uuid = uuid("uuid").autoGenerate()

    init {
        uniqueIndex(project, name)
    }
}

class DaoScript(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoScript>(DaoScripts)

    var name by DaoScripts.name
    var script by DaoScripts.script
    var project by DaoProject referencedOn DaoScripts.project
    var scriptType by DaoScripts.scriptType
    var uuid by DaoScripts.uuid
}
