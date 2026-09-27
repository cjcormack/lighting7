package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass

/**
 * A patch's **extra placements** — the other places the same fixture hangs.
 *
 * A paired dimmer drives two lanterns from one DMX address: an SL and an SR unit on the LX
 * bar, say. That is one fixture to control (one address, one level, one row in every cue and
 * group) but two objects on the stage. So the patch keeps its primary placement in its own
 * columns ([DaoFixturePatches.stageX] and friends) and gains an ordered list of these, each
 * with the same geometry fields and the same rigging-relative rule: with a [rigging], the
 * coordinates are offsets in the rigging's local frame; without one, they are absolute
 * FOH-relative world coordinates (Z-up, metres — `docs/fixtures-engineering.md`).
 *
 * **Presentational only**, like every stage field on the patch: nothing at runtime reads a
 * placement. The stage views draw one marker per placement, all lit from the patch's one
 * set of channels, which is exactly why the model is placements rather than two patches at
 * one address — the pair can never be told to do two different things.
 *
 * What a placement does *not* carry is the fixture's own facts — type, beam angle, gel, kind
 * override, hidden. A paired lantern is the same unit in the same colour; an override per
 * placement is additive later if a venue ever needs one.
 *
 * The one exception is [lengthM], and it is the first such override: a variable-length type (a
 * lightstrip, `FixtureType.acceptsLength`) laid in segments — a ring round the stage edge is one
 * run on one controller, drawn as four sides — is one patch whose placements are the segments,
 * and each side is its own length. Null takes the patch's own length. Refused, like the patch's,
 * for a type whose length is fixed.
 *
 * Portable: embedded in its patch's sync document as `extraPlacements`
 * (`FixturePatchJson`), in list order, so [sortOrder] is not on the wire.
 *
 * `PRAGMA foreign_keys` is off on this desk, so nothing cascades: every path that deletes a
 * patch calls [deletePlacementsOf], and every path that deletes a rigging calls
 * [detachPlacementsFromRigging] — the same explicit treatment the patch's own rigging
 * reference and its group memberships get.
 */
object DaoFixturePatchPlacements : IntIdTable("fixture_patch_placements") {
    val fixturePatch = reference("fixture_patch_id", DaoFixturePatches)
    val rigging = optReference("rigging_id", DaoRiggings)
    /** Short operator label naming this placement on the plot, e.g. "SR". Null draws none. */
    val label = varchar("label", MAX_PLACEMENT_LABEL_LENGTH).nullable()
    val stageX = double("stage_x").nullable()
    val stageY = double("stage_y").nullable()
    val stageZ = double("stage_z").nullable()
    val baseYawDeg = double("base_yaw_deg").nullable()
    val basePitchDeg = double("base_pitch_deg").nullable()
    /** This segment's length in metres, for a variable-length type; null takes the patch's own. */
    val lengthM = double("length_m").nullable()
    val sortOrder = integer("sort_order").default(0)
    val uuid = javaUUID("uuid").autoGenerate()
}

class DaoFixturePatchPlacement(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoFixturePatchPlacement>(DaoFixturePatchPlacements)

    var fixturePatch by DaoFixturePatch referencedOn DaoFixturePatchPlacements.fixturePatch
    var rigging by DaoRigging optionalReferencedOn DaoFixturePatchPlacements.rigging
    var label by DaoFixturePatchPlacements.label
    var stageX by DaoFixturePatchPlacements.stageX
    var stageY by DaoFixturePatchPlacements.stageY
    var stageZ by DaoFixturePatchPlacements.stageZ
    var baseYawDeg by DaoFixturePatchPlacements.baseYawDeg
    var basePitchDeg by DaoFixturePatchPlacements.basePitchDeg
    var lengthM by DaoFixturePatchPlacements.lengthM
    var sortOrder by DaoFixturePatchPlacements.sortOrder
    var uuid by DaoFixturePatchPlacements.uuid
}

/** Column width of [DaoFixturePatchPlacements.label]; longer labels are refused, not cut. */
const val MAX_PLACEMENT_LABEL_LENGTH = 40

/**
 * Upper bound on a patch's extra placements. Generous — a paired dimmer is two lanterns, a
 * ganged circuit a handful — and there only so a runaway client cannot write thousands.
 */
const val MAX_EXTRA_PLACEMENTS = 16

/** A patch's extra placements, in their stored order. Call inside a transaction. */
fun extraPlacementsOf(patch: DaoFixturePatch): List<DaoFixturePatchPlacement> =
    DaoFixturePatchPlacement.find { DaoFixturePatchPlacements.fixturePatch eq patch.id }
        .orderBy(DaoFixturePatchPlacements.sortOrder to SortOrder.ASC, DaoFixturePatchPlacements.id to SortOrder.ASC)
        .toList()

/**
 * Every listed patch's extra placements in one query, by patch id, each list in stored order.
 * A patch with none is absent from the map. For a loop over many patches, where
 * [extraPlacementsOf] per patch would be a query each. Call inside a transaction.
 */
fun extraPlacementsByPatch(patchIds: Collection<EntityID<Int>>): Map<Int, List<DaoFixturePatchPlacement>> {
    if (patchIds.isEmpty()) return emptyMap()
    return DaoFixturePatchPlacement.find { DaoFixturePatchPlacements.fixturePatch inList patchIds }
        .orderBy(DaoFixturePatchPlacements.sortOrder to SortOrder.ASC, DaoFixturePatchPlacements.id to SortOrder.ASC)
        .groupBy { it.readValues[DaoFixturePatchPlacements.fixturePatch].value }
}

/** Delete a patch's extra placements. Every patch delete calls this first (no FK cascade). */
fun deletePlacementsOf(patch: DaoFixturePatch) {
    DaoFixturePatchPlacement.find { DaoFixturePatchPlacements.fixturePatch eq patch.id }.forEach { it.delete() }
}

/**
 * Clear [rigging] from every placement hung on it, leaving the offsets as they were — the
 * treatment the rigging delete already gives a patch's own rigging reference. Returns the
 * number detached.
 */
fun detachPlacementsFromRigging(rigging: DaoRigging): Int =
    DaoFixturePatchPlacement.find { DaoFixturePatchPlacements.rigging eq rigging.id }
        .toList()
        .onEach { it.rigging = null }
        .size
