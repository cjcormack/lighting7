package uk.me.cormack.lighting7.routes

import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.request.receive
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.me.cormack.lighting7.fixture.group.FixtureElement
import uk.me.cormack.lighting7.fx.LayerPressRules
import uk.me.cormack.lighting7.history.historyLabel
import uk.me.cormack.lighting7.fx.TargetCoverage
import uk.me.cormack.lighting7.fx.familyOf
import uk.me.cormack.lighting7.fx.maskAllows
import uk.me.cormack.lighting7.fx.parseFamilies
import uk.me.cormack.lighting7.models.CueAttributeAssignmentDto
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.DaoLook
import uk.me.cormack.lighting7.models.DaoPalette
import uk.me.cormack.lighting7.models.LookRowDto
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.state.State

/**
 * `POST /projects/{id}/looks/{lookId}/press` — a **value** palette tapped into a Look focused in the
 * programmer (palette-references plan D23): reference rows written into, or taken out of, the Look's
 * own rows by the cue press's rule ([CueReferencePress], D10 over stored rows), so a press into a
 * Look and one into a cue cannot drift. A cell's row is read by the cell's key, so a press on the
 * cell writes over it, and a row the press adds on a cell is stored under its parent, as Record stores
 * one. Rows the press keeps keep their order.
 *
 * An FX palette is refused: it joins a Look through `absorb-fx`. A generic palette with no targets
 * is `PALETTE_NEEDS_SELECTION`, and the mask is tested on the on arm only. Nothing reaches the stage
 * but through the Look's own consumers, republished as a contents edit republishes them.
 */
@Resource("/{projectId}/looks/{lookId}/press")
internal class LookPressResource(val projectId: String, val lookId: Int)

@Serializable
internal data class LookPressRequest(
    val paletteId: Int,
    /** The heads the press lands on; none is a per-fixture palette's own. */
    val targets: List<CueTargetDto> = emptyList(),
    /** The selection's attribute mask (`AttributeFamily` names); absent is every attribute. */
    val families: List<String>? = null,
)

@Serializable
internal data class LookPressResponse(
    /** `"applied"` or `"removed"`. */
    val action: String,
    /** The other palettes whose references the press took out. */
    val released: Int = 0,
    /** The Look as stored after the press. */
    val look: LookDetails,
    /** A per-fixture palette's heads the press did not name (D14). */
    val omitted: List<String> = emptyList(),
    /** Pressed heads the palette lands nothing on (D14). */
    val notCovered: List<String> = emptyList(),
)

internal fun Route.routeApiRestLookReferencePress(state: State) {
    post<LookPressResource> { resource ->
        withCurrentProject(state, resource.projectId) { project ->
            val request = call.receive<LookPressRequest>()
            val families = try {
                parseFamilies(request.families)
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Bad mask"))
                return@withCurrentProject
            }
            val bad = request.targets.filter { TargetRef.ofOrNull(it.type, it.key) == null }
            if (bad.isNotEmpty()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("A target must be a fixture or a group: ${bad.joinToString { "${it.type}:${it.key}" }}"))
                return@withCurrentProject
            }
            val found = transaction(state.database) {
                val look = DaoLook.findById(resource.lookId)?.takeIf { it.project.id == project.id } ?: return@transaction null
                val palette = DaoPalette.findById(request.paletteId)?.takeIf { it.project.id == project.id }
                look.uuid to palette?.let { Triple(it.uuid, it.name, it.isGenericPalette()) }
            }
            if (found == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Look not found"))
                return@withCurrentProject
            }
            val (lookUuid, paletteRead) = found
            val (paletteUuid, paletteName, generic) = paletteRead ?: run {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Palette not found"))
                return@withCurrentProject
            }
            val snapshot = state.show.paletteRegistry.snapshot(paletteUuid)
            if (snapshot == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Palette not found"))
                return@withCurrentProject
            }
            if (snapshot.effect != null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("$paletteName is an FX palette: its FX joins a Look as an FX, not as values"))
                return@withCurrentProject
            }
            if (request.targets.isEmpty() && generic) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("This palette needs a selection to press onto", code = CODE_PALETTE_NEEDS_SELECTION))
                return@withCurrentProject
            }
            historyLabel("Press $paletteName")
            val coverage = TargetCoverage { state.show.fixtures }
            val press = CueReferencePress.of(state, LayerPressRules({ state.show.paletteRegistry }, coverage), coverage)
            repeat(LOOK_PRESS_ATTEMPTS) {
                val rows = transaction(state.database) { DaoLook.findById(resource.lookId)?.storedRowDtos() }
                if (rows == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Look not found"))
                    return@withCurrentProject
                }
                val result = press.press(snapshot, request.targets, rows.map { it.asCueRow() })
                if (result.applied && families != null && !maskAllows(families, snapshot.familyOf())) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(paletteOutsideMaskMessage(paletteName, snapshot.familyOf(), families), code = CODE_PALETTE_OUTSIDE_MASK),
                    )
                    return@withCurrentProject
                }
                val written = transaction(state.database) {
                    val look = DaoLook.findById(resource.lookId) ?: return@transaction null
                    if (look.storedRowDtos() != rows) return@transaction null
                    val byUuid = rows.associateBy { it.uuid }
                    // A row the press kept is written back as stored, its order included; a new one
                    // on a cell is stored as Record stores one, under its parent.
                    val next = result.rows.map { row -> byUuid[row.uuid] ?: row.asNewLookRow { key -> elementParentOf(state, key) } }
                    writeLookChildren(look, next, null)
                    look.toDetailsDto(state)
                }
                if (written != null) {
                    republishForLookEdit(state, lookUuid)
                    call.respond(
                        LookPressResponse(
                            action = if (result.applied) "applied" else "removed",
                            released = result.released.size,
                            look = written,
                            omitted = result.omitted,
                            notCovered = result.notCovered,
                        ),
                    )
                    return@withCurrentProject
                }
            }
            call.respond(HttpStatusCode.Conflict, ErrorResponse("The Look's rows kept changing under the press — try again", code = CODE_LOOK_ROWS_MOVED))
        }
    }
}

private const val LOOK_PRESS_ATTEMPTS = 3

/** 409 code: another write moved the Look's rows between every read and write of the press. */
internal const val CODE_LOOK_ROWS_MOVED = "LOOK_ROWS_MOVED"

/** The Look's rows as stored, uuids kept — the press's read and its compare. Must run inside a transaction. */
private fun DaoLook.storedRowDtos(): List<LookRowDto> = rowsInOrder().map {
    LookRowDto(
        targetType = it.targetType,
        targetKey = it.targetKey,
        attribute = it.attribute,
        value = it.value,
        fadeDurationMs = it.fadeDuration?.toMillis(),
        elementKey = it.elementKey,
        sortOrder = it.sortOrder,
        uuid = it.uuid.toString(),
    )
}

/** As the press reads a row: a cell's row by the cell's own key, as `LookRegistry.expand` lands it. */
private fun LookRowDto.asCueRow() = CueAttributeAssignmentDto(
    targetType = targetType,
    targetKey = elementKey ?: targetKey,
    attribute = attribute,
    value = value,
    fadeDurationMs = fadeDurationMs,
    sortOrder = sortOrder,
    uuid = uuid,
)

/** A row the press added, as a Look row: on a cell, under [parentOf] the cell's fixture. */
private fun CueAttributeAssignmentDto.asNewLookRow(parentOf: (String) -> String?): LookRowDto {
    val parent = if (targetType == TargetRef.Fixture.TYPE) parentOf(targetKey) else null
    return LookRowDto(
        targetType = targetType,
        targetKey = parent ?: targetKey,
        attribute = attribute,
        value = value,
        fadeDurationMs = fadeDurationMs,
        elementKey = if (parent != null) targetKey else null,
        sortOrder = sortOrder,
    )
}

/** The fixture [key] is a cell of, or null for a fixture or a key the rig lacks. */
private fun elementParentOf(state: State, key: String): String? =
    (runCatching { state.show.fixtures.untypedGroupableFixture(key) }.getOrNull() as? FixtureElement<*>)?.parentFixture?.key
