package uk.me.cormack.lighting7.routes

import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.resources.get
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.state.State

/**
 * "What does this cue actually assert, and which part of it said so?"
 *
 * The read behind the desk's cue surface. Since session 2a a cue is drawn as the *same* value grid
 * the programmer uses — same cells, same ownership colours, drawn read-only — so it needs the cue's
 * composed values per `(target, property)` plus the layer that won each one. That is precisely what
 * [buildCombinedCueLayerRows] already produces on the way to firing a cue, so this is a DTO mapping
 * over the real cook rather than a second implementation of it.
 *
 * **Distinct from `POST /cues/preview`**, which was not usable here and is worth saying why: preview
 * answers "what DMX would this send", returning channel values merged against what is already live.
 * A grid is keyed by property, not channel, and needs to know that `rgbColour` on SL Wash 1 came
 * from *Warm Wash* — neither of which survives the trip through the patch. The two are different
 * questions about the same cue.
 *
 * Composing this client-side was the alternative and is the thing to keep not doing: it would mean
 * reimplementing layer order, masks, per-layer amount and blend, group expansion and specificity in
 * the browser, and every one of those is a place for the desk and the display to disagree.
 *
 * **Effects are not here.** An effect in the cue band has no static value to report — it is a
 * function of time — so a cue whose look is carried by a chase reads as whatever its values say,
 * which may be nothing. The client lists the cue's effects separately for that reason. Same limit
 * `cuePreview` documents, and the same reason.
 */
@Resource("/{projectId}/cues/{cueId}/cooked")
internal class CueCookedResource(val projectId: String, val cueId: Int)

/** One composed value, in the canonical assignment grammar the client already parses. */
@Serializable
data class CookedRowDto(
    val targetType: String,
    val targetKey: String,
    val propertyName: String,
    /** `"0".."255"`, `"#rrggbb;w128"`, or `"pan,tilt"` — `PropertyValue.serialize()`. */
    val value: String,
    /**
     * The Look layer that won this key, when one did. Null for the cue's **own** rows, which belong
     * to no layer — the same convention `CueAssignmentResolver.Assignment.layerWinner` uses, and the
     * distinction the grid draws as "the cue set this" versus "Warm Wash set this".
     */
    val layerId: Int? = null,
    val lookId: Int? = null,
    val lookName: String? = null,
)

@Serializable
data class CueCookedResponse(
    val cueId: Int,
    val rows: List<CookedRowDto>,
)

internal fun Route.routeApiRestCueCooked(state: State) {
    get<CueCookedResource> { resource ->
        withCurrentProject(state, resource.projectId) { project ->
            val applyData = transaction(state.database) {
                DaoCue.findById(resource.cueId)
                    ?.takeIf { it.cueStack.project.id == project.id }
                    ?.let { buildCueApplyData(it) }
            }
            if (applyData == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Cue not found"))
                return@withCurrentProject
            }

            // The same builder the GO path runs, so a cue cannot look one way here and compose
            // another way on stage.
            val cooked = buildCombinedCueLayerRows(state, resource.cueId, applyData)
            call.respond(
                CueCookedResponse(
                    cueId = resource.cueId,
                    rows = cooked.rows.map { row ->
                        CookedRowDto(
                            targetType = if (row.targetIsGroup) TargetRef.Group.TYPE
                            else TargetRef.Fixture.TYPE,
                            targetKey = row.targetKey,
                            propertyName = row.propertyName,
                            value = row.value.serialize(),
                            layerId = row.layerWinner?.layerId,
                            lookId = row.layerWinner?.lookId,
                            lookName = row.layerWinner?.lookName,
                        )
                    },
                ),
            )
        }
    }
}
