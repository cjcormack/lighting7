package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable

/**
 * Projects table - the base columns are defined here.
 * FK references to scripts/scenes are added after those tables are defined
 * to avoid forward reference issues at compile time.
 */
object DaoProjects: IntIdTable("projects") {
    val name = varchar("name", 50).uniqueIndex()
    val description = varchar("description", 255).nullable()
    val isCurrent = bool("is_current").default(false)
    val runLoopDelayMs = long("run_loop_delay_ms").default(100L)

    // FK references - these are initialized lazily after DaoScripts/DaoScenes are defined
    // The columns will be created via SchemaUtils.createMissingTablesAndColumns()
    val loadFixturesScriptId = integer("load_fixtures_script_id").nullable()
    val trackChangedScriptId = integer("track_changed_script_id").nullable()
    val runLoopScriptId = integer("run_loop_script_id").nullable()
    val initialSceneId = integer("initial_scene_id").nullable()
}

class DaoProject(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoProject>(DaoProjects)

    var name by DaoProjects.name
    var description by DaoProjects.description
    var isCurrent by DaoProjects.isCurrent
    var runLoopDelayMs by DaoProjects.runLoopDelayMs

    // Manual FK handling since we can't use reference() due to circular dependency
    var loadFixturesScriptId by DaoProjects.loadFixturesScriptId
    var trackChangedScriptId by DaoProjects.trackChangedScriptId
    var runLoopScriptId by DaoProjects.runLoopScriptId
    var initialSceneId by DaoProjects.initialSceneId

    // Convenience properties to load related entities
    val loadFixturesScript: DaoScript?
        get() = loadFixturesScriptId?.let { DaoScript.findById(it) }

    val trackChangedScript: DaoScript?
        get() = trackChangedScriptId?.let { DaoScript.findById(it) }

    val runLoopScript: DaoScript?
        get() = runLoopScriptId?.let { DaoScript.findById(it) }

    val initialScene: DaoScene?
        get() = initialSceneId?.let { DaoScene.findById(it) }

    val scripts by DaoScript referrersOn DaoScripts.project
    val scenes by DaoScene referrersOn DaoScenes.project
}
