package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable

/**
 * A rectangular platform that makes up part of the playable stage surface. Multiple
 * regions describe thrusts, raised platforms, pits, and multi-level stages. The
 * project-level [DaoProjects.stageWidthM] / depth / height stays as a coarse bounding
 * box for renderers that don't read regions. See `docs/fixtures-engineering.md` for
 * the v3 Z-up FOH-relative coordinate system.
 *
 * [centerZ] is the Z (height) of the platform's top surface — 0 = deck level, > 0 raised.
 * [yawDeg] rotates the rectangle about Z relative to its centre.
 */
object DaoStageRegions : IntIdTable("stage_regions") {
    val project = reference("project_id", DaoProjects)
    val name = varchar("name", 100)
    val centerX = double("center_x").nullable()
    val centerY = double("center_y").nullable()
    val centerZ = double("center_z").nullable()
    val widthM = double("width_m").nullable()
    val depthM = double("depth_m").nullable()
    val heightM = double("height_m").nullable()
    val yawDeg = double("yaw_deg").nullable()
    val sortOrder = integer("sort_order").default(0)
    val uuid = uuid("uuid").autoGenerate()

    init {
        uniqueIndex(project, name)
    }
}

class DaoStageRegion(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoStageRegion>(DaoStageRegions)

    var project by DaoProject referencedOn DaoStageRegions.project
    var name by DaoStageRegions.name
    var centerX by DaoStageRegions.centerX
    var centerY by DaoStageRegions.centerY
    var centerZ by DaoStageRegions.centerZ
    var widthM by DaoStageRegions.widthM
    var depthM by DaoStageRegions.depthM
    var heightM by DaoStageRegions.heightM
    var yawDeg by DaoStageRegions.yawDeg
    var sortOrder by DaoStageRegions.sortOrder
    var uuid by DaoStageRegions.uuid
}
