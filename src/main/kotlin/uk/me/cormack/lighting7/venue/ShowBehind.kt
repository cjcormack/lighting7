package uk.me.cormack.lighting7.venue

import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.models.AddressFacet
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoShowHide
import uk.me.cormack.lighting7.models.DaoShowHides
import uk.me.cormack.lighting7.models.DaoShowOverride
import uk.me.cormack.lighting7.models.DaoShowOverrides
import uk.me.cormack.lighting7.models.DaoUniverseConfig
import uk.me.cormack.lighting7.models.DaoUniverseConfigs
import uk.me.cormack.lighting7.models.HideKind
import uk.me.cormack.lighting7.models.ParkAddress
import uk.me.cormack.lighting7.models.VenueParks
import uk.me.cormack.lighting7.models.PatchFacet
import uk.me.cormack.lighting7.models.ProjectKind
import uk.me.cormack.lighting7.models.decodeFacet
import uk.me.cormack.lighting7.state.State
import java.util.UUID

/**
 * How far behind its venue a show is (venue plan §3.1, D7; session 6).
 *
 * - **NONE** — a standalone show, a venue, a show on its venue's installed version, and a show whose
 *   pinned version (or venue) this desk does not hold (it loads no rig at all, `VenuePins`).
 * - **DRAWING** — pinned older than the installed version, and nothing between the two changes what
 *   this show would send: a banner, output live.
 * - **OUTPUT** — at least one change between the two is output-affecting by [VenueDiff]'s own rule (a
 *   universe added, removed or re-addressed; a patch added, removed, re-addressed or re-typed; a park
 *   added, removed or changed) **and reaches this show**. The show then opens held (`dmx/OutputHold.kt`).
 *
 * **What "reaches this show" means.** The show's layer can stand between a venue change and its output,
 * and the hold is for what an Update would change on the wire — holding a show over a change the update
 * itself would not make only blacks out the rig until someone presses a button that sends the same
 * thing. So two kinds of output change are discounted (counted as drawing):
 *
 * - a change to a patch the show **hides** — removed, re-addressed or re-typed, the show drives it at
 *   neither version;
 * - a **re-address** of a patch whose Address the show **overrides**, where the override applies at
 *   both versions (its universe is in both rigs): the show sends that unit to its own address either
 *   way. The venue's move is still drift, which the Update review and the Patch list's chip say;
 * - a change to a **park** on a channel the show parks itself or has released: the show's own word on
 *   the channel stands at both versions (`models/VenueParks.kt`), so the venue's park never reaches it.
 *
 * A re-type is never discounted (no facet overrides the type), nor is any universe change (a show's
 * override names a universe, so re-addressing the universe moves the unit too), nor an added patch.
 *
 * Both versions are snapshots, so [VenueVersions.diff] reads them through its cache: a show's judgement
 * costs one export per version the first time, and a compare after that.
 */
object ShowBehind {

    private val logger = LoggerFactory.getLogger(ShowBehind::class.java)

    /**
     * One show's judgement. [pinned] and [installed] are null when the show is on no venue (or this
     * desk lacks it); [output] are the output changes that reach the show, [discounted] the ones its
     * layer stands between, [drawing] the rest.
     */
    data class Judgement(
        val projectId: Int,
        val behind: VenueBehind,
        val venueId: Int? = null,
        val venueUuid: UUID? = null,
        val venueName: String? = null,
        val pinned: Int? = null,
        val installed: Int? = null,
        val output: List<VenueChangeDto> = emptyList(),
        val discounted: List<VenueChangeDto> = emptyList(),
        val drawing: List<VenueChangeDto> = emptyList(),
    )

    private data class Layer(
        val venueId: Int,
        val venueUuid: UUID,
        val venueName: String,
        val pinned: Int,
        val installed: Int,
        val hiddenPatches: Set<UUID>,
        /** Address overrides, by venue patch uuid: the universe uuid each names. */
        val addressUniverses: Map<UUID, String>,
        /** Universes an override may name at the pinned version: the show's own and the pinned snapshot's. */
        val universesAtPin: Set<String>,
        val universesAtInstalled: Set<String>,
        /** The channels the show parks itself or has released: a venue park change there does not reach it. */
        val ownParkChannels: Set<ParkAddress>,
    )

    /** Judge [projectId]. Blocking; opens its own transactions (and joins a caller's). */
    fun judge(state: State, projectId: Int): Judgement {
        val layer = transaction(state.database) { layerOf(projectId) }
            ?: return Judgement(projectId, VenueBehind.NONE).withPin(state, projectId)
        if (layer.pinned >= layer.installed) {
            return Judgement(projectId, VenueBehind.NONE, layer.venueId, layer.venueUuid, layer.venueName, layer.pinned, layer.installed)
        }
        val diff = try {
            state.venueVersions.diff(layer.venueId, layer.pinned, layer.installed)
        } catch (e: Exception) {
            // Fail closed: a show behind its venue whose gap cannot be read is treated as behind on output.
            logger.warn("Could not read venue {} v{} → v{} for project {}: {}; treating it as behind on output",
                layer.venueId, layer.pinned, layer.installed, projectId, e.message)
            return Judgement(projectId, VenueBehind.OUTPUT, layer.venueId, layer.venueUuid, layer.venueName, layer.pinned, layer.installed)
        }
        val (discounted, reaching) = diff.output.partition { layer.discounts(it) }
        return Judgement(
            projectId = projectId,
            behind = if (reaching.isNotEmpty()) VenueBehind.OUTPUT else VenueBehind.DRAWING,
            venueId = layer.venueId,
            venueUuid = layer.venueUuid,
            venueName = layer.venueName,
            pinned = layer.pinned,
            installed = layer.installed,
            output = reaching,
            discounted = discounted,
            drawing = diff.drawing,
        )
    }

    /** A judgement of NONE still names the pin where there is one, for the `venue.hold` frame. */
    private fun Judgement.withPin(state: State, projectId: Int): Judgement = transaction(state.database) {
        val project = DaoProject.findById(projectId) ?: return@transaction this@withPin
        if (project.kind != ProjectKind.SHOW) return@transaction this@withPin
        val venue = project.venueUuid?.let { VenuePins.venueOf(it) }
        copy(
            venueId = venue?.id?.value,
            venueUuid = project.venueUuid,
            venueName = venue?.name,
            pinned = project.venueVersion,
            installed = venue?.let { VenueVersions.versionsOf(it.id.value).firstOrNull()?.number },
        )
    }

    private fun Layer.discounts(change: VenueChangeDto): Boolean {
        VenueDiff.parkAddressOf(change)?.let { return it in ownParkChannels }
        if (change.kind != VenueChangeKind.PATCH) return false
        val uuid = change.uuid?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return false
        if (uuid in hiddenPatches) return true
        if (change.change != VenueChangeType.CHANGED) return false
        if (VenueFacet.TYPE in change.facets || VenueFacet.ADDRESS !in change.facets) return false
        val universe = addressUniverses[uuid] ?: return false
        return universe in universesAtPin && universe in universesAtInstalled
    }

    /** [projectId]'s pin and the layer that can discount a change, or null when it is no show on a venue held here. */
    private fun layerOf(projectId: Int): Layer? {
        val project = DaoProject.findById(projectId) ?: return null
        if (project.kind != ProjectKind.SHOW) return null
        val venueUuid = project.venueUuid ?: return null
        val pinned = project.venueVersion ?: return null
        if (VenuePins.isMissing(project)) return null
        val venue = VenuePins.venueOf(venueUuid) ?: return null
        val installed = VenueVersions.versionsOf(venue.id.value).firstOrNull() ?: return null
        val pinnedSnapshot = VenuePins.snapshotOf(venueUuid, pinned) ?: return null
        fun universesOf(projectId: EntityID<Int>) =
            DaoUniverseConfig.find { DaoUniverseConfigs.project eq projectId }.map { it.uuid.toString() }.toSet()
        val own = universesOf(project.id)
        val hidden = DaoShowHide.find { (DaoShowHides.project eq project.id) and (DaoShowHides.recordKind eq HideKind.PATCH) }
            .map { it.recordUuid }.toSet()
        val addresses = DaoShowOverride.find {
            (DaoShowOverrides.project eq project.id) and (DaoShowOverrides.facet eq PatchFacet.ADDRESS)
        }.mapNotNull { row -> (decodeFacet(PatchFacet.ADDRESS, row.value) as AddressFacet?)?.let { row.recordUuid to it.universeConfigUuid } }
            .toMap()
        return Layer(
            venueId = venue.id.value,
            venueUuid = venueUuid,
            venueName = venue.name,
            pinned = pinned,
            installed = installed.number,
            hiddenPatches = hidden,
            addressUniverses = addresses,
            universesAtPin = own + universesOf(pinnedSnapshot.id),
            universesAtInstalled = own + universesOf(installed.snapshotProject.id),
            ownParkChannels = VenueParks.rowsOf(project.id).keys + VenueParks.releasesOf(project.id),
        )
    }
}

/** How far behind its venue a show is (venue plan §3.1): `ProjectDto.venue.behind`. */
@Serializable
enum class VenueBehind { NONE, DRAWING, OUTPUT }
