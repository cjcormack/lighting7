package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.java.javaUUID

/**
 * Stage geometry ([stageX]/[stageY]/[stageZ], [baseYawDeg], [basePitchDeg], [baseRollDeg]) is FOH-relative,
 * right-handed, **Z-up**, metres — see `docs/fixtures-engineering.md`. For moving heads,
 * `base*` is the yoke orientation, not the live aim.
 *
 * When [rigging] is non-null, [stageX]/[stageY]/[stageZ] are interpreted as offsets in
 * the rigging's local frame (rigging origin = (0,0,0)). When [rigging] is null, they are
 * absolute world coordinates from the FOH origin (stage centre on the deck).
 */
object DaoFixturePatches : IntIdTable("fixture_patches") {
    val project = reference("project_id", DaoProjects)
    val universeConfig = reference("universe_config_id", DaoUniverseConfigs)
    val rigging = optReference("rigging_id", DaoRiggings)
    val fixtureTypeKey = varchar("fixture_type_key", 100)
    val key = varchar("key", 100)
    val displayName = varchar("display_name", 255)
    /** The operator's number for this head — what another console calls a head, fixture or channel
     *  number, and what a patch list migrated from one (a ChamSys MagicQ show) is keyed on. Null is
     *  unnumbered. Unique within a project when set, [MIN_HEAD_NUMBER]..[MAX_HEAD_NUMBER], both
     *  enforced at the write boundary rather than by an index — a sync merge of two peers' numbers
     *  must import rather than fail, and a renumber that swaps two heads writes them in one
     *  transaction. Presentational: the loader never reads it. See `docs/fixtures-engineering.md`
     *  §"Head numbers". */
    val headNumber = integer("head_number").nullable()
    val startChannel = integer("start_channel")
    val sortOrder = integer("sort_order").default(0)
    val stageX = double("stage_x").nullable()
    val stageY = double("stage_y").nullable()
    val stageZ = double("stage_z").nullable()
    val baseYawDeg = double("base_yaw_deg").nullable()
    val basePitchDeg = double("base_pitch_deg").nullable()
    /** Body roll: rotation about the body's local Z (three.js terms), applied before pitch and yaw.
     *  What stands a strip on end — its length runs along its own X, which pitch turns about and yaw
     *  swings round, so neither lifts it off level. For a moving head it lays the unit on its side;
     *  it is not a spin about the beam. Null is 0. */
    val baseRollDeg = double("base_roll_deg").nullable()
    val beamAngleDeg = integer("beam_angle_deg").nullable()
    val gelCode = varchar("gel_code", 20).nullable()
    val kindOverride = varchar("kind_override", 32).nullable()
    /** The unit's length in metres along its long axis, for a type whose length is set per install
     *  (`FixtureType.acceptsLength` — a lightstrip cut to its run). Null draws the type's default.
     *  Refused at the write boundary for every other type. Presentational: the loader never reads
     *  it. See `docs/fixtures-engineering.md` §"Variable-length fixtures". */
    val lengthM = double("length_m").nullable()
    /** Omit this patch from the Stage view's 2D/3D renders. For patches that
     *  are real DMX but not stage objects — a dimmer driving hard power, a
     *  hazer's fan channel. Purely presentational: the fixture still patches,
     *  still outputs, and still participates in groups, cues and FX. */
    val stageHidden = bool("stage_hidden").default(false)
    /** Infrastructure rather than a lighting fixture — a dimmer channel switching hard power, a
     *  relay, a hazer's fan. Hidden from every operator surface except the Patches and Channels
     *  views (which are where it is patched and where its raw channels are driven), and never
     *  offered as a target. Presentational like [stageHidden]: it still patches and outputs, and
     *  anything that already names it — a group, a cue, a binding — keeps working. Implies
     *  [stageHidden] on the stage without writing it. See `docs/fixtures-engineering.md`
     *  §"Infrastructure fixtures". */
    val infrastructure = bool("infrastructure").default(false)
    val uuid = javaUUID("uuid").autoGenerate()

    init {
        uniqueIndex(project, key)
    }
}

class DaoFixturePatch(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoFixturePatch>(DaoFixturePatches)

    var project by DaoProject referencedOn DaoFixturePatches.project
    var universeConfig by DaoUniverseConfig referencedOn DaoFixturePatches.universeConfig
    var rigging by DaoRigging optionalReferencedOn DaoFixturePatches.rigging
    var fixtureTypeKey by DaoFixturePatches.fixtureTypeKey
    var key by DaoFixturePatches.key
    var displayName by DaoFixturePatches.displayName
    var headNumber by DaoFixturePatches.headNumber
    var startChannel by DaoFixturePatches.startChannel
    var sortOrder by DaoFixturePatches.sortOrder
    var stageX by DaoFixturePatches.stageX
    var stageY by DaoFixturePatches.stageY
    var stageZ by DaoFixturePatches.stageZ
    var baseYawDeg by DaoFixturePatches.baseYawDeg
    var basePitchDeg by DaoFixturePatches.basePitchDeg
    var baseRollDeg by DaoFixturePatches.baseRollDeg
    var beamAngleDeg by DaoFixturePatches.beamAngleDeg
    var gelCode by DaoFixturePatches.gelCode
    var kindOverride by DaoFixturePatches.kindOverride
    var lengthM by DaoFixturePatches.lengthM
    var stageHidden by DaoFixturePatches.stageHidden
    var infrastructure by DaoFixturePatches.infrastructure
    var uuid by DaoFixturePatches.uuid
}

/** Bounds on a patch's (or a placement's) [DaoFixturePatches.lengthM], metres. The floor is a
 *  rigging's: strictly positive, since a zero-length body has nothing to draw. */
const val MIN_FIXTURE_LENGTH_M = 0.01
const val MAX_FIXTURE_LENGTH_M = 100.0

/** Bounds on a patch's [DaoFixturePatches.headNumber]. Five digits covers every console's fixture
 *  numbering in practice (MagicQ, Eos channels); zero and negatives are no one's head. */
const val MIN_HEAD_NUMBER = 1
const val MAX_HEAD_NUMBER = 99999
