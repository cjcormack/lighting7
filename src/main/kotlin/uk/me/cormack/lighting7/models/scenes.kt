package uk.me.cormack.lighting7.models

import kotlinx.serialization.json.Json
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.json.json
import uk.me.cormack.lighting7.scriptSettings.IntValue

enum class Mode
{
    SCENE,
    CHASE,
}

object DaoScenes : IntIdTable("scenes") {
    val name = varchar("name", 255)
    val script = reference("script_id", DaoScripts)
    val project = reference("project_id", DaoProjects)
    // TODO needs to become generic
    val settingsValues = json<Map<String, IntValue>>("settings_values", Json).nullable()
    val mode = enumerationByName("mode", 50, Mode::class).default(Mode.SCENE)

    init {
        uniqueIndex(project, name)
    }
}

class DaoScene(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoScene>(DaoScenes)

    var name by DaoScenes.name
    var script by DaoScript referencedOn DaoScenes.script
    var project by DaoProject referencedOn DaoScenes.project
    var settingsValues by DaoScenes.settingsValues
    var mode by DaoScenes.mode
}
