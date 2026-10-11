package uk.me.cormack.lighting7.venue

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import uk.me.cormack.lighting7.fixture.FixtureTypeRegistry
import uk.me.cormack.lighting7.models.DaoFixtureGroup
import uk.me.cormack.lighting7.models.DaoFixtureGroupMembers
import uk.me.cormack.lighting7.models.DaoFixtureGroups
import uk.me.cormack.lighting7.models.DaoFixturePatch
import uk.me.cormack.lighting7.models.DaoFixturePatches
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoRigging
import uk.me.cormack.lighting7.models.DaoRiggings
import uk.me.cormack.lighting7.models.DaoShowHide
import uk.me.cormack.lighting7.models.DaoShowHides
import uk.me.cormack.lighting7.models.DaoStageElement
import uk.me.cormack.lighting7.models.DaoStageElements
import uk.me.cormack.lighting7.models.DaoStageRegion
import uk.me.cormack.lighting7.models.DaoStageRegions
import uk.me.cormack.lighting7.models.DaoStageViewpoint
import uk.me.cormack.lighting7.models.DaoStageViewpoints
import uk.me.cormack.lighting7.models.HideKind
import uk.me.cormack.lighting7.models.RigOwner
import uk.me.cormack.lighting7.models.RigScope
import uk.me.cormack.lighting7.models.VenueLinks
import java.util.UUID

/**
 * Hiding a venue record in a show (venue plan §3.2, §3.5, D5): the one change a show makes to its
 * venue's rows this session. A hide is a `show_hides` row naming the record's kind and uuid;
 * [RigScope] leaves the record out of the show's rig from then on. All six kinds hide the same way.
 *
 * - **Only a venue record hides** — 409 [VenueRigGuard.CODE_NOT_A_VENUE_RECORD] for a standalone
 *   show, or for a show's own row (delete it instead).
 * - **Nothing the rig still uses hides** — 409 [CODE_HIDE_IN_USE], naming what holds it: a rigging a
 *   visible fixture or lantern hangs on, seating a visible seat view sits in, a record a show row
 *   links to by id (a busk rig tile, a cue event, a scenery change, a show patch on the rigging).
 *   A show group's membership of a hidden patch is the exception: the rig filters it out
 *   ([RigScope.members]), and it is back when the patch is. Rows that name a hidden patch by key —
 *   cue, Look and template rows — stay, marked *Missing fixture* as today.
 * - **Unhiding gives the key back** — 409 [VenueRigGuard.CODE_KEY_TAKEN_BY_SHOW] while the show's own
 *   record holds the venue record's key, name or head number (the show reused it while it was hidden),
 *   or — for a patch — while a show patch sits on its channels: a hide frees them for the show's own
 *   units (the patch create's overlap check reads the rig), so bringing it back must not land on one.
 *
 * Inside the caller's transaction.
 */
object ShowHides {
    const val CODE_HIDE_IN_USE = "VENUE_HIDE_IN_USE"

    /** What one hide names, resolved against the pinned version — what `GET …/hides` lists. */
    @Serializable
    data class HideDto(
        val kind: HideKind,
        val uuid: String,
        /** The record's row id in the pinned version (the id `PUT …/hidden` takes); null when the version lacks it. */
        val id: Int? = null,
        val name: String? = null,
        /** A patch's key. */
        val key: String? = null,
    )

    /** The show's hides, resolved against its pinned version, by kind then name. */
    fun list(project: DaoProject): List<HideDto> {
        val venue = RigScope.of(project).venue ?: return emptyList()
        return DaoShowHide.find { DaoShowHides.project eq project.id }.map { hide ->
            val row = venueRow(venue.snapshotId, hide.recordKind, hide.recordUuid)
            HideDto(hide.recordKind, hide.recordUuid.toString(), row?.id, row?.name, row?.key)
        }.sortedWith(compareBy({ it.kind }, { it.name ?: it.uuid }))
    }

    /**
     * Hide ([hidden] true) or show again the venue record of [kind] whose row in the pinned version is
     * [rowId]. Answers whether anything changed. Throws a [VenueException] for every refusal.
     */
    fun setHiddenById(project: DaoProject, kind: HideKind, rowId: Int, hidden: Boolean): Boolean {
        val rig = RigScope.of(project)
        val venue = rig.venue ?: throw notOnVenue()
        val row = rowById(kind, rowId)
        val owner = row?.let { rig.ownerOf(it.project) }
        when (owner) {
            null -> throw VenueException(HttpStatusCode.NotFound, CODE_HIDE_NOT_FOUND, "No ${kind.label()} $rowId in this rig")
            RigOwner.SHOW -> throw VenueException.conflict(
                VenueRigGuard.CODE_NOT_A_VENUE_RECORD,
                "${row.describe(kind)} is this show's own: delete it instead of hiding it",
            )
            RigOwner.VENUE -> Unit
        }
        return setHidden(project, rig, venue.snapshotId, kind, row.uuid, hidden)
    }

    /** As [setHiddenById], naming the record by its uuid (a record's identity across versions). */
    fun setHiddenByUuid(project: DaoProject, kind: HideKind, uuid: UUID, hidden: Boolean): Boolean {
        val rig = RigScope.of(project)
        val venue = rig.venue ?: throw notOnVenue()
        if (venueRow(venue.snapshotId, kind, uuid) == null) {
            // Showing again a hide the version no longer holds just drops it.
            if (!hidden && DaoShowHide.find(hideRow(project.id, kind, uuid)).any()) {
                DaoShowHide.find(hideRow(project.id, kind, uuid)).forEach { it.delete() }
                return true
            }
            throw VenueException(HttpStatusCode.NotFound, CODE_HIDE_NOT_FOUND, "The venue has no ${kind.label()} $uuid")
        }
        return setHidden(project, rig, venue.snapshotId, kind, uuid, hidden)
    }

    private fun setHidden(
        project: DaoProject,
        rig: RigScope,
        snapshotId: EntityID<Int>,
        kind: HideKind,
        uuid: UUID,
        hidden: Boolean,
    ): Boolean {
        val existing = DaoShowHide.find(hideRow(project.id, kind, uuid)).firstOrNull()
        val row = venueRow(snapshotId, kind, uuid) ?: return false
        if (hidden) {
            if (existing != null) return false
            val holders = holdersOf(project, rig, snapshotId, kind, row)
            if (holders.isNotEmpty()) {
                throw VenueException.conflict(
                    CODE_HIDE_IN_USE,
                    "${row.describe(kind)} is still in use in this show: ${holders.joinToString("; ")}. " +
                        "Move or remove those first.",
                )
            }
            DaoShowHide.new {
                this.project = project
                recordKind = kind
                recordUuid = uuid
            }
            return true
        }
        if (existing == null) return false
        reuseOf(rig, kind, row)?.let { throw it }
        existing.delete()
        return true
    }

    /** Whose a refusal names, read from one venue row. */
    private class Row(val id: Int, val project: EntityID<Int>, val uuid: UUID, val name: String, val key: String? = null, val headNumber: Int? = null) {
        fun describe(kind: HideKind) = "${kind.label().replaceFirstChar { it.uppercase() }} '${key ?: name}'"
    }

    private fun rowById(kind: HideKind, id: Int): Row? = when (kind) {
        HideKind.PATCH -> DaoFixturePatch.findById(id)?.let { Row(id, it.project.id, it.uuid, it.displayName, it.key, it.headNumber) }
        HideKind.RIGGING -> DaoRigging.findById(id)?.let { Row(id, it.project.id, it.uuid, it.name) }
        HideKind.ELEMENT -> DaoStageElement.findById(id)?.let { Row(id, it.project.id, it.uuid, it.name) }
        HideKind.REGION -> DaoStageRegion.findById(id)?.let { Row(id, it.project.id, it.uuid, it.name) }
        HideKind.GROUP -> DaoFixtureGroup.findById(id)?.let { Row(id, it.project.id, it.uuid, it.name) }
        HideKind.VIEWPOINT -> DaoStageViewpoint.findById(id)?.let { Row(id, it.project.id, it.uuid, it.name) }
    }

    private fun venueRow(snapshotId: EntityID<Int>, kind: HideKind, uuid: UUID): Row? = when (kind) {
        HideKind.PATCH -> DaoFixturePatch.find { (DaoFixturePatches.project eq snapshotId) and (DaoFixturePatches.uuid eq uuid) }
            .firstOrNull()?.let { Row(it.id.value, it.project.id, it.uuid, it.displayName, it.key, it.headNumber) }
        HideKind.RIGGING -> DaoRigging.find { (DaoRiggings.project eq snapshotId) and (DaoRiggings.uuid eq uuid) }
            .firstOrNull()?.let { Row(it.id.value, it.project.id, it.uuid, it.name) }
        HideKind.ELEMENT -> DaoStageElement.find { (DaoStageElements.project eq snapshotId) and (DaoStageElements.uuid eq uuid) }
            .firstOrNull()?.let { Row(it.id.value, it.project.id, it.uuid, it.name) }
        HideKind.REGION -> DaoStageRegion.find { (DaoStageRegions.project eq snapshotId) and (DaoStageRegions.uuid eq uuid) }
            .firstOrNull()?.let { Row(it.id.value, it.project.id, it.uuid, it.name) }
        HideKind.GROUP -> DaoFixtureGroup.find { (DaoFixtureGroups.project eq snapshotId) and (DaoFixtureGroups.uuid eq uuid) }
            .firstOrNull()?.let { Row(it.id.value, it.project.id, it.uuid, it.name) }
        HideKind.VIEWPOINT -> DaoStageViewpoint.find { (DaoStageViewpoints.project eq snapshotId) and (DaoStageViewpoints.uuid eq uuid) }
            .firstOrNull()?.let { Row(it.id.value, it.project.id, it.uuid, it.name) }
    }

    private fun hideRow(project: EntityID<Int>, kind: HideKind, uuid: UUID) =
        (DaoShowHides.project eq project) and (DaoShowHides.recordKind eq kind) and (DaoShowHides.recordUuid eq uuid)

    /** What in the show's rig still holds [row]: each a phrase for the refusal. Empty when it may hide. */
    private fun holdersOf(project: DaoProject, rig: RigScope, snapshotId: EntityID<Int>, kind: HideKind, row: Row): List<String> {
        val holders = mutableListOf<String>()
        val target = kind.table()
        // The show's own rows that link to it by id, through the registry every link is in (P3). A
        // show group's membership of a patch is not one: the rig filters it out while it is hidden.
        for (link in VenueLinks.ALL) {
            if (link.target != target || (link.table == DaoFixtureGroupMembers && link.column.name == DaoFixtureGroupMembers.fixturePatch.name)) continue
            val count = link.table.select(link.table.id)
                .where { link.owner.ownedBy(project.id) and (link.column eq EntityID(row.id, target)) }
                .count()
            if (count > 0) holders += "$count ${link.name} row${if (count == 1L) "" else "s"}"
        }
        // The venue's own rows that are still in the show's rig and need it.
        when (kind) {
            HideKind.RIGGING -> {
                // Where the rig hangs the venue's visible units — a Position override (session 5) moving
                // one onto this rigging or off it included — the patch's own placement or an extra one.
                val units = rig.patches(DaoFixturePatches.project eq snapshotId).toList()
                val placements = rig.effectivePlacementsByPatch(units)
                val hung = rig.effective(units).filter { p ->
                    p.rigging?.id?.value == row.id || placements[p.id.value].orEmpty().any { it.rigging?.id?.value == row.id }
                }.map { it.key }
                hung.takeIf { it.isNotEmpty() }?.let { holders += "fixtures hang on it (${it.joinToString()})" }
            }
            HideKind.ELEMENT -> {
                val views = rig.stageViewpoints(DaoStageViewpoints.seatElementUuid eq row.uuid).map { it.name }
                if (views.isNotEmpty()) holders += "seat views sit in it (${views.joinToString()})"
            }
            else -> Unit
        }
        return holders
    }

    /** The refusal for showing [row] again while one of the show's own records holds its identity or its channels. */
    private fun reuseOf(rig: RigScope, kind: HideKind, row: Row): VenueException? {
        val own = rig.own
        fun holds(what: String) = "this show's own record now has $what"
        val clash: String? = when (kind) {
            HideKind.PATCH -> {
                val key = own.patches(DaoFixturePatches.key eq row.key!!).firstOrNull()?.let { holds("its key '${row.key}'") }
                key ?: row.headNumber?.let { n -> own.patches(DaoFixturePatches.headNumber eq n).firstOrNull()?.let { holds("its head number $n") } }
                    ?: channelsTakenBy(rig, row.id)
            }
            HideKind.RIGGING -> own.riggings(DaoRiggings.name eq row.name).firstOrNull()?.let { holds("its name '${row.name}'") }
            HideKind.ELEMENT -> own.stageElements(DaoStageElements.name eq row.name).firstOrNull()?.let { holds("its name '${row.name}'") }
            HideKind.REGION -> own.stageRegions(DaoStageRegions.name eq row.name).firstOrNull()?.let { holds("its name '${row.name}'") }
            HideKind.GROUP -> own.groups(DaoFixtureGroups.name eq row.name).firstOrNull()?.let { holds("its name '${row.name}'") }
            HideKind.VIEWPOINT -> own.stageViewpoints(DaoStageViewpoints.name eq row.name).firstOrNull()?.let { holds("its name '${row.name}'") }
        }
        return clash?.let {
            VenueException.conflict(
                VenueRigGuard.CODE_KEY_TAKEN_BY_SHOW,
                "${row.describe(kind)} cannot come back: $it. Repatch, rename or delete that first.",
            )
        }
    }

    /**
     * A show patch on the channels venue patch [patchId] would take back — the overlap the patch
     * create refuses, judged by universe number since the show's patch and the version's may name it
     * through different universe rows.
     */
    private fun channelsTakenBy(rig: RigScope, patchId: Int): String? {
        // Where it would come back to: an Address override it carries comes back with it.
        val patch = DaoFixturePatch.findById(patchId)?.let(rig::effective) ?: return null
        val subnet = patch.universeConfig.subnet
        val universe = patch.universeConfig.universe
        val first = patch.startChannel
        val last = first + channelCountOf(patch.fixtureTypeKey) - 1
        val clash = rig.own.patches().firstOrNull { other ->
            other.universeConfig.subnet == subnet && other.universeConfig.universe == universe &&
                first <= other.startChannel + channelCountOf(other.fixtureTypeKey) - 1 && last >= other.startChannel
        } ?: return null
        return "this show's own fixture '${clash.displayName}' (${clash.key}) now sits on its channels"
    }

    private fun channelCountOf(typeKey: String) =
        FixtureTypeRegistry.allTypes.find { it.typeKey == typeKey }?.channelCount ?: 1

    private fun notOnVenue() = VenueException.conflict(
        VenueRigGuard.CODE_NOT_A_VENUE_RECORD,
        "This project is not a show on a venue: only a venue's records can be hidden in a show",
    )

    const val CODE_HIDE_NOT_FOUND = "VENUE_RECORD_NOT_FOUND"

    private fun HideKind.label() = when (this) {
        HideKind.PATCH -> "patch"
        HideKind.RIGGING -> "rigging"
        HideKind.ELEMENT -> "stage element"
        HideKind.REGION -> "stage region"
        HideKind.GROUP -> "group"
        HideKind.VIEWPOINT -> "viewpoint"
    }

    private fun HideKind.table(): IntIdTable = when (this) {
        HideKind.PATCH -> DaoFixturePatches
        HideKind.RIGGING -> DaoRiggings
        HideKind.ELEMENT -> DaoStageElements
        HideKind.REGION -> DaoStageRegions
        HideKind.GROUP -> DaoFixtureGroups
        HideKind.VIEWPOINT -> DaoStageViewpoints
    }
}
