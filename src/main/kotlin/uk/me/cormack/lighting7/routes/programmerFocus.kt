package uk.me.cormack.lighting7.routes

import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.request.receive
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.fixture.BodyArchetype
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fixture.FixtureTypeRegistry
import uk.me.cormack.lighting7.fixture.GroupableFixture
import uk.me.cormack.lighting7.fixture.PropertyCategory
import uk.me.cormack.lighting7.fixture.dmx.DmxSlider
import uk.me.cormack.lighting7.fixture.group.FixtureElement
import uk.me.cormack.lighting7.fx.CueAssignmentResolver
import uk.me.cormack.lighting7.fx.ProgrammerOwner
import uk.me.cormack.lighting7.fx.ProgrammerWriter
import uk.me.cormack.lighting7.fx.PropertyChannelWriter
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.show.StagePoint
import uk.me.cormack.lighting7.show.MoverLens
import uk.me.cormack.lighting7.show.focusRange
import uk.me.cormack.lighting7.show.lensDistance
import uk.me.cormack.lighting7.state.State
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** 400 code: the point is missing or not a stage coordinate — [checkStageCoord]'s bound, as aim's. */
internal const val CODE_FOCUS_INVALID = "FOCUS_INVALID"

/** 400 code: no targets — a focus has nothing to focus. */
internal const val CODE_FOCUS_NEEDS_SELECTION = "FOCUS_NEEDS_SELECTION"

/**
 * `POST /projects/{id}/programmer/focus` — *Focus here* (fixture-optics plan D11): focus heads on a
 * point on the stage, resolved on the desk. A stage coordinate in, one focus level per head out,
 * written into the programmer as ordinary Local entries (owner `WEB`), as an aim's are — so Record
 * captures it, Blind previews it and Clear releases it.
 *
 * Each head's distance to the point is measured from its **lens** as the Stage view draws it — its
 * placement composed through its rigging ([worldPosition], aim's own maths), up its body's axis to
 * the head's pivot and along the beam to the lens ([lensDistance], [MoverLens]: the view's mover
 * proportions) — and turned into DMX through its FOCUS slider's declared range, the inverse of the
 * view's `resolveDeclaredFocusDistance` ([uk.me.cormack.lighting7.show.FocusRange.levelFor]). So a
 * head focused here is drawn sharp at the point at a 3 m throw as at a 24 m one. A type whose body
 * declares no mover head is measured from its placement point; no focus type in the library is one
 * (`FixtureFocusTest` holds them to it).
 *
 * Everything that cannot be focused is skipped by name: a head with no focus channel, one whose
 * focus declares no range, an unplaced fixture, a cell (it has no placement of its own), a point
 * outside the head's range. [FocusRequest.write] false answers without writing, as Spread's does.
 */
internal fun Route.routeApiRestProgrammerFocus(state: State) {
    post<ProgrammerFocusResource> { resource ->
        withCurrentProject(state, resource.projectId) { project ->
            val request = call.receive<FocusRequest>()
            when (val outcome = focusIntoProgrammer(state, project, request.targets, request.point, request.fadeMs, request.write)) {
                is FocusOutcome.Invalid ->
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(outcome.message, code = outcome.code))
                is FocusOutcome.Done -> call.respond(outcome.response)
            }
        }
    }
}

@Resource("/{projectId}/programmer/focus")
internal data class ProgrammerFocusResource(val projectId: String)

/** A point on the stage, metres, stage coordinates (x audience-right, y upstage, z up). */
@Serializable
internal data class FocusPointDto(val x: Double, val y: Double, val z: Double)

@Serializable
internal data class FocusRequest(
    val targets: List<CueTargetDto> = emptyList(),
    /** Null is refused, by name; it is nullable so a missing point is a 400 that says so. */
    val point: FocusPointDto? = null,
    val fadeMs: Long? = null,
    /** False resolves and answers everything and writes nothing. */
    val write: Boolean = true,
)

@Serializable
internal data class FocusResponse(
    val written: List<FocusWriteDto> = emptyList(),
    val skipped: List<SpreadSkipDto> = emptyList(),
)

/**
 * One head's focus: its focus slider's literal (`"0".."255"`, what a Look row or a programmer entry
 * holds) and the distance it was solved for, metres to the centimetre.
 */
@Serializable
internal data class FocusWriteDto(
    val target: CueTargetDto,
    val value: String,
    val distanceM: Double,
)

internal sealed interface FocusOutcome {
    data class Invalid(val message: String, val code: String = CODE_FOCUS_INVALID) : FocusOutcome
    data class Done(val response: FocusResponse) : FocusOutcome
}

/**
 * The body of the route, shared with `aim_fixtures`' `focus: true` and separable so a test can
 * drive it without HTTP. [write] false is the route's `write: false` and the tool's `dryRun`.
 */
internal fun focusIntoProgrammer(
    state: State,
    project: DaoProject,
    targets: List<CueTargetDto>,
    point: FocusPointDto?,
    fadeMs: Long?,
    write: Boolean = true,
): FocusOutcome {
    // No selection first, as aim's: it is the more fundamental refusal, whatever else is wrong.
    if (targets.isEmpty()) return FocusOutcome.Invalid("A focus needs fixtures to focus", CODE_FOCUS_NEEDS_SELECTION)
    if (point == null) return FocusOutcome.Invalid("A focus needs a point: {x, y, z} in metres")
    for ((axis, value) in listOf("x" to point.x, "y" to point.y, "z" to point.z)) {
        checkStageCoord(axis, value)?.let { return FocusOutcome.Invalid(it) }
    }
    if (fadeMs != null && fadeMs < 0) return FocusOutcome.Invalid("fadeMs must not be negative")

    val skipped = ArrayList<SpreadSkipDto>()
    val heads = headsOf(state, targets, skipped)
    val placements = aimPlacements(state, project, heads.keys)
    val hints = groupHintsForTargets(state.show.fixtures, targets.mapNotNull { TargetRef.ofOrNull(it.type, it.key) })
    val at = StagePoint(point.x, point.y, point.z)

    val writes = ArrayList<ProgrammerWriter.PropertyWrite>()
    val written = ArrayList<FocusWriteDto>()
    for ((key, head) in heads) {
        val target = CueTargetDto(TargetRef.Fixture.TYPE, key)
        when (val focus = focusHead(head, placements[key], at)) {
            is HeadFocus.Skip -> skipped += SpreadSkipDto(target, focus.reason)
            is HeadFocus.Focused -> {
                writes += ProgrammerWriter.PropertyWrite(head, focus.propertyName, focus.value, hints[key])
                written += FocusWriteDto(target, focus.value.serialize(), roundCentimetre(focus.distanceM))
            }
        }
    }
    if (write && writes.isNotEmpty()) {
        state.show.fxEngine.programmer.writeProperties(ProgrammerOwner.WEB, writes, fadeMs = fadeMs ?: 0)
    }
    return FocusOutcome.Done(FocusResponse(written, skipped))
}

internal sealed interface HeadFocus {
    data class Skip(val reason: String) : HeadFocus
    data class Focused(
        val propertyName: String,
        val value: CueAssignmentResolver.PropertyValue.Slider,
        val distanceM: Double,
    ) : HeadFocus
}

/**
 * One head's focus on [point] from [placement] (null, or a null `world`, when unplaced). Internal so
 * a test can hand it a head no patch can name — a focus with no declared range.
 */
internal fun focusHead(head: GroupableFixture, placement: AimPlacement?, point: StagePoint): HeadFocus {
    if (head is FixtureElement<*>) return HeadFocus.Skip("a cell has no placement of its own — focus its fixture")
    if (head !is Fixture) return HeadFocus.Skip("no focus channel")
    val property = head.fixtureProperties.firstOrNull { it.category == PropertyCategory.FOCUS && it.fineOf == null }
        ?: return HeadFocus.Skip("no focus channel")
    val slider = PropertyChannelWriter.resolveProperty(head, property.name)?.value as? DmxSlider
        ?: return HeadFocus.Skip("focus is not DMX-backed")
    val range = property.focusRange(slider) ?: return HeadFocus.Skip("focus declares no range")
    val from = placement?.world ?: return HeadFocus.Skip("not placed on the stage")

    val distance = distanceFromLens(head, placement, from, point)
    val dmx = range.dmxFor(distance) ?: return HeadFocus.Skip(
        "out of focus range — the point is ${formatMetres(distance)} m away; " +
            "focus reaches ${formatMetres(range.nearM)}–${formatMetres(range.farM)} m",
    )
    return HeadFocus.Focused(property.name, CueAssignmentResolver.PropertyValue.Slider(dmx.toUByte()), distance)
}

/**
 * How far [point] is from [head]'s lens: [lensDistance] for a type whose body declares a mover head
 * (every focus type in the library does), else from the placement point [from].
 */
private fun distanceFromLens(head: Fixture, placement: AimPlacement, from: StagePoint, point: StagePoint): Double {
    val info = FixtureTypeRegistry.typeInfoForKey(head.typeKey)
    val body = info?.body
    val moverHead = body?.head
    if (body?.archetype == BodyArchetype.MOVER && moverHead != null) {
        return lensDistance(from, placement.baseYawDeg, placement.basePitchDeg, placement.baseRollDeg, MoverLens.of(info.heightM, moverHead), point)
    }
    val dx = point.x - from.x
    val dy = point.y - from.y
    val dz = point.z - from.z
    return sqrt(dx * dx + dy * dy + dz * dz)
}

private fun roundCentimetre(value: Double): Double = (value * 100).roundToInt() / 100.0

private fun formatMetres(value: Double): String {
    val rounded = (value * 10).roundToInt() / 10.0
    return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
}
