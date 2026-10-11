package uk.me.cormack.lighting7.venue

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.update
import uk.me.cormack.lighting7.models.DaoCueScenery
import uk.me.cormack.lighting7.models.DaoCueStackScenery
import uk.me.cormack.lighting7.models.DaoLookScenery
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoStageElement
import uk.me.cormack.lighting7.models.DaoStageElements
import uk.me.cormack.lighting7.models.DaoStageViewpoint
import uk.me.cormack.lighting7.models.DaoStageViewpoints
import uk.me.cormack.lighting7.models.DaoVenueVersion
import uk.me.cormack.lighting7.models.ProjectKind
import uk.me.cormack.lighting7.models.RigScope
import uk.me.cormack.lighting7.models.SeatingParams
import uk.me.cormack.lighting7.models.StageElementKind
import uk.me.cormack.lighting7.models.StageViewpointKind
import uk.me.cormack.lighting7.models.VenueLayer
import uk.me.cormack.lighting7.models.deleteSceneryForElements
import uk.me.cormack.lighting7.models.readElementParams
import uk.me.cormack.lighting7.models.storedParamsObject
import uk.me.cormack.lighting7.models.storedParamsText
import uk.me.cormack.lighting7.models.toIsoUtc
import uk.me.cormack.lighting7.routes.forgetElement
import uk.me.cormack.lighting7.routes.seatResolves
import uk.me.cormack.lighting7.routes.seatingOf
import uk.me.cormack.lighting7.routes.toFields
import uk.me.cormack.lighting7.state.State
import java.util.UUID

/**
 * A venue's **Preset** seating, as a show takes it (venue plan D6, session 7): a movable stalls layout
 * the venue offers and a show **copies** — at creation, or later by *Replace from preset* — and never
 * references. The venue's Fixed blocks (the balcony) are the venue's, read through the rig as any
 * venue record is; a Preset is left out of every show's rig (`VenueLayer.presetSeatingIds`), so the
 * show's copy is the stalls it seats.
 *
 * **The copy is the show's own row**, with no `block` (a show's seating ignores it, §3.2) and the
 * preset's **uuid kept**, as Extract's copy of a Preset keeps it: a venue seat view sitting in that
 * preset then finds the show's copy, and the copy reads as "from that preset" for as long as it is.
 * Its patches and every other stored field are the preset's as the version froze it.
 *
 * Every helper runs inside the caller's transaction.
 */
object SeatingPresets {

    const val CODE_PRESET_UNKNOWN = "SEATING_PRESET_UNKNOWN"
    const val CODE_NOT_A_VENUE = "NOT_A_VENUE"
    const val CODE_NAME_TAKEN = "SEATING_PRESET_NAME_TAKEN"

    /**
     * The version a project's presets are read from: a venue's installed version, a show's pinned one.
     * Null for a venue with nothing published. A standalone show, or a version, has none to offer.
     */
    private fun versionOf(project: DaoProject): DaoVenueVersion? = when (project.kind) {
        ProjectKind.VENUE -> VenueVersions.versionsOf(project.id.value).firstOrNull()
        ProjectKind.SHOW -> {
            val venueUuid = project.venueUuid
                ?: throw VenueException.conflict(CODE_NOT_A_VENUE, "${project.name} is not on a venue: it has no seating presets to offer")
            if (VenuePins.isMissing(project)) throw VenuePins.missing(project)
            val venue = VenuePins.venueOf(venueUuid)!!
            VenueVersions.versionsOf(venue.id.value).first { it.number == project.venueVersion }
        }
        ProjectKind.VENUE_VERSION ->
            throw VenueException.conflict(CODE_NOT_A_VENUE, "A venue version is read through its venue's history")
    }

    /** [snapshot]'s Preset seating blocks, in the scene's order. */
    private fun presetsIn(snapshot: EntityID<Int>): List<DaoStageElement> =
        DaoStageElement.find { (DaoStageElements.project eq snapshot) and (DaoStageElements.kind eq StageElementKind.SEATING.name) }
            .orderBy(DaoStageElements.sortOrder to SortOrder.ASC, DaoStageElements.name to SortOrder.ASC)
            .filter { !VenueLayer.isFixedSeating(it.params) }

    /**
     * What [project] can seat from: a venue's presets at its installed version (what *Create* copies
     * from), or a show's at its pinned version (what *Replace from preset* copies from), and for a show
     * its own seating as it stands.
     */
    fun list(project: DaoProject): SeatingPresetsDto {
        val version = versionOf(project)
        val venue = if (project.kind == ProjectKind.VENUE) project else project.venueUuid?.let { VenuePins.venueOf(it) }
        val presets = version?.let { presetsIn(it.snapshotProject.id) }.orEmpty()
        val presetUuids = presets.map { it.uuid }.toSet()
        val current = if (project.kind == ProjectKind.SHOW) {
            ownSeating(project).map { SeatingBlockDto(it.uuid.toString(), it.name, seatsOf(it), fromPreset = it.uuid in presetUuids) }
        } else {
            emptyList()
        }
        return SeatingPresetsDto(
            venueId = venue?.id?.value,
            venueName = venue?.name,
            version = version?.number,
            publishedAt = version?.publishedAt?.toIsoUtc(),
            note = version?.note,
            presets = presets.map { SeatingBlockDto(it.uuid.toString(), it.name, seatsOf(it)) },
            current = current,
        )
    }

    /** The show's own seating blocks — what *Replace from preset* replaces. */
    private fun ownSeating(show: DaoProject): List<DaoStageElement> =
        RigScope.of(show).own.stageElements(DaoStageElements.kind eq StageElementKind.SEATING.name)
            .orderBy(DaoStageElements.sortOrder to SortOrder.ASC).toList()

    private fun seatsOf(element: DaoStageElement): Int =
        (readElementParams(StageElementKind.SEATING, element.params) as? SeatingParams)?.let { it.rows * it.seatsPerRow } ?: 0

    /** [uuid] among [snapshot]'s presets, or 400 [CODE_PRESET_UNKNOWN]. */
    private fun presetIn(snapshot: EntityID<Int>, uuid: String, where: String): DaoStageElement {
        val id = runCatching { UUID.fromString(uuid.trim()) }.getOrNull()
        return presetsIn(snapshot).firstOrNull { it.uuid == id }
            ?: throw VenueException.badRequest(CODE_PRESET_UNKNOWN, "$where has no seating preset $uuid")
    }

    /**
     * Copy preset [presetUuid] of [venue]'s installed version into [show], a show just created on it
     * (*Create*'s stalls preset). The caller has pinned the show to that version.
     */
    fun copyOnCreate(show: DaoProject, venue: DaoProject, presetUuid: String): DaoStageElement {
        val version = VenueVersions.versionsOf(venue.id.value).firstOrNull()
            ?: throw VenueException.conflict(VenueVersions.CODE_NOTHING_PUBLISHED, "${venue.name} has no published version yet")
        val preset = presetIn(version.snapshotProject.id, presetUuid, "${venue.name} v${version.number}")
        return copy(preset, show, sortOrder = nextSortOrder(show))
    }

    /**
     * *Replace from preset*: [show]'s seating — every seating block it owns — replaced by a fresh copy
     * of preset [presetUuid] from its pinned version.
     *
     * **Seat views** (the show's own) that sat in a replaced block move to the copy: a view whose seat
     * the copy also has (a seat id names the same chair, row and number) keeps looking from it; one
     * whose seat it lacks would look from nowhere, so the replace is refused with
     * `STAGE_ELEMENT_IN_USE` naming them, and [force] goes ahead and leaves them without a seat —
     * the element delete's and reshape's rule. A venue's seat view is the venue's, and is not moved.
     *
     * **Scenery** — a cue, stack or Look showing or hiding the stalls — follows the copy when the show
     * had one block; with several, each owner could hold a row for each, so they are swept, as an
     * element delete sweeps them. Answers what moved.
     */
    fun replace(state: State, show: DaoProject, presetUuid: String, force: Boolean): ReplaceSeatingOutcome {
        if (show.kind != ProjectKind.SHOW || show.venueUuid == null) {
            throw VenueException.conflict(CODE_NOT_A_VENUE, "${show.name} is not on a venue: it has no seating presets to replace from")
        }
        val version = versionOf(show)!!
        val preset = presetIn(version.snapshotProject.id, presetUuid, "v${version.number}")
        val old = ownSeating(show)
        val oldUuids = old.map { it.uuid }.toSet()
        val oldNames = old.map { it.name }
        val presetParams = readElementParams(StageElementKind.SEATING, preset.params)
        val newSeating = seatingOf(preset.toFields(), presetParams)
        val views = DaoStageViewpoint.find {
            (DaoStageViewpoints.project eq show.id) and (DaoStageViewpoints.kind eq StageViewpointKind.SEAT.name)
        }.filter { it.seatElementUuid in oldUuids }
        val broken = views.filter { !seatResolves(newSeating, it.seatId) }.map { it.name }
        if (broken.isNotEmpty() && !force) return ReplaceSeatingOutcome.InUse(broken)

        val sortOrder = old.minOfOrNull { it.sortOrder } ?: nextSortOrder(show)
        // The copy is made before the old blocks go, so their scenery can follow it. Names are unique per
        // project, so the old blocks step aside first; a re-copy of the same preset shares their uuid for
        // that moment, which nothing reads inside this transaction.
        for (element in old) {
            DaoStageElements.update({ DaoStageElements.id eq element.id }) { it[name] = "~${element.uuid}" }
        }
        val oldIds = old.map { it.id }
        val sceneryKept = oldIds.size == 1
        val swept = if (sceneryKept) 0 else deleteSceneryForElements(oldIds)
        val copy = copy(preset, show, sortOrder)
        val moved = if (sceneryKept) moveScenery(oldIds.single(), copy.id) else 0
        for (element in old) {
            // An old block's machine-local *Full detail* goes with it — unless the copy is the same preset
            // again, sharing its uuid: that override is the copy's now, and stays.
            if (element.uuid != copy.uuid) forgetElement(state, show, element)
            element.delete()
        }
        views.forEach { it.seatElementUuid = copy.uuid }
        return ReplaceSeatingOutcome.Replaced(
            ReplaceSeatingResultDto(
                seating = SeatingBlockDto(copy.uuid.toString(), copy.name, seatsOf(copy), fromPreset = true),
                removed = oldNames,
                viewsReseated = views.size - broken.size,
                viewsWithoutSeat = broken,
                sceneryMoved = moved,
                scenerySwept = swept,
            ),
        )
    }

    /** Every scenery row naming [from] re-pointed at [to]. */
    private fun moveScenery(from: EntityID<Int>, to: EntityID<Int>): Int =
        DaoCueScenery.update({ DaoCueScenery.element eq from }) { it[DaoCueScenery.element] = to } +
            DaoCueStackScenery.update({ DaoCueStackScenery.element eq from }) { it[DaoCueStackScenery.element] = to } +
            DaoLookScenery.update({ DaoLookScenery.element eq from }) { it[DaoLookScenery.element] = to }

    private fun nextSortOrder(show: DaoProject): Int =
        (RigScope.of(show).own.stageElements().maxOfOrNull { it.sortOrder } ?: -1) + 1

    /**
     * [preset] as [show]'s own seating: every stored field the version froze, `block` dropped, uuid kept.
     * Names are one namespace across the rig (§3.3) and unique per project, and a Preset is out of the
     * show's rig, so another of the show's elements may hold its name: refused by name, 409
     * [CODE_NAME_TAKEN], rather than as a constraint error. A replace has stepped its old blocks aside
     * first, so they never clash.
     */
    private fun copy(preset: DaoStageElement, show: DaoProject, sortOrder: Int): DaoStageElement {
        val clash = RigScope.of(show).stageElements(DaoStageElements.name eq preset.name).firstOrNull()
        if (clash != null) {
            throw VenueException.conflict(
                CODE_NAME_TAKEN,
                "${show.name} already has a stage element called '${preset.name}': rename it before taking the preset",
            )
        }
        val params = JsonObject(storedParamsObject(preset.params) - "block")
        return DaoStageElement.new {
            project = show
            name = preset.name
            kind = preset.kind
            layer = preset.layer
            positionX = preset.positionX
            positionY = preset.positionY
            positionZ = preset.positionZ
            yawDeg = preset.yawDeg
            widthM = preset.widthM
            depthM = preset.depthM
            heightM = preset.heightM
            finishColour = preset.finishColour
            finishPattern = preset.finishPattern
            emissive = preset.emissive
            this.params = storedParamsText(params)
            hidden = preset.hidden
            this.sortOrder = sortOrder
            uuid = preset.uuid
        }
    }
}

/** What *Replace from preset* did, or why it would not. */
sealed interface ReplaceSeatingOutcome {
    data class Replaced(val result: ReplaceSeatingResultDto) : ReplaceSeatingOutcome
    data class InUse(val views: List<String>) : ReplaceSeatingOutcome
}

/** A seating block: its uuid, name and seat count; [fromPreset] when a show's copy keeps a preset's uuid. */
@Serializable
data class SeatingBlockDto(
    val uuid: String,
    val name: String,
    val seats: Int,
    val fromPreset: Boolean = false,
)

/**
 * `GET /projects/{id}/seating/presets`: the venue and version the presets come from — a venue's
 * installed version, a show's pinned one — its presets, and a show's own seating as it stands.
 */
@Serializable
data class SeatingPresetsDto(
    val venueId: Int? = null,
    val venueName: String? = null,
    /** Null for a venue with nothing published. */
    val version: Int? = null,
    val publishedAt: String? = null,
    val note: String? = null,
    val presets: List<SeatingBlockDto> = emptyList(),
    /** A show's own seating; empty for a venue. */
    val current: List<SeatingBlockDto> = emptyList(),
)

/** What *Replace from preset* did. */
@Serializable
data class ReplaceSeatingResultDto(
    val seating: SeatingBlockDto,
    /** The show's seating blocks it replaced, by name. */
    val removed: List<String>,
    /** The show's seat views that moved to the copy and still have their seat. */
    val viewsReseated: Int,
    /** The show's seat views a forced replace left without a seat. */
    val viewsWithoutSeat: List<String> = emptyList(),
    /** Scenery rows that followed the stalls onto the copy. */
    val sceneryMoved: Int = 0,
    /** Scenery rows swept with the blocks, when there were several. */
    val scenerySwept: Int = 0,
)
