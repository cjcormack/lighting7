package uk.me.cormack.lighting7.venue

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import uk.me.cormack.lighting7.models.AddressFacet
import uk.me.cormack.lighting7.models.DaoFixturePatch
import uk.me.cormack.lighting7.models.DaoFixturePatchPlacement
import uk.me.cormack.lighting7.models.DaoFixturePatches
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoShowOverride
import uk.me.cormack.lighting7.models.DaoShowOverrides
import uk.me.cormack.lighting7.models.FacetValue
import uk.me.cormack.lighting7.models.HideKind
import uk.me.cormack.lighting7.models.PatchFacet
import uk.me.cormack.lighting7.models.PositionFacet
import uk.me.cormack.lighting7.models.RigOwner
import uk.me.cormack.lighting7.models.RigScope
import uk.me.cormack.lighting7.models.VenueLayer
import uk.me.cormack.lighting7.models.decodeFacet
import uk.me.cormack.lighting7.models.encodeFacet
import uk.me.cormack.lighting7.models.extraPlacementsOf
import uk.me.cormack.lighting7.models.facetElement
import uk.me.cormack.lighting7.models.venueFacetOf
import java.util.UUID

/**
 * A show's **facet overrides** of its venue's patches (venue plan §3.2, §3.5, D4, D5; session 5): the
 * second change a show makes to a venue row, beside hiding it. One `show_overrides` row per facet a
 * show makes its own — Address, Position, Focus or Media, each **whole** — naming the patch by uuid.
 * [RigScope.effective] lays them over the pinned snapshot's row; this object writes them.
 *
 * - **Only a venue patch takes one** — 409 [VenueRigGuard.CODE_NOT_A_VENUE_RECORD] for a show's own
 *   patch (edit it instead), a standalone show and a venue. A version refuses every write before this
 *   is reached (`VENUE_VERSION_READ_ONLY`).
 * - **A hidden patch takes no override write but a revert** — 409 [CODE_PATCH_HIDDEN] for a new one or an
 *   edit (it is not in the rig, so the operator could not see what the change does); its existing
 *   overrides stay, dormant, and come back with it. Reverting one is allowed, so they can be cleared.
 * - **[base] is recorded when the override is made**: the pinned version's value of the facet at that
 *   moment. Editing the override's value later keeps it, so a drift the venue introduced stays drawn
 *   until the show answers it — *Keep mine* ([keepMine]: base becomes the venue's current value) or
 *   *Take venue's* (a revert).
 * - **Drift** is the pinned version's current value of the facet differing from [base] ([drift]). An
 *   Update moves the pin and leaves the base (`venue/VenueUpdate.kt`), so that is what makes it.
 *
 * Inside the caller's transaction. Every refusal is a [VenueException].
 */
object ShowOverrides {
    const val CODE_FACET_UNKNOWN = "FACET_UNKNOWN"
    const val CODE_FACET_INVALID = "FACET_INVALID"
    const val CODE_PATCH_HIDDEN = "FACET_PATCH_HIDDEN"
    const val CODE_NOT_OVERRIDDEN = "FACET_NOT_OVERRIDDEN"
    const val CODE_PATCH_NOT_FOUND = "VENUE_RECORD_NOT_FOUND"

    /** The facet a route path names, case-insensitively, or a 400 [CODE_FACET_UNKNOWN]. */
    fun facetOf(name: String): PatchFacet =
        PatchFacet.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: throw VenueException.badRequest(
                CODE_FACET_UNKNOWN,
                "'$name' is not a facet: a venue patch's facets are ${PatchFacet.entries.joinToString(" | ")}",
            )

    /** A facet write's subject: the show's rig, its venue, and the venue patch named, read in the pinned version. */
    class Target(val rig: RigScope, val venue: VenueLayer, val patch: DaoFixturePatch) {
        val placements: List<DaoFixturePatchPlacement> by lazy { extraPlacementsOf(patch) }
        val hidden: Boolean get() = rig.isHidden(HideKind.PATCH, patch.uuid)
    }

    /**
     * The venue patch [patchId] of [project]'s rig, or the refusal: 404 when it is in no rig of this
     * project, 409 [VenueRigGuard.CODE_NOT_A_VENUE_RECORD] for a show's own patch or a project that is
     * not a show on a venue, `VENUE_MISSING` for a show whose pinned version is not on the desk.
     */
    fun target(project: DaoProject, patchId: Int): Target {
        val rig = RigScope.of(project)
        val venue = rig.venue
        if (venue == null) {
            if (VenuePins.isMissing(project)) throw VenuePins.missing(project)
            throw VenueException.conflict(
                VenueRigGuard.CODE_NOT_A_VENUE_RECORD,
                "This project is not a show on a venue: only a venue patch in a show takes facet overrides. " +
                    "Edit the patch itself (PUT …/patches/$patchId).",
            )
        }
        val patch = DaoFixturePatch.findById(patchId)
        when (patch?.let { rig.ownerOf(it.project.id) }) {
            null -> throw VenueException(HttpStatusCode.NotFound, CODE_PATCH_NOT_FOUND, "No patch $patchId in this rig")
            RigOwner.SHOW -> throw VenueException.conflict(
                VenueRigGuard.CODE_NOT_A_VENUE_RECORD,
                "Patch '${patch.key}' is this show's own: edit it (PUT …/patches/$patchId) rather than overriding it",
            )
            RigOwner.VENUE -> Unit
        }
        return Target(rig, venue, patch)
    }

    /**
     * Override [facet] of the target's patch with [value], or revert it to the venue's with null.
     * Answers whether anything changed. A new override records the pinned version's value as its
     * base; an existing one keeps its base (see the class doc).
     */
    fun put(project: DaoProject, target: Target, facet: PatchFacet, value: FacetValue?): Boolean {
        val existing = row(project.id, target.patch.uuid, facet)
        if (value == null) {
            existing?.delete() ?: return false
            return true
        }
        require(value.facet == facet) { "A ${value.facet} value for the $facet facet" }
        if (target.hidden) {
            throw VenueException.conflict(
                CODE_PATCH_HIDDEN,
                "Patch '${target.patch.key}' is hidden in this show: show it again before overriding its ${facet.label()}",
            )
        }
        val text = encodeFacet(value)
        if (existing != null) {
            if (existing.value == text) return false
            existing.value = text
            return true
        }
        DaoShowOverride.new {
            this.project = project
            recordUuid = target.patch.uuid
            this.facet = facet
            this.value = text
            base = encodeFacet(venueFacetOf(target.patch, target.placements, facet))
        }
        return true
    }

    /**
     * *Keep mine*: the override stays, and its base becomes the venue's current value — the show has
     * seen the venue's change and keeps its own. 409 [CODE_NOT_OVERRIDDEN] when the facet is not
     * overridden. Answers whether anything changed.
     */
    fun keepMine(project: DaoProject, target: Target, facet: PatchFacet): Boolean {
        val existing = row(project.id, target.patch.uuid, facet)
            ?: throw VenueException.conflict(
                CODE_NOT_OVERRIDDEN,
                "Patch '${target.patch.key}' takes its ${facet.label()} from the venue: there is no override to keep",
            )
        val current = encodeFacet(venueFacetOf(target.patch, target.placements, facet))
        if (existing.base == current) return false
        existing.base = current
        return true
    }

    /**
     * One overridden facet of a venue patch as the Patch list and the fixture sheet read it: the show's
     * value, the venue's current value, and whether the venue moved under the override since it was
     * made (drift), with the base it moved from. A facet the show does not override is not listed —
     * the patch's own fields are the venue's there.
     */
    @Serializable
    data class FacetStateDto(
        val facet: PatchFacet,
        /** The show's value of the facet — `PUT …/facets/{facet}`'s shape. */
        val value: JsonElement,
        /** The pinned version's value of the facet now. */
        val venue: JsonElement,
        /** The venue's value has moved since the override was made (or last kept). */
        val drifted: Boolean = false,
        /** The venue's value when the override was made; present only when [drifted]. */
        val base: JsonElement? = null,
        /**
         * The override names a universe or rigging this rig no longer has, so it contributes nothing and
         * the venue's facet stands (`RigScope.overridesOf`).
         */
        val dormant: Boolean = false,
    )

    /**
     * [patch]'s overridden facets in [rig], in facet order; empty for any patch but a venue one with
     * overrides. [placements] are the patch's own extra placements in stored order.
     */
    fun statesOf(rig: RigScope, patch: DaoFixturePatch, placements: List<DaoFixturePatchPlacement>): List<FacetStateDto> {
        val venue = rig.venue ?: return emptyList()
        if (patch.readValues[DaoFixturePatches.project] != venue.snapshotId) return emptyList()
        // The scope has read every override once; a patch it holds none for costs no query.
        if (rig.overrides[patch.uuid] == null) return emptyList()
        val rows = DaoShowOverride.find {
            (DaoShowOverrides.project eq rig.projectId) and (DaoShowOverrides.recordUuid eq patch.uuid)
        }.sortedBy { it.facet.ordinal }
        if (rows.isEmpty()) return emptyList()
        val applied = rig.overridesOf(patch)
        return rows.mapNotNull { row ->
            val value = decodeFacet(row.facet, row.value) ?: return@mapNotNull null
            val current = venueFacetOf(patch, placements, row.facet)
            val base = decodeFacet(row.facet, row.base)
            val drifted = base != current
            FacetStateDto(
                facet = row.facet,
                value = facetElement(value),
                venue = facetElement(current),
                drifted = drifted,
                base = base?.takeIf { drifted }?.let(::facetElement),
                dormant = row.facet !in applied,
            )
        }
    }

    /**
     * One override set against a version — what drift is, and what the Update review lists (`VenueUpdate`).
     * [current] is that version's value of the facet; null with [missing] when the version has no such
     * patch (an Update would drop the override's subject).
     */
    data class FacetDrift(
        val recordUuid: UUID,
        /** The patch's key in the version, or null when [missing]. */
        val patchKey: String?,
        val facet: PatchFacet,
        val value: FacetValue?,
        val base: FacetValue?,
        val current: FacetValue?,
        val missing: Boolean,
        /**
         * Judged against the pinned version only: the override names a universe or rigging the show's rig
         * no longer has, so it contributes nothing and the venue's facet stands (`RigScope.overridesOf`).
         */
        val dormant: Boolean = false,
    ) {
        /** The version's value differs from the base the override was made (or last kept) against. */
        val drifted: Boolean get() = !missing && current != base
    }

    /**
     * Every override [project] holds, judged against the venue version whose snapshot is [snapshotId] —
     * the pinned one by default. This is the drift read: today it answers the Patch list's amber chips
     * (through [statesOf]); the Update review calls it with the target version's snapshot to list what
     * an update would move under the show's overrides. Empty for a project on no venue.
     */
    fun drift(project: DaoProject, snapshotId: EntityID<Int>? = null): List<FacetDrift> {
        val pinned = RigScope.of(project)
        val against = snapshotId ?: pinned.venue?.snapshotId ?: return emptyList()
        val judgedLive = against == pinned.venue?.snapshotId
        val rows = DaoShowOverride.find { DaoShowOverrides.project eq project.id }.toList()
        if (rows.isEmpty()) return emptyList()
        val patches = DaoFixturePatch.find { DaoFixturePatches.project eq against }.associateBy { it.uuid }
        val placements = HashMap<UUID, List<DaoFixturePatchPlacement>>()
        return rows.sortedWith(compareBy({ it.recordUuid }, { it.facet.ordinal })).map { row ->
            val patch = patches[row.recordUuid]
            FacetDrift(
                recordUuid = row.recordUuid,
                patchKey = patch?.key,
                facet = row.facet,
                value = decodeFacet(row.facet, row.value),
                base = decodeFacet(row.facet, row.base),
                current = patch?.let { p -> venueFacetOf(p, placements.getOrPut(p.uuid) { extraPlacementsOf(p) }, row.facet) },
                missing = patch == null,
                dormant = judgedLive && patch != null && row.facet !in pinned.overridesOf(patch),
            )
        }
    }

    /**
     * A show's own rigging [riggingUuid] is being deleted: every Position override hanging a unit on it
     * keeps its offsets and loses the rigging, the treatment the rigging delete gives a patch's own
     * reference (`detachPlacementsFromRigging`). The bases are untouched. Answers how many changed.
     */
    fun detachRigging(project: EntityID<Int>, riggingUuid: UUID): Int {
        val target = riggingUuid.toString()
        var changed = 0
        for ((row, v) in positionRows(project)) {
            if (v.riggingUuid != target && v.placements.none { it.riggingUuid == target }) continue
            val detached = v.copy(
                riggingUuid = v.riggingUuid?.takeIf { it != target },
                placements = v.placements.map { p -> if (p.riggingUuid == target) p.copy(riggingUuid = null) else p },
            )
            row.value = encodeFacet(detached)
            changed++
        }
        return changed
    }

    /**
     * A show's own universe [universeUuid] is being deleted, and every patch on it with it: an Address
     * override putting a venue patch on it goes too, so that patch takes the venue's address again rather
     * than keeping a dormant override naming a universe that is gone. Answers how many were removed.
     */
    fun sweepUniverse(project: EntityID<Int>, universeUuid: UUID): Int {
        val target = universeUuid.toString()
        val rows = DaoShowOverride.find {
            (DaoShowOverrides.project eq project) and (DaoShowOverrides.facet eq PatchFacet.ADDRESS)
        }.filter { (decodeFacet(PatchFacet.ADDRESS, it.value) as AddressFacet?)?.universeConfigUuid == target }
        rows.forEach { it.delete() }
        return rows.size
    }

    private fun positionRows(project: EntityID<Int>): List<Pair<DaoShowOverride, PositionFacet>> =
        DaoShowOverride.find {
            (DaoShowOverrides.project eq project) and (DaoShowOverrides.facet eq PatchFacet.POSITION)
        }.mapNotNull { row -> (decodeFacet(PatchFacet.POSITION, row.value) as PositionFacet?)?.let { row to it } }

    private fun row(project: EntityID<Int>, recordUuid: UUID, facet: PatchFacet): DaoShowOverride? =
        DaoShowOverride.find {
            (DaoShowOverrides.project eq project) and (DaoShowOverrides.recordUuid eq recordUuid) and (DaoShowOverrides.facet eq facet)
        }.firstOrNull()

    /** The facet's name in a refusal. */
    fun PatchFacet.label(): String = name.lowercase()
}
