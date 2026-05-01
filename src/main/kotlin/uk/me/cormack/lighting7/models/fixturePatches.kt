package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable

/**
 * Stage geometry ([stageX]/[stageY]/[stageZ], [baseYawDeg], [basePitchDeg]) is FOH-relative,
 * right-handed, Y-up, metres — see `docs/fixtures-engineering.md`. For moving heads, `base*`
 * is the yoke orientation, not the live aim.
 */
object DaoFixturePatches : IntIdTable("fixture_patches") {
    val project = reference("project_id", DaoProjects)
    val universeConfig = reference("universe_config_id", DaoUniverseConfigs)
    val fixtureTypeKey = varchar("fixture_type_key", 100)
    val key = varchar("key", 100)
    val displayName = varchar("display_name", 255)
    val startChannel = integer("start_channel")
    val sortOrder = integer("sort_order").default(0)
    val stageX = double("stage_x").nullable()
    val stageY = double("stage_y").nullable()
    val stageZ = double("stage_z").nullable()
    val baseYawDeg = double("base_yaw_deg").nullable()
    val basePitchDeg = double("base_pitch_deg").nullable()
    val riggingPosition = varchar("rigging_position", 50).nullable()
    val beamAngleDeg = integer("beam_angle_deg").nullable()
    val gelCode = varchar("gel_code", 20).nullable()
    val uuid = uuid("uuid").autoGenerate()

    init {
        uniqueIndex(project, key)
    }
}

class DaoFixturePatch(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoFixturePatch>(DaoFixturePatches)

    var project by DaoProject referencedOn DaoFixturePatches.project
    var universeConfig by DaoUniverseConfig referencedOn DaoFixturePatches.universeConfig
    var fixtureTypeKey by DaoFixturePatches.fixtureTypeKey
    var key by DaoFixturePatches.key
    var displayName by DaoFixturePatches.displayName
    var startChannel by DaoFixturePatches.startChannel
    var sortOrder by DaoFixturePatches.sortOrder
    var stageX by DaoFixturePatches.stageX
    var stageY by DaoFixturePatches.stageY
    var stageZ by DaoFixturePatches.stageZ
    var baseYawDeg by DaoFixturePatches.baseYawDeg
    var basePitchDeg by DaoFixturePatches.basePitchDeg
    var riggingPosition by DaoFixturePatches.riggingPosition
    var beamAngleDeg by DaoFixturePatches.beamAngleDeg
    var gelCode by DaoFixturePatches.gelCode
    var uuid by DaoFixturePatches.uuid
}
