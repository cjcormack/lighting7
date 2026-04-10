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

    // FK references - plain integer columns to avoid circular dependency issues
    val trackChangedScriptId = integer("track_changed_script_id").nullable()
}

class DaoProject(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoProject>(DaoProjects)

    var name by DaoProjects.name
    var description by DaoProjects.description
    var isCurrent by DaoProjects.isCurrent

    var trackChangedScriptId by DaoProjects.trackChangedScriptId

    val trackChangedScript: DaoScript?
        get() = trackChangedScriptId?.let { DaoScript.findById(it) }

    val scripts by DaoScript referrersOn DaoScripts.project
    val fxPresets by DaoFxPreset referrersOn DaoFxPresets.project
    val cues by DaoCue referrersOn DaoCues.project
    val cueStacks by DaoCueStack referrersOn DaoCueStacks.project
    val cueSlots by DaoCueSlot referrersOn DaoCueSlots.project
    val universeConfigs by DaoUniverseConfig referrersOn DaoUniverseConfigs.project
    val fixturePatches by DaoFixturePatch referrersOn DaoFixturePatches.project
    val fixtureGroups by DaoFixtureGroup referrersOn DaoFixtureGroups.project
    val parkedChannels by DaoParkedChannel referrersOn DaoParkedChannels.project
    val aiConversations by DaoAiConversation referrersOn DaoAiConversations.project
    val fxDefinitions by DaoFxDefinition referrersOn DaoFxDefinitions.project
}
