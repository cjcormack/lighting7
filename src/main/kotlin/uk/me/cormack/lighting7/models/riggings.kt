package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable

/**
 * Riggings are first-class 3D entities representing trusses, bars, booms, and other
 * mounting structures that fixtures hang from. A fixture patch with a non-null
 * [DaoFixturePatches.rigging] interprets its [DaoFixturePatches.stageX]/Y/Z as offsets
 * in the rigging's local frame; with a null rigging, those fields are absolute world
 * coordinates. See `docs/fixtures-engineering.md` for the v3 Z-up FOH-relative system.
 *
 * [yawDeg] / [pitchDeg] / [rollDeg] orient the rigging's local frame. Yaw is rotation
 * about Z (up), pitch about X (audience-right), roll about Y (upstage). All nullable;
 * absent values are treated as 0.
 */
object DaoRiggings : IntIdTable("riggings") {
    val project = reference("project_id", DaoProjects)
    val name = varchar("name", 100)
    val kind = varchar("kind", 20).nullable()
    val positionX = double("position_x").nullable()
    val positionY = double("position_y").nullable()
    val positionZ = double("position_z").nullable()
    val yawDeg = double("yaw_deg").nullable()
    val pitchDeg = double("pitch_deg").nullable()
    val rollDeg = double("roll_deg").nullable()
    val lengthM = double("length_m").nullable()
    val sortOrder = integer("sort_order").default(0)
    val uuid = uuid("uuid").autoGenerate()

    init {
        uniqueIndex(project, name)
    }
}

class DaoRigging(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoRigging>(DaoRiggings)

    var project by DaoProject referencedOn DaoRiggings.project
    var name by DaoRiggings.name
    var kind by DaoRiggings.kind
    var positionX by DaoRiggings.positionX
    var positionY by DaoRiggings.positionY
    var positionZ by DaoRiggings.positionZ
    var yawDeg by DaoRiggings.yawDeg
    var pitchDeg by DaoRiggings.pitchDeg
    var rollDeg by DaoRiggings.rollDeg
    var lengthM by DaoRiggings.lengthM
    var sortOrder by DaoRiggings.sortOrder
    var uuid by DaoRiggings.uuid

    val fixturePatches by DaoFixturePatch optionalReferrersOn DaoFixturePatches.rigging
}
