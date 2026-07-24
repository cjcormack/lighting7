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
    // The project's currently-active cue stack (the show playhead), or null when the show is not
    // running. Plain integer, no enforced FK — a deleted stack is reconciled by nulling this in the
    // stack-delete path. (The legacy `active_entry_id` column referenced the dropped show_entries
    // table; StateMigrations.migrateCollapseShowIntoStacks copies its value here.)
    val activeStackId = integer("active_stack_id").nullable()
    // Per-project stage bounds (metres) for the FOH 3D view; synced with the project
    // because shows are designed for a specific venue. See docs/fixtures-engineering.md.
    val stageWidthM = double("stage_width_m").nullable()
    val stageDepthM = double("stage_depth_m").nullable()
    val stageHeightM = double("stage_height_m").nullable()
    val uuid = uuid("uuid").autoGenerate()
}

class DaoProject(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoProject>(DaoProjects)

    var name by DaoProjects.name
    var description by DaoProjects.description
    var isCurrent by DaoProjects.isCurrent
    var activeStackId by DaoProjects.activeStackId
    var stageWidthM by DaoProjects.stageWidthM
    var stageDepthM by DaoProjects.stageDepthM
    var stageHeightM by DaoProjects.stageHeightM
    var uuid by DaoProjects.uuid

    val scripts by DaoScript referrersOn DaoScripts.project
    val fxPresets by DaoFxPreset referrersOn DaoFxPresets.project
    val cues by DaoCue referrersOn DaoCues.project
    val cueStacks by DaoCueStack referrersOn DaoCueStacks.project
    val cueSlots by DaoCueSlot referrersOn DaoCueSlots.project
    val universeConfigs by DaoUniverseConfig referrersOn DaoUniverseConfigs.project
    val riggings by DaoRigging referrersOn DaoRiggings.project
    val stageRegions by DaoStageRegion referrersOn DaoStageRegions.project
    val fixturePatches by DaoFixturePatch referrersOn DaoFixturePatches.project
    val fixtureGroups by DaoFixtureGroup referrersOn DaoFixtureGroups.project
    val parkedChannels by DaoParkedChannel referrersOn DaoParkedChannels.project
    val aiConversations by DaoAiConversation referrersOn DaoAiConversations.project
    val fxDefinitions by DaoFxDefinition referrersOn DaoFxDefinitions.project
    val controlSurfaceBindings by DaoControlSurfaceBinding referrersOn DaoControlSurfaceBindings.project
    /** The project's single prompt book, or null if none has been imported yet. */
    val promptBook: DaoPromptBook?
        get() = DaoPromptBook.find { DaoPromptBooks.project eq id }.firstOrNull()
}
