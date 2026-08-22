package uk.me.cormack.lighting7.routes

import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.RoutingContext
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fx.PropertyMaskGroup
import uk.me.cormack.lighting7.fx.maskAllows
import uk.me.cormack.lighting7.fx.maskGroupForProperty
import uk.me.cormack.lighting7.fx.parseMaskGroups
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoCuePropertyAssignment
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.state.State

private val logger = LoggerFactory.getLogger("cueFlatten")

@Resource("/{cueId}/flatten")
internal data class FlattenCueLayersResource(
    val parent: ProjectCuesResource,
    val cueId: Int,
    val force: Boolean = false,
)

@Serializable
internal data class CueFlattenRequest(
    /**
     * Flatten only this layer. Omitted = the whole stack.
     *
     * A single layer is only accepted when it is the **last** contributing one — see the file's
     * KDoc for why a middle layer cannot be flattened without changing the output.
     */
    val layerId: Int? = null,
    val mask: List<String>? = null,
)

@Serializable
internal data class CueFlattenResponse(
    val cue: CueDetails,
    /** Local rows written. */
    val rowsWritten: Int,
    /** Layers removed once their contribution was written out. */
    val layersRemoved: Int,
    /** Keys the mask excluded, so their layers were left in place. */
    val maskedOut: Int,
    val republishedLive: Boolean,
)

/**
 * Flatten a cue's Look layers into its own local rows — the successor to the two Make Hard routes.
 *
 * `projectCuesMakeHard.kt` replaced a row's `ref:{uuid}` with the literal it resolved to, and
 * `projectFxPresetsMakeHard.kt` did the same for a preset's target-less rows. Both retired with the
 * `ref:` grammar; what an operator wants from "make this hard" now is *this cue stops depending on
 * the library*, and a layer — which always has a target set — is the thing to detach.
 *
 * **The composed result is what gets written, not the layer's rows.** The cook step already reduces
 * the stack to exactly one value per (fixture, property), with blend, `amount`, `propertyMask` and
 * group expansion applied, so flattening is "write down what cook computed and delete the layers".
 * Three consequences worth stating, because each looks like a loss and isn't:
 *
 * - **Rows come out fixture-targeted, never group-targeted.** The old cue route could keep a group
 *   row when every member happened to resolve to the same literal, because it rewrote a value in
 *   place without cooking. Cook's output is per fixture by construction — that invariant is the
 *   whole point of it — and the group name is not recoverable from a cooked key, so re-deriving a
 *   group row would mean guessing which of several overlapping groups to name.
 * - **A Look row's `fadeDurationMs` does not survive.** It never reached the stage either: cook
 *   carries no per-row fade, so a layered row's fade is already not honoured. Flattening preserves
 *   the cue's *actual* behaviour, which is the guarantee that matters.
 * - **`moveInDark` is preserved**, because it lives on the cue's own rows and those are untouched.
 *
 * **Why a single [CueFlattenRequest.layerId] is restricted to the last layer.** Local rows beat
 * every layer unconditionally (`CueComposer.cook` overlays them last). Promoting a middle layer's
 * values to local rows would therefore make them beat the layers *above* it — the cue would look
 * different immediately after an operation whose whole promise is that nothing changes. Flattening
 * the last contributing layer is safe (nothing was above it), and flattening the whole stack is
 * safe (nothing is left to lose to). Anything in between is refused rather than silently
 * reordered.
 */
internal suspend fun RoutingContext.handleFlattenCueLayers(
    state: State,
    projectId: String,
    cueId: Int,
    force: Boolean,
) {
    val request = try {
        call.receive<CueFlattenRequest>()
    } catch (_: Exception) {
        CueFlattenRequest()
    }

    val mask = try {
        parseMaskGroups(request.mask)
    } catch (e: IllegalArgumentException) {
        return call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Bad mask"))
    }

    // [projectId] from the URL, not the literal "current". The route this replaces passed
    // `"current"`, so a request naming a non-current project resolved the current one instead: the
    // cue guard below then answered 404 where the intended answer was 409, and — worse — a cue that
    // happened to be in the current project was modified even though the caller addressed another.
    withCurrentProject(state, projectId, { p ->
        "Cannot modify project '${p.name}' — only the current project can be modified"
    }) { project ->
        if (!force) {
            // Same rule as Record/Update: an open session's Discard would revert this.
            val open = state.cueEditSessionRegistry.activeSession(project.id.value)
            if (open?.session?.cueId == cueId) {
                call.respond(
                    HttpStatusCode.Conflict,
                    ProgrammerConflictResponse(
                        "A cue-edit session is open on this cue — flattening underneath it would be " +
                            "reverted by Discard.",
                        CODE_CUE_EDIT_SESSION_OPEN,
                        cueId,
                    ),
                )
                return@withCurrentProject
            }
        }

        val result = transaction(state.database) {
            val cue = DaoCue.findById(cueId)?.takeIf { it.project.id == project.id }
                ?: return@transaction FlattenResult.CueNotFound
            flattenCueLayers(state, cue, request.layerId, mask)
        }

        when (result) {
            FlattenResult.CueNotFound ->
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Cue not found in project '$projectId'"))

            FlattenResult.LayerNotFound ->
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Layer ${request.layerId} is not on this cue"))

            FlattenResult.LayerNotLast -> call.respond(
                HttpStatusCode.Conflict,
                ErrorResponse(
                    "Layer ${request.layerId} is not the last layer in this cue. Flattening it would " +
                        "promote its values above the layers on top of it and change the cue's output. " +
                        "Flatten the whole stack, or move this layer to the top first.",
                ),
            )

            is FlattenResult.Done -> {
                val republished = if (result.rowsWritten > 0 || result.layersRemoved > 0) {
                    republishCueIfLive(state, cueId, transaction(state.database) {
                        DaoCue.findById(cueId)?.cueStack?.id?.value
                    })
                } else {
                    false
                }
                if (result.rowsWritten > 0 || result.layersRemoved > 0) state.show.fixtures.cueListChanged()

                val details = transaction(state.database) {
                    DaoCue.findById(cueId)!!.toCueDetails(true, state.show.fixtures)
                }
                logger.info(
                    "flatten cue {}: {} row(s) written, {} layer(s) removed, {} masked out",
                    cueId, result.rowsWritten, result.layersRemoved, result.maskedOut,
                )
                call.respond(
                    CueFlattenResponse(
                        cue = details,
                        rowsWritten = result.rowsWritten,
                        layersRemoved = result.layersRemoved,
                        maskedOut = result.maskedOut,
                        republishedLive = republished,
                    ),
                )
            }
        }
    }
}

private sealed interface FlattenResult {
    data object CueNotFound : FlattenResult
    data object LayerNotFound : FlattenResult
    data object LayerNotLast : FlattenResult
    data class Done(val rowsWritten: Int, val layersRemoved: Int, val maskedOut: Int) : FlattenResult
}

/** Must be called inside a transaction. */
private fun flattenCueLayers(
    state: State,
    cue: DaoCue,
    layerId: Int?,
    mask: Set<PropertyMaskGroup>?,
): FlattenResult {
    val fixtures = state.show.fixtures
    val layers = cue.layers.sortedBy { it.sortOrder }.toList()

    val toRemove = if (layerId == null) {
        layers
    } else {
        val target = layers.firstOrNull { it.id.value == layerId } ?: return FlattenResult.LayerNotFound
        // "Last" means last among the layers that can contribute at all. A disabled layer above
        // this one cannot win a key, so it does not make flattening unsafe; a timed one can fire
        // later and *would*, so it counts.
        val contributingAbove = layers.filter { it.sortOrder > target.sortOrder && it.enabled }
        if (contributingAbove.isNotEmpty()) return FlattenResult.LayerNotLast
        listOf(target)
    }
    if (toRemove.isEmpty()) return FlattenResult.Done(0, 0, 0)

    val removeIds = toRemove.map { it.id.value }.toSet()

    // Cook the cue exactly as apply does, so what we write is what the rig is showing.
    val cooked = buildCombinedCueLayerRows(state, cue.id.value, buildCueApplyData(cue))

    var rowsWritten = 0
    var maskedOut = 0
    var nextSortOrder = (cue.propertyAssignments.maxOfOrNull { it.sortOrder } ?: -1) + 1

    for (assignment in cooked) {
        // A key with no winning layer is already a local row — nothing to promote.
        val winner = assignment.layerWinner ?: continue
        if (winner.layerId !in removeIds) continue

        val fixture = runCatching { fixtures.untypedFixture(assignment.targetKey) }.getOrNull()
        if (fixture !is Fixture) continue
        if (!maskAllows(mask, maskGroupForProperty(fixture, assignment.propertyName))) {
            maskedOut++
            continue
        }

        DaoCuePropertyAssignment.new {
            this.cue = cue
            targetType = TargetRef.Fixture.TYPE
            targetKey = assignment.targetKey
            propertyName = assignment.propertyName
            value = assignment.value.serialize()
            sortOrder = nextSortOrder++
            // A cooked row carries no per-row fade — see the file KDoc.
            fadeDurationMs = null
            moveInDark = assignment.moveInDark
        }
        rowsWritten++
    }

    // A masked flatten leaves the layers alone: they still carry the properties the mask excluded,
    // and deleting them would drop those silently. "Flatten only the colour" therefore *copies*
    // colour down rather than detaching the layer — reported through [CueFlattenResponse.maskedOut]
    // so the operator can see the layer survived on purpose.
    var layersRemoved = 0
    if (maskedOut == 0) {
        for (layer in toRemove) {
            layer.delete()
            layersRemoved++
        }
    }

    return FlattenResult.Done(rowsWritten, layersRemoved, maskedOut)
}
