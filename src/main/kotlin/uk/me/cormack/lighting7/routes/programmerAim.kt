package uk.me.cormack.lighting7.routes

import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.request.receive
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.me.cormack.lighting7.fixture.dmx.DmxSlider
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fixture.GroupableFixture
import uk.me.cormack.lighting7.fixture.PropertyCategory
import uk.me.cormack.lighting7.fixture.group.FixtureElement
import uk.me.cormack.lighting7.fixture.trait.WithPosition
import uk.me.cormack.lighting7.fx.CueAssignmentResolver
import uk.me.cormack.lighting7.fx.ProgrammerOwner
import uk.me.cormack.lighting7.fx.ProgrammerWriter
import uk.me.cormack.lighting7.fx.PropertyChannelWriter
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.DaoFixturePatch
import uk.me.cormack.lighting7.models.DaoFixturePatches
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoRigging
import uk.me.cormack.lighting7.models.DaoRiggings
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.show.AimAxis
import uk.me.cormack.lighting7.show.AimSolution
import uk.me.cormack.lighting7.show.RiggingPose
import uk.me.cormack.lighting7.show.StagePoint
import uk.me.cormack.lighting7.show.aimAt
import uk.me.cormack.lighting7.show.worldPosition
import uk.me.cormack.lighting7.state.State
import kotlin.math.roundToInt

/**
 * 400 code: the point is not a stage coordinate — refused by [checkStageCoord], the bound a patch's
 * own placement is validated to, so any point a fixture could hang at is aimable.
 */
internal const val CODE_AIM_INVALID = "AIM_INVALID"

/** 400 code: no targets — an aim has nothing to point. */
internal const val CODE_AIM_NEEDS_SELECTION = "AIM_NEEDS_SELECTION"

/**
 * `POST /projects/{id}/programmer/aim` — point moving heads at a spot on the stage, resolved on
 * the desk: a stage coordinate in, one pan/tilt per head out, written into the programmer as
 * ordinary Local entries (owner `WEB`), the way a spread's are. So Record captures it, Blind
 * previews it, Clear releases it, and `programmer.entryChanged` carries it to every window.
 *
 * Each head is aimed from where the Stage view draws it — its placement composed through its
 * rigging ([worldPosition]) — through its base orientation and its pan/tilt's annotated degree
 * range, by [aimAt]. A head with fine pan/tilt channels gets them too, so the aim is 16-bit rather
 * than one coarse step (about 2° on a 540° pan). Everything that cannot be aimed is skipped by
 * name, never guessed at: a fixed head, an axis with no degree range, an unplaced fixture, a cell
 * (it has no placement of its own), a point outside the head's travel.
 *
 * The point is metres in stage coordinates (x audience-right, y upstage, z up — see
 * `docs/fixtures-engineering.md` §"Coordinate system (FOH-relative)").
 */
internal fun Route.routeApiRestProgrammerAim(state: State) {
    post<ProgrammerAimResource> { resource ->
        withCurrentProject(state, resource.projectId) { project ->
            val request = call.receive<AimRequest>()
            when (val outcome = aimIntoProgrammer(state, project, request.targets, request.x, request.y, request.z, request.fadeMs)) {
                is AimOutcome.Invalid ->
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(outcome.message, code = outcome.code))
                is AimOutcome.Done -> call.respond(outcome.response)
            }
        }
    }
}

@Resource("/{projectId}/programmer/aim")
internal data class ProgrammerAimResource(val projectId: String)

@Serializable
internal data class AimRequest(
    val targets: List<CueTargetDto> = emptyList(),
    /** The point, metres, stage coordinates. */
    val x: Double,
    val y: Double,
    val z: Double,
    val fadeMs: Long? = null,
)

@Serializable
internal data class AimResponse(
    val written: List<AimWriteDto> = emptyList(),
    val skipped: List<SpreadSkipDto> = emptyList(),
)

/**
 * One head's aim: its `position` literal (`"pan,tilt"`, coarse DMX — what a Look row or a
 * programmer entry holds) and the travel degrees it came from, rounded to a tenth.
 */
@Serializable
internal data class AimWriteDto(
    val target: CueTargetDto,
    val value: String,
    val panDeg: Double,
    val tiltDeg: Double,
)

internal sealed interface AimOutcome {
    data class Invalid(val message: String, val code: String = CODE_AIM_INVALID) : AimOutcome
    data class Done(val response: AimResponse) : AimOutcome
}

/**
 * The body of the route, shared with the `aim_fixtures` tool and separable so a test can drive it
 * without HTTP. [write] false resolves and answers everything and writes nothing — the tool's
 * `dryRun`.
 */
internal fun aimIntoProgrammer(
    state: State,
    project: DaoProject,
    targets: List<CueTargetDto>,
    x: Double,
    y: Double,
    z: Double,
    fadeMs: Long?,
    write: Boolean = true,
): AimOutcome {
    // No selection first: it is the more fundamental refusal, whatever else is wrong.
    if (targets.isEmpty()) return AimOutcome.Invalid("An aim needs fixtures to point", CODE_AIM_NEEDS_SELECTION)
    for ((axis, value) in listOf("x" to x, "y" to y, "z" to z)) {
        checkStageCoord(axis, value)?.let { return AimOutcome.Invalid(it) }
    }
    if (fadeMs != null && fadeMs < 0) return AimOutcome.Invalid("fadeMs must not be negative")

    val fixtures = state.show.fixtures
    val skipped = ArrayList<SpreadSkipDto>()

    // A group to its members in member order, a fixture to itself. Distinct by key.
    val heads = LinkedHashMap<String, GroupableFixture>()
    for (target in targets) {
        val members: List<GroupableFixture>? = runCatching {
            when (val ref = TargetRef.ofOrNull(target.type, target.key)) {
                is TargetRef.Group -> fixtures.untypedGroup(ref.key).fixtures
                is TargetRef.Fixture -> listOf(fixtures.untypedGroupableFixture(ref.key))
                null -> null
            }
        }.getOrNull()
        if (members == null) {
            skipped += SpreadSkipDto(target, "not patched")
            continue
        }
        for (member in members) heads.putIfAbsent(member.targetKey, member)
    }

    val placements = aimPlacements(state, project, heads.keys)
    val point = StagePoint(x, y, z)
    val hints = groupHintsForTargets(fixtures, targets.mapNotNull { TargetRef.ofOrNull(it.type, it.key) })

    val writes = ArrayList<ProgrammerWriter.PropertyWrite>()
    val written = ArrayList<AimWriteDto>()
    for ((key, head) in heads) {
        val target = CueTargetDto(TargetRef.Fixture.TYPE, key)
        when (val aim = aimHead(head, placements[key], point)) {
            is HeadAim.Skip -> skipped += SpreadSkipDto(target, aim.reason)
            is HeadAim.Aimed -> {
                val sourceGroup = hints[key]
                writes += ProgrammerWriter.PropertyWrite(head, "position", aim.position, sourceGroup)
                aim.fines.forEach { (name, value) -> writes += ProgrammerWriter.PropertyWrite(head, name, value, sourceGroup) }
                written += AimWriteDto(target, aim.position.serialize(), roundTenth(aim.panDeg), roundTenth(aim.tiltDeg))
            }
        }
    }
    if (write && writes.isNotEmpty()) {
        state.show.fxEngine.programmer.writeProperties(ProgrammerOwner.WEB, writes, fadeMs = fadeMs ?: 0)
    }
    return AimOutcome.Done(AimResponse(written, skipped))
}

/** Where a patched fixture is and how its body is turned. [world] is null when it is unplaced. */
private data class AimPlacement(
    val world: StagePoint?,
    val baseYawDeg: Double?,
    val basePitchDeg: Double?,
    val baseRollDeg: Double?,
)

/** The placements of the patches keyed [keys] in [project], composed through their riggings. */
private fun aimPlacements(state: State, project: DaoProject, keys: Collection<String>): Map<String, AimPlacement> {
    if (keys.isEmpty()) return emptyMap()
    return transaction(state.database) {
        val poses = DaoRigging.find { DaoRiggings.project eq project.id }.associate { r ->
            r.id.value to RiggingPose(r.positionX, r.positionY, r.positionZ, r.yawDeg, r.pitchDeg, r.rollDeg)
        }
        DaoFixturePatch.find { (DaoFixturePatches.project eq project.id) and (DaoFixturePatches.key inList keys) }
            .associate { p ->
                val riggingId = p.readValues.getOrNull(DaoFixturePatches.rigging)?.value
                p.key to AimPlacement(
                    worldPosition(p.stageX, p.stageY, p.stageZ, riggingId?.let { poses[it] }),
                    p.baseYawDeg,
                    p.basePitchDeg,
                    p.baseRollDeg,
                )
            }
    }
}

private sealed interface HeadAim {
    data class Skip(val reason: String) : HeadAim
    data class Aimed(
        val position: CueAssignmentResolver.PropertyValue.Position,
        /** Fine pan / tilt, by property name, on a head that has them. */
        val fines: List<Pair<String, CueAssignmentResolver.PropertyValue.Slider>>,
        val panDeg: Double,
        val tiltDeg: Double,
    ) : HeadAim
}

private fun aimHead(head: GroupableFixture, placement: AimPlacement?, point: StagePoint): HeadAim {
    if (head is FixtureElement<*>) return HeadAim.Skip("a cell has no placement of its own — aim its fixture")
    if (head !is Fixture || head !is WithPosition) return HeadAim.Skip("fixed head — no pan or tilt")
    val catalogue = head.fixtureProperties
    val pan = catalogue.firstOrNull { it.category == PropertyCategory.PAN }
    val tilt = catalogue.firstOrNull { it.category == PropertyCategory.TILT }
    val panSlider = head.pan as? DmxSlider
    val tiltSlider = head.tilt as? DmxSlider
    if (pan == null || tilt == null || panSlider == null || tiltSlider == null) {
        return HeadAim.Skip("position is not DMX-backed")
    }
    val panAxis = aimAxis(pan) ?: return HeadAim.Skip("pan has no degree range annotated")
    val tiltAxis = aimAxis(tilt) ?: return HeadAim.Skip("tilt has no degree range annotated")
    val from = placement?.world ?: return HeadAim.Skip("not placed on the stage")

    return when (val solution = aimAt(from, placement.baseYawDeg, placement.basePitchDeg, point, panAxis, tiltAxis, placement.baseRollDeg)) {
        AimSolution.AtFixture -> HeadAim.Skip("the point is where the fixture is")
        is AimSolution.OutOfReach -> HeadAim.Skip(
            "out of reach — needs pan ${formatSigned(solution.panSignedDeg)}° and tilt ${formatSigned(solution.tiltSignedDeg)}° " +
                "from centre; travel is ±${formatTenth(panAxis.halfSpanDeg)}° pan and ±${formatTenth(tiltAxis.halfSpanDeg)}° tilt",
        )
        is AimSolution.Aimed -> {
            val panFine = fineSlider(head, PropertyCategory.PAN_FINE)
            val tiltFine = fineSlider(head, PropertyCategory.TILT_FINE)
            val panDmx = travelToDmx(solution.panDeg, pan, panSlider, panFine?.second)
            val tiltDmx = travelToDmx(solution.tiltDeg, tilt, tiltSlider, tiltFine?.second)
            HeadAim.Aimed(
                CueAssignmentResolver.PropertyValue.Position(panDmx.coarse, tiltDmx.coarse),
                buildList {
                    if (panFine != null && panDmx.fine != null) add(panFine.first to CueAssignmentResolver.PropertyValue.Slider(panDmx.fine))
                    if (tiltFine != null && tiltDmx.fine != null) add(tiltFine.first to CueAssignmentResolver.PropertyValue.Slider(tiltDmx.fine))
                },
                solution.panDeg,
                solution.tiltDeg,
            )
        }
    }
}

private fun aimAxis(property: Fixture.Property): AimAxis? {
    val min = property.degMin ?: return null
    val max = property.degMax ?: return null
    return if (min == max) null else AimAxis(min, max)
}

/** The head's fine channel for [category], by property name, when it has a DMX-backed one. */
private fun fineSlider(head: Fixture, category: PropertyCategory): Pair<String, DmxSlider>? {
    val property = head.fixtureProperties.firstOrNull { it.category == category } ?: return null
    val slider = PropertyChannelWriter.resolveProperty(head, property.name)?.value as? DmxSlider ?: return null
    return property.name to slider
}

internal data class AxisDmx(val coarse: UByte, val fine: UByte?)

/**
 * A travel-degree position to its axis's DMX: the same mapping as the position template's
 * (`TemplateResolver.degreesToDmx`, and the Stage view's `degreesToDmx`) — the fraction along the
 * annotated range, flipped for an inverted axis, laid over the coarse slider's own `min..max` —
 * carried to 16 bits when [fine] is given, as the Stage view reads it back (`coarse + fine / 256`).
 */
internal fun travelToDmx(degrees: Double, property: Fixture.Property, coarse: DmxSlider, fine: DmxSlider?): AxisDmx {
    val degMin = property.degMin!!
    val degMax = property.degMax!!
    val fraction = ((degrees - degMin) / (degMax - degMin)).coerceIn(0.0, 1.0)
    val effective = if (property.inverted) 1.0 - fraction else fraction
    val min = coarse.min.toInt()
    val max = coarse.max.toInt()
    val exact = min + effective * (max - min)
    if (fine == null) return AxisDmx(exact.roundToInt().coerceIn(min, max).toUByte(), null)
    val sixteen = (exact * 256).roundToInt().coerceIn(min * 256, max * 256)
    val fineValue = (sixteen % 256).coerceIn(fine.min.toInt(), fine.max.toInt())
    return AxisDmx((sixteen / 256).toUByte(), fineValue.toUByte())
}

private fun roundTenth(value: Double): Double = (value * 10).roundToInt() / 10.0

private fun formatTenth(value: Double): String {
    val rounded = roundTenth(value)
    return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
}

private fun formatSigned(value: Double): String = (if (value >= 0) "+" else "") + formatTenth(value)
