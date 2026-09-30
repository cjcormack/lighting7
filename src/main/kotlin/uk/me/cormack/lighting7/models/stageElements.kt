package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass

/**
 * One named element of the scene document (stage-view plan D2, §3.1): a piece of the venue — the
 * hall, the proscenium, the seating — or of this production's set. The Stage view draws it; nothing
 * in the show composes it.
 *
 * Every kind shares the pose and size columns, and what only one kind means lives in [params], a
 * canonical JSON document validated per kind at the write boundary ([ElementParams]). The pose is
 * FOH-relative metres, Z-up (`docs/fixtures-engineering.md`). [positionZ] is the element's **base**,
 * except for a [StageElementKind.PLATFORM], where it is the **top surface** and the deck hangs down
 * from it — as a region's `centerZ` is. [yawDeg] turns the element about Z at its origin.
 *
 * Regions stay separate (D5): they are the playing surface, aim targets and beam receivers, and a
 * platform may link to one through its params rather than duplicating it.
 */
object DaoStageElements : IntIdTable("stage_elements") {
    val project = reference("project_id", DaoProjects)
    val name = varchar("name", 100)
    /** A [StageElementKind] name. */
    val kind = varchar("kind", 20)
    /** A [StageElementLayer] name. */
    val layer = varchar("layer", 10)
    val positionX = double("position_x").default(0.0)
    val positionY = double("position_y").default(0.0)
    val positionZ = double("position_z").default(0.0)
    val yawDeg = double("yaw_deg").default(0.0)
    val widthM = double("width_m").default(0.0)
    val depthM = double("depth_m").default(0.0)
    val heightM = double("height_m").default(0.0)
    /** `#rrggbb`, or null for the kind's default. */
    val finishColour = varchar("finish_colour", 7).nullable()
    /** A [SurfacePattern] name, or null for plain. */
    val finishPattern = varchar("finish_pattern", 10).nullable()
    val emissive = bool("emissive").default(false)
    /** The kind's [ElementParams], as canonical JSON (keys sorted, defaults omitted). */
    val params = text("params").default("{}")
    val hidden = bool("hidden").default(false)
    val sortOrder = integer("sort_order").default(0)
    val uuid = javaUUID("uuid").autoGenerate()

    init {
        uniqueIndex(project, name)
    }
}

class DaoStageElement(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoStageElement>(DaoStageElements)

    var project by DaoProject referencedOn DaoStageElements.project
    var name by DaoStageElements.name
    var kind by DaoStageElements.kind
    var layer by DaoStageElements.layer
    var positionX by DaoStageElements.positionX
    var positionY by DaoStageElements.positionY
    var positionZ by DaoStageElements.positionZ
    var yawDeg by DaoStageElements.yawDeg
    var widthM by DaoStageElements.widthM
    var depthM by DaoStageElements.depthM
    var heightM by DaoStageElements.heightM
    var finishColour by DaoStageElements.finishColour
    var finishPattern by DaoStageElements.finishPattern
    var emissive by DaoStageElements.emissive
    var params by DaoStageElements.params
    var hidden by DaoStageElements.hidden
    var sortOrder by DaoStageElements.sortOrder
    var uuid by DaoStageElements.uuid
}

/**
 * A saved place to look at the stage from (stage-view plan D6, §3.1): portable, because "Row F" is
 * the venue's, not this Mac's. Plan, Front and Side are built in and are never rows.
 *
 * - [StageViewpointKind.ORBIT] and [StageViewpointKind.EYE] carry an eye and a target, in the stage's
 *   lighting coordinates; an eye view also carries its lens.
 * - [StageViewpointKind.SEAT] names a seat of a seating element instead of an eye: the eye is the
 *   seat's, at seated height, so moving the seating moves the view. It may carry a target and a
 *   lens; without them it looks at the stage.
 *
 * The seat reference is a **uuid**, not a foreign key, like every other cross-record reference sync
 * carries: a forced delete of the seating leaves it dangling, and a reader treats that as no seat.
 */
object DaoStageViewpoints : IntIdTable("stage_viewpoints") {
    val project = reference("project_id", DaoProjects)
    val name = varchar("name", 100)
    /** A [StageViewpointKind] name. */
    val kind = varchar("kind", 10)
    val eyeX = double("eye_x").nullable()
    val eyeY = double("eye_y").nullable()
    val eyeZ = double("eye_z").nullable()
    val targetX = double("target_x").nullable()
    val targetY = double("target_y").nullable()
    val targetZ = double("target_z").nullable()
    val fovDeg = double("fov_deg").nullable()
    val seatElementUuid = javaUUID("seat_element_uuid").nullable()
    /** A seat of the seating element, row letter then number: `F6`. */
    val seatId = varchar("seat_id", 8).nullable()
    val sortOrder = integer("sort_order").default(0)
    val uuid = javaUUID("uuid").autoGenerate()

    init {
        uniqueIndex(project, name)
    }
}

class DaoStageViewpoint(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoStageViewpoint>(DaoStageViewpoints)

    var project by DaoProject referencedOn DaoStageViewpoints.project
    var name by DaoStageViewpoints.name
    var kind by DaoStageViewpoints.kind
    var eyeX by DaoStageViewpoints.eyeX
    var eyeY by DaoStageViewpoints.eyeY
    var eyeZ by DaoStageViewpoints.eyeZ
    var targetX by DaoStageViewpoints.targetX
    var targetY by DaoStageViewpoints.targetY
    var targetZ by DaoStageViewpoints.targetZ
    var fovDeg by DaoStageViewpoints.fovDeg
    var seatElementUuid by DaoStageViewpoints.seatElementUuid
    var seatId by DaoStageViewpoints.seatId
    var sortOrder by DaoStageViewpoints.sortOrder
    var uuid by DaoStageViewpoints.uuid
}
