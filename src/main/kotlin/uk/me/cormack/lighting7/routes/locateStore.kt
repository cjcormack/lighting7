package uk.me.cormack.lighting7.routes

import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.request.receive
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fixture.GroupableFixture
import uk.me.cormack.lighting7.fixture.dmx.DmxSlider
import uk.me.cormack.lighting7.fixture.group.FixtureElement
import uk.me.cormack.lighting7.fixture.group.MultiElementFixture
import uk.me.cormack.lighting7.fixture.locate.StoredLocate
import uk.me.cormack.lighting7.fixture.locate.UnitLocate
import uk.me.cormack.lighting7.fixture.locate.storedLocate
import uk.me.cormack.lighting7.fx.LocateValueResolver
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.DaoFixturePatches
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.FocusFacet
import uk.me.cormack.lighting7.models.HideKind
import uk.me.cormack.lighting7.models.PatchFacet
import uk.me.cormack.lighting7.models.RigOwner
import uk.me.cormack.lighting7.models.RigScope
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.models.venueFacetOf
import uk.me.cormack.lighting7.show.patchKeyOf
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.venue.ShowOverrides

/** A store or clear named nothing to act on. */
internal const val CODE_LOCATE_NEEDS_SELECTION = "LOCATE_NEEDS_SELECTION"

/**
 * A unit's **stored locate** (venue-visit plan D6, D17; `docs/fixtures-engineering.md` §"Stored
 * locate"): *Set locate from current* and *Clear locate*, from the programmer and the patch sheet.
 *
 * - `POST /projects/{id}/locate/store` `{targets}` — each unit the targets reach that pans and tilts
 *   takes the pan and tilt **the desk is sending it now** (coarse and fine, park included) as its
 *   stored locate. A fixture stores its own unit and every head's; a head stores its own.
 * - `POST /projects/{id}/locate/clear` `{targets}` — those units' stored locate goes: Locate goes
 *   back to its default.
 *
 * Both write the patch's stored locate where the rig owns the patch (a standalone show's, a venue's
 * own draft, a show's own patch). A **venue patch in a show** takes it through the show's Focus override
 * (venue plan session 5), made from the Focus the show sees with the new locate, so the venue is never
 * written: *Clear locate* there leaves the show with none, and the venue's comes back only through *Take
 * venue's*. A hidden venue patch is skipped. Current project only — the store reads the live output —
 * and a stored write, so an operator's on a venue is refused (`VENUE_ADMIN_ONLY`) and a version's is
 * `VENUE_VERSION_READ_ONLY`, by the write guard. Answers `{stored, skipped}`: each patch's stored locate
 * as it now stands, and every target or unit that took nothing, with why. A unit located while its
 * stored locate changes is re-asserted at the new point.
 */
internal fun Route.routeApiRestLocateStore(state: State) {
    post<LocateStoreResource> { resource ->
        withCurrentProject(state, resource.projectId) { project ->
            respondLocateWrite(state, project, call.receive<LocateStoreRequest>().targets, store = true)
        }
    }
    post<LocateClearResource> { resource ->
        withCurrentProject(state, resource.projectId) { project ->
            respondLocateWrite(state, project, call.receive<LocateStoreRequest>().targets, store = false)
        }
    }
}

private suspend fun io.ktor.server.routing.RoutingContext.respondLocateWrite(
    state: State,
    project: DaoProject,
    targets: List<CueTargetDto>,
    store: Boolean,
) {
    if (targets.isEmpty()) {
        call.respond(
            HttpStatusCode.BadRequest,
            ErrorResponse(
                if (store) "Set locate needs fixtures to read" else "Clear locate needs fixtures to clear",
                CODE_LOCATE_NEEDS_SELECTION,
            ),
        )
        return
    }
    call.respond(writeStoredLocate(state, project, targets, store))
}

/**
 * The body of both routes, separable so a test can drive it without HTTP. [store] true captures what
 * the desk sends each unit now; false clears the units named.
 */
internal fun writeStoredLocate(state: State, project: DaoProject, targets: List<CueTargetDto>, store: Boolean): LocateStoreResponse {
    val skipped = ArrayList<FanSkipDto>()
    val heads = headsOf(state, targets, skipped)

    // Per patch, the units this write sets (store) or takes away (clear), by head (null the fixture's own).
    val captured = LinkedHashMap<String, LinkedHashMap<Int?, UnitLocate>>()
    val cleared = LinkedHashMap<String, MutableSet<Int?>>()
    for ((key, head) in heads) {
        val units = unitsOf(head)
        if (units.isEmpty()) {
            skipped += FanSkipDto(CueTargetDto(TargetRef.Fixture.TYPE, key), "does not pan and tilt")
            continue
        }
        val patchKey = patchKeyOf(head)
        if (store) {
            for ((index, unit) in units) {
                // A unit the rig sends nothing for is skipped rather than stored as 0, a corner of travel.
                val read = capture(state, index, unit)
                    ?: run { skipped += FanSkipDto(CueTargetDto(TargetRef.Fixture.TYPE, key), "no output for this unit"); null }
                    ?: continue
                captured.getOrPut(patchKey) { LinkedHashMap() }[index] = read
            }
        } else {
            cleared.getOrPut(patchKey) { mutableSetOf() } += units.map { it.first }
        }
    }

    // Everything the transaction decides is built inside it and handed out whole, so a retried
    // transaction cannot leave a skip or a change counted twice.
    val (stored, txSkipped, changedKeys) = transaction(state.database) {
        val txSkipped = ArrayList<FanSkipDto>()
        val changedKeys = LinkedHashMap<String, List<UnitLocate>>()
        val rig = RigScope.of(project)
        val keys = (captured.keys + cleared.keys).toList()
        val stored = keys.mapNotNull { patchKey ->
            val patch = rig.patches(DaoFixturePatches.key eq patchKey).firstOrNull()
            if (patch == null) {
                txSkipped += FanSkipDto(CueTargetDto(TargetRef.Fixture.TYPE, patchKey), "not patched")
                return@mapNotNull null
            }
            val current = rig.effective(patch).locate
            val next = StoredLocate.canonical(
                if (store) {
                    val into = captured.getValue(patchKey)
                    current.filter { it.head !in into.keys } + into.values
                } else {
                    val gone = cleared.getValue(patchKey)
                    current.filter { it.head !in gone }
                },
            )
            when (rig.ownerOf(patch.project.id)) {
                RigOwner.VENUE -> {
                    if (rig.isHidden(HideKind.PATCH, patch.uuid)) {
                        txSkipped += FanSkipDto(CueTargetDto(TargetRef.Fixture.TYPE, patchKey), "hidden in this show")
                        return@mapNotNull null
                    }
                    if (next != current) {
                        // The show's Focus as it sees it now — its override, else the venue's — with this locate.
                        val target = ShowOverrides.target(project, patch.id.value)
                        val focus = rig.overridesOf(patch)[PatchFacet.FOCUS] as FocusFacet?
                            ?: venueFacetOf(patch, target.placements, PatchFacet.FOCUS) as FocusFacet
                        ShowOverrides.put(project, target, PatchFacet.FOCUS, focus.copy(locate = next))
                        changedKeys[patchKey] = next
                    }
                }
                else -> if (next != current) {
                    patch.storedLocate = next
                    changedKeys[patchKey] = next
                }
            }
            LocatePatchDto(patch.id.value, patchKey, next)
        }
        Triple(stored, txSkipped, changedKeys)
    }
    skipped += txSkipped

    if (changedKeys.isNotEmpty()) {
        for ((key, locate) in changedKeys) refreshStoredLocate(state, key, locate, reassert = false)
        reassertLocates(state, changedKeys.keys)
        state.show.fixtures.patchListChanged()
    }
    return LocateStoreResponse(stored, skipped)
}

/**
 * Put [locate] in the live rig's patch metadata for [patchKey], where Locate reads it, and re-assert a
 * locate that reaches it ([reassert]). A key the rig has no metadata for is left alone. Shared with the
 * Focus facet write, which changes a venue patch's stored locate in a show.
 */
internal fun refreshStoredLocate(state: State, patchKey: String, locate: List<UnitLocate>, reassert: Boolean = true) {
    val fixtures = state.show.fixtures
    val metadata = fixtures.patchMetadataFor(patchKey) ?: return
    if (metadata.locate == locate) return
    fixtures.setPatchMetadata(patchKey, metadata.copy(locate = locate))
    if (reassert) reassertLocates(state, setOf(patchKey))
}

/**
 * Re-read the stored locate of the patches keyed [patchKeys] in the live rig, through its overrides,
 * and refresh each that moved ([refreshStoredLocate]) — after a facet write that may have changed one
 * without saying so (a Focus override taken back to the venue's, through the MCP tools). A no-op unless
 * [projectId] is the current project.
 */
internal fun syncStoredLocates(state: State, projectId: Int, patchKeys: Collection<String>) {
    if (patchKeys.isEmpty() || state.projectManager.currentProject.id.value != projectId) return
    val locates = transaction(state.database) {
        val rig = RigScope.of(DaoProject[projectId])
        rig.effective(rig.patches(DaoFixturePatches.key inList patchKeys.toList())).associate { it.key to it.locate }
    }
    for ((key, locate) in locates) refreshStoredLocate(state, key, locate)
}

/**
 * The units [head] stands for that pan and tilt, each with its axes: a fixture its own and every
 * head's, a head its own. Keyed by head index, null for the fixture's own.
 */
private fun unitsOf(head: GroupableFixture): List<Pair<Int?, LocateValueResolver.LocateAxes>> = when (head) {
    is FixtureElement<*> -> listOfNotNull(LocateValueResolver.axesOf(head)?.let { head.elementIndex to it })
    is Fixture -> buildList {
        LocateValueResolver.axesOf(head)?.let { add(null to it) }
        if (head is MultiElementFixture<*>) {
            for (element in head.elements) LocateValueResolver.axesOf(element)?.let { add(element.elementIndex to it) }
        }
    }
    else -> emptyList()
}

/**
 * What the desk sends a unit's pan and tilt now — the frame, park included — as its stored locate;
 * null when a channel it needs has no controller in the live rig (the rig rebuilt between resolving
 * the targets and reading them), so nothing is stored rather than a 0 the desk never sent.
 */
private fun capture(state: State, head: Int?, axes: LocateValueResolver.LocateAxes): UnitLocate? {
    fun read(slider: DmxSlider): Int? =
        state.show.fixtures.controllerOrNull(slider.universe)?.getValue(slider.channelNo)?.toInt()
    val panFine = axes.panFine?.let { read(it.second) ?: return null }
    val tiltFine = axes.tiltFine?.let { read(it.second) ?: return null }
    return UnitLocate(
        head = head,
        pan = read(axes.pan) ?: return null,
        tilt = read(axes.tilt) ?: return null,
        panFine = panFine,
        tiltFine = tiltFine,
    )
}

@Resource("/{projectId}/locate/store")
internal data class LocateStoreResource(val projectId: String)

@Resource("/{projectId}/locate/clear")
internal data class LocateClearResource(val projectId: String)

@Serializable
internal data class LocateStoreRequest(val targets: List<CueTargetDto> = emptyList())

/** One patch's stored locate after the write. */
@Serializable
internal data class LocatePatchDto(val patchId: Int, val key: String, val locate: List<UnitLocate>)

@Serializable
internal data class LocateStoreResponse(
    val stored: List<LocatePatchDto> = emptyList(),
    val skipped: List<FanSkipDto> = emptyList(),
)
