package uk.me.cormack.lighting7.routes

import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.request.receive
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.me.cormack.lighting7.fixture.dmx.DmxSlider
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fixture.GroupableFixture
import uk.me.cormack.lighting7.fixture.AttributeCategory
import uk.me.cormack.lighting7.fixture.group.FixtureElement
import uk.me.cormack.lighting7.fixture.trait.WithPosition
import uk.me.cormack.lighting7.fx.CueAssignmentResolver
import uk.me.cormack.lighting7.fx.PaletteResolver
import uk.me.cormack.lighting7.fx.ProgrammerOwner
import uk.me.cormack.lighting7.fx.ProgrammerWriter
import uk.me.cormack.lighting7.fx.AttributeChannelWriter
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.DaoFixturePatches
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.RigScope
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.show.AimAxis
import uk.me.cormack.lighting7.show.AimSolution
import uk.me.cormack.lighting7.show.JOG_DEFAULT_PLANE_Z
import uk.me.cormack.lighting7.show.SignedAim
import uk.me.cormack.lighting7.show.dmxToTravel
import uk.me.cormack.lighting7.show.jogAim
import uk.me.cormack.lighting7.show.RiggingPose
import uk.me.cormack.lighting7.show.StagePoint
import uk.me.cormack.lighting7.show.travelToDmx
import uk.me.cormack.lighting7.show.aimAt
import uk.me.cormack.lighting7.show.worldPosition
import uk.me.cormack.lighting7.state.State
import kotlin.math.roundToInt
import uk.me.cormack.lighting7.fx.groupHintsForTargets

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
            val outcome = when (request.mode?.trim()?.uppercase() ?: AIM_MODE_POINT) {
                AIM_MODE_POINT -> {
                    val (x, y, z) = Triple(request.x, request.y, request.z)
                    when {
                        request.targets.isEmpty() -> AimOutcome.Invalid("An aim needs fixtures to point", CODE_AIM_NEEDS_SELECTION)
                        x == null || y == null || z == null -> AimOutcome.Invalid("An aim at a point needs x, y and z")
                        else -> aimIntoProgrammer(state, project, request.targets, x, y, z, request.fadeMs, request.write)
                    }
                }
                AIM_MODE_JOG -> jogIntoProgrammer(state, project, request)
                else -> AimOutcome.Invalid("Mode '${request.mode}' is not one of [$AIM_MODE_POINT, $AIM_MODE_JOG]")
            }
            when (outcome) {
                is AimOutcome.Invalid ->
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(outcome.message, code = outcome.code))
                is AimOutcome.Done -> call.respond(outcome.response)
            }
        }
    }
}

private const val AIM_MODE_POINT = "POINT"
private const val AIM_MODE_JOG = "JOG"

@Resource("/{projectId}/programmer/aim")
internal data class ProgrammerAimResource(val projectId: String)

@Serializable
internal data class AimRequest(
    val targets: List<CueTargetDto> = emptyList(),
    /** `POINT` (absent): aim at [x], [y], [z]. `JOG`: move each head's landing point by [dx], [dy]. */
    val mode: String? = null,
    /** `POINT`: the point, metres, stage coordinates. */
    val x: Double? = null,
    val y: Double? = null,
    val z: Double? = null,
    val fadeMs: Long? = null,
    /** False resolves and answers without writing, for a host that holds a draft. */
    val write: Boolean = true,
    /** `JOG`: metres to move the landing points — +x audience-right (stage left), +y upstage. */
    val dx: Double? = null,
    val dy: Double? = null,
    /** `JOG`: the working plane's height, metres; absent is head height. */
    val planeZ: Double? = null,
    /**
     * `JOG`: where each head starts — the answer's own [AimResponse.base] sent back for the rest
     * of a gesture, or the row being edited. A head absent here is read by the desk.
     */
    val base: List<AimBaseDto>? = null,
    /** `JOG`: the previous step's answer, so each head's solve stays on the same side. */
    val near: List<AimNearDto>? = null,
    /**
     * `JOG`: the groups the heads were picked from — a fixture sheet's or a busk tab's — so a head in
     * one writes with it as its source group, as that pick's own writes do. Names no head of its own.
     */
    val groups: List<String>? = null,
)

@Serializable
internal data class AimResponse(
    val written: List<AimWriteDto> = emptyList(),
    val skipped: List<FanSkipDto> = emptyList(),
    /** `JOG`: where each head started — the gesture's anchor a later request sends back. */
    val base: List<AimBaseDto> = emptyList(),
)

/** A head's position as a jog reads it: its `"pan,tilt"` literal and, where it has them, its fine bytes. */
@Serializable
internal data class AimBaseDto(
    val target: CueTargetDto,
    val value: String,
    val panFine: Int? = null,
    val tiltFine: Int? = null,
)

/** A head's pan and tilt in travel degrees — an [AimWriteDto]'s, sent back. */
@Serializable
internal data class AimNearDto(val target: CueTargetDto, val panDeg: Double, val tiltDeg: Double)

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
    /**
     * [paletteValues] is each aimed head's position as the `pos:` intent that lands back on its
     * coarse bytes, by target key — what `aim_fixtures`' `saveAsPalette` stores. Never on the wire.
     */
    data class Done(val response: AimResponse, val paletteValues: Map<String, String> = emptyMap()) : AimOutcome
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
    val skipped = ArrayList<FanSkipDto>()
    val heads = headsOf(state, targets, skipped)

    val placements = aimPlacements(state, project, heads.keys)
    val point = StagePoint(x, y, z)
    val hints = groupHintsForTargets(fixtures, targets.mapNotNull { TargetRef.ofOrNull(it.type, it.key) })

    val writes = ArrayList<ProgrammerWriter.AttributeWrite>()
    val written = ArrayList<AimWriteDto>()
    val paletteValues = LinkedHashMap<String, String>()
    for ((key, head) in heads) {
        val target = CueTargetDto(TargetRef.Fixture.TYPE, key)
        when (val aim = aimHead(head, placements[key], point)) {
            is HeadAim.Skip -> skipped += FanSkipDto(target, aim.reason)
            is HeadAim.Aimed -> {
                val sourceGroup = hints[key]
                writes += ProgrammerWriter.AttributeWrite(head, "position", aim.position, sourceGroup)
                aim.fines.forEach { (name, value) -> writes += ProgrammerWriter.AttributeWrite(head, name, value, sourceGroup) }
                written += AimWriteDto(target, aim.position.serialize(), roundTenth(aim.panDeg), roundTenth(aim.tiltDeg))
                PaletteResolver.centreIntentOf(head, aim.position)?.let { paletteValues[key] = it.serialize() }
            }
        }
    }
    if (write && writes.isNotEmpty()) {
        state.show.fxEngine.programmer.writeAttributes(ProgrammerOwner.WEB, writes, fadeMs = fadeMs ?: 0)
    }
    return AimOutcome.Done(AimResponse(written, skipped), paletteValues)
}

/**
 * `mode = JOG`, the relative arm: each head's beam is landed on the working plane from where it
 * starts ([jogLanding]), that point is moved by `dx`, `dy` metres, and the head is aimed at the moved
 * point nearest where it was ([jogAim]). So a drag moves every landing point the same distance
 * across the stage, whatever each head's mount, yaw or declared front.
 *
 * A head starts from the request's `base` where it names one, else — unless `write` is false,
 * whose rows are another context's — from Local's own position, else from what the desk is
 * sending; the answer's `base` says where each started, fine bytes included, so a client sends it
 * back for the rest of the gesture and a drag is always measured from its start. `near` is the
 * previous step's answer, so each solve stays on the side the head is on.
 */
internal fun jogIntoProgrammer(state: State, project: DaoProject, request: AimRequest): AimOutcome {
    if (request.targets.isEmpty()) return AimOutcome.Invalid("A jog needs fixtures to move", CODE_AIM_NEEDS_SELECTION)
    val dx = request.dx
    val dy = request.dy
    if (dx == null || dy == null) return AimOutcome.Invalid("A jog needs dx and dy")
    for ((field, value) in listOf("dx" to dx, "dy" to dy, "planeZ" to request.planeZ)) {
        checkStageCoord(field, value)?.let { return AimOutcome.Invalid(it) }
    }
    if (request.fadeMs != null && request.fadeMs < 0) return AimOutcome.Invalid("fadeMs must not be negative")
    val given = jogStarts(request.base.orEmpty())
        .getOrElse { return AimOutcome.Invalid(it.message ?: "Bad base") }
    val near = jogNear(request.near.orEmpty()).getOrElse { return AimOutcome.Invalid(it.message ?: "Bad near") }
    val planeZ = request.planeZ ?: JOG_DEFAULT_PLANE_Z

    val fixtures = state.show.fixtures
    val skipped = ArrayList<FanSkipDto>()
    val heads = headsOf(state, request.targets, skipped)
    val placements = aimPlacements(state, project, heads.keys)
    val hints = groupHintsForTargets(
        fixtures,
        request.targets.mapNotNull { TargetRef.ofOrNull(it.type, it.key) } + request.groups.orEmpty().map(TargetRef::Group),
    )

    val writes = ArrayList<ProgrammerWriter.AttributeWrite>()
    val written = ArrayList<AimWriteDto>()
    val bases = ArrayList<AimBaseDto>()
    for ((key, head) in heads) {
        val target = CueTargetDto(TargetRef.Fixture.TYPE, key)
        val jog = jogHead(state, head, placements[key], given[key], readLocal = request.write, near[key], dx, dy, planeZ)
        jog.base?.let { bases += AimBaseDto(target, it.position.serialize(), it.panFine?.toInt(), it.tiltFine?.toInt()) }
        when (jog) {
            is HeadJog.Skip -> skipped += FanSkipDto(target, jog.reason)
            is HeadJog.Moved -> {
                writes += jog.attributeWrites(head, hints[key])
                written += AimWriteDto(target, jog.position.serialize(), roundTenth(jog.panDeg), roundTenth(jog.tiltDeg))
            }
        }
    }
    if (request.write && writes.isNotEmpty()) {
        state.show.fxEngine.programmer.writeAttributes(ProgrammerOwner.WEB, writes, fadeMs = request.fadeMs ?: 0)
    }
    return AimOutcome.Done(AimResponse(written, skipped, bases))
}

/** One head's step of a jog or a stage fan: where it started, once known, and where it went or why not. */
internal sealed interface HeadJog {
    val base: JogStart?

    data class Skip(val reason: String, override val base: JogStart? = null) : HeadJog

    data class Moved(
        override val base: JogStart,
        val position: CueAssignmentResolver.AttributeValue.Position,
        /** Fine pan / tilt, by property name, on a head that has them. */
        val fines: List<Pair<String, CueAssignmentResolver.AttributeValue.Slider>>,
        val panDeg: Double,
        val tiltDeg: Double,
    ) : HeadJog {
        fun attributeWrites(head: GroupableFixture, sourceGroup: String?): List<ProgrammerWriter.AttributeWrite> =
            listOf(ProgrammerWriter.AttributeWrite(head, "position", position, sourceGroup)) +
                fines.map { (name, value) -> ProgrammerWriter.AttributeWrite(head, name, value, sourceGroup) }
    }
}

/**
 * [head]'s landing point on the plane at [planeZ] moved by [dx], [dy] metres, and the head aimed at
 * it ([jogAim]): from [given] where the request names a start, else — where [readLocal] — from
 * Local, else from what the desk is sending; nearest [near], the gesture's previous step.
 */
internal fun jogHead(
    state: State,
    head: GroupableFixture,
    placement: AimPlacement?,
    given: JogStart?,
    readLocal: Boolean,
    near: AimNearDto?,
    dx: Double,
    dy: Double,
    planeZ: Double,
): HeadJog {
    val aimable = when (val a = aimableHead(head, placement)) {
        is HeadAim.Skip -> return HeadJog.Skip(a.reason)
        is AimableHead -> a
    }
    val start = given ?: readJogStart(state, aimable, readLocal) ?: return HeadJog.Skip("no position to jog from")
    val base = SignedAim(
        dmxToTravel(start.position.pan, start.panFine, aimable.pan, aimable.panSlider) - aimable.panAxis.zeroDeg,
        dmxToTravel(start.position.tilt, start.tiltFine, aimable.tilt, aimable.tiltSlider) - aimable.tiltAxis.zeroDeg,
    )
    val previous = near?.let { SignedAim(it.panDeg - aimable.panAxis.zeroDeg, it.tiltDeg - aimable.tiltAxis.zeroDeg) }
    val p = aimable.placement
    val jog = jogAim(
        aimable.from, p.baseYawDeg, p.basePitchDeg, base, dx, dy, planeZ,
        aimable.panAxis, aimable.tiltAxis, previous, p.baseRollDeg,
    )
    return when (val aim = aimable.aimed(jog.solution)) {
        is HeadAim.Skip -> HeadJog.Skip(aim.reason, start)
        is HeadAim.Aimed -> HeadJog.Moved(start, aim.position, aim.fines, aim.panDeg, aim.tiltDeg)
    }
}

/** A request's starts by head key, or the reason one is not a start. */
internal fun jogStarts(entries: List<AimBaseDto>): Result<Map<String, JogStart>> = runCatching {
    entries.associate { entry ->
        entry.target.key to requireNotNull(jogStartOf(entry)) {
            "base for '${entry.target.key}' (${entry.value}) is not a pan,tilt position with fine bytes 0..255"
        }
    }
}

/** A request's previous step by head key, or the reason it is not one. */
internal fun jogNear(entries: List<AimNearDto>): Result<Map<String, AimNearDto>> = runCatching {
    entries.associateBy { entry ->
        require(entry.panDeg.isFinite() && entry.tiltDeg.isFinite()) { "near for '${entry.target.key}' must be finite degrees" }
        entry.target.key
    }
}

/** Where a jogged head starts: its coarse position and, where known, its fine bytes. */
internal data class JogStart(
    val position: CueAssignmentResolver.AttributeValue.Position,
    val panFine: UByte?,
    val tiltFine: UByte?,
)

private fun jogStartOf(entry: AimBaseDto): JogStart? {
    val position = CueAssignmentResolver.parseAssignmentValue(AttributeCategory.PAN, "position", entry.value)
        as? CueAssignmentResolver.AttributeValue.Position ?: return null
    fun fine(value: Int?): UByte? = value?.takeIf { it in 0..255 }?.toUByte()
    if (entry.panFine != null && fine(entry.panFine) == null) return null
    if (entry.tiltFine != null && fine(entry.tiltFine) == null) return null
    return JogStart(position, fine(entry.panFine), fine(entry.tiltFine))
}

/**
 * A head's starting position, axis by axis: Local's where [readLocal] and Local holds the axis — its
 * fine entry with it, else the fine the desk is sending when the coarse it is sending is Local's — else
 * what the desk is sending on the coarse and fine channels. Null when there is nothing to read.
 */
private fun readJogStart(state: State, head: AimableHead, readLocal: Boolean): JogStart? {
    val store = state.show.programmerStore
    val local = if (readLocal) localPosition(store, head.head) else null
    fun read(slider: DmxSlider): UByte? = state.show.fixtures.controllerOrNull(slider.universe)?.getValue(slider.channelNo)
    fun localFine(fine: Pair<String, DmxSlider>?): UByte? =
        fine?.let { (store.get(head.head.targetKey, it.first)?.value?.resolved as? CueAssignmentResolver.AttributeValue.Slider)?.value }
    fun axis(localCoarse: UByte?, coarse: DmxSlider, fine: Pair<String, DmxSlider>?): Pair<UByte, UByte?>? =
        jogAxisStart(localCoarse, localFine(fine), read(coarse), fine?.second?.let(::read))
    val (pan, panFine) = axis(local?.pan, head.panSlider, head.panFine) ?: return null
    val (tilt, tiltFine) = axis(local?.tilt, head.tiltSlider, head.tiltFine) ?: return null
    return JogStart(CueAssignmentResolver.AttributeValue.Position(pan, tilt), panFine, tiltFine)
}

/**
 * One axis's start for [readJogStart]: Local's coarse with Local's fine, else with the fine being
 * sent while the coarse being sent is Local's (a coarse-only Local value over a cue's fine), else
 * what is being sent. Null when nothing is.
 */
internal fun jogAxisStart(localCoarse: UByte?, localFine: UByte?, sentCoarse: UByte?, sentFine: UByte?): Pair<UByte, UByte?>? {
    if (localCoarse == null) return sentCoarse?.let { it to sentFine }
    return localCoarse to (localFine ?: sentFine?.takeIf { sentCoarse == localCoarse })
}

/**
 * The heads [targets] name — a group to its members in member order, a fixture to itself, distinct
 * by key — with a target that names nothing patched added to [skipped] as `not patched`. Shared by
 * aim and focus.
 */
internal fun headsOf(state: State, targets: List<CueTargetDto>, skipped: MutableList<FanSkipDto>): LinkedHashMap<String, GroupableFixture> {
    val fixtures = state.show.fixtures
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
            skipped += FanSkipDto(target, "not patched")
            continue
        }
        for (member in members) heads.putIfAbsent(member.targetKey, member)
    }
    return heads
}

/** Where a patched fixture is and how its body is turned. [world] is null when it is unplaced. */
internal data class AimPlacement(
    val world: StagePoint?,
    val baseYawDeg: Double?,
    val basePitchDeg: Double?,
    val baseRollDeg: Double?,
)

/** The placements of the patches keyed [keys] in [project], composed through their riggings. */
internal fun aimPlacements(state: State, project: DaoProject, keys: Collection<String>): Map<String, AimPlacement> {
    if (keys.isEmpty()) return emptyMap()
    return transaction(state.database) {
        val rig = RigScope.of(project)
        val poses = rig.riggings().associate { r ->
            r.id.value to RiggingPose(r.positionX, r.positionY, r.positionZ, r.yawDeg, r.pitchDeg, r.rollDeg)
        }
        // Where each head is as the rig sees it: a venue patch's Position override is where it hangs.
        rig.effective(rig.patches(DaoFixturePatches.key inList keys))
            .associate { p ->
                val riggingId = p.rigging?.id?.value
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
    data class Skip(val reason: String) : HeadAim, Aimability
    data class Aimed(
        val position: CueAssignmentResolver.AttributeValue.Position,
        /** Fine pan / tilt, by property name, on a head that has them. */
        val fines: List<Pair<String, CueAssignmentResolver.AttributeValue.Slider>>,
        val panDeg: Double,
        val tiltDeg: Double,
    ) : HeadAim
}

/** A head as something to aim ([AimableHead]), or why it cannot be aimed ([HeadAim.Skip]). */
private sealed interface Aimability

/** A head [aimAt] can solve for: its axes, their DMX, and where it hangs. */
private class AimableHead(
    val head: Fixture,
    val pan: Fixture.Attribute,
    val tilt: Fixture.Attribute,
    val panSlider: DmxSlider,
    val tiltSlider: DmxSlider,
    val panAxis: AimAxis,
    val tiltAxis: AimAxis,
    val from: StagePoint,
    val placement: AimPlacement,
) : Aimability {
    val panFine = fineSlider(head, AttributeCategory.PAN_FINE)
    val tiltFine = fineSlider(head, AttributeCategory.TILT_FINE)
}

/** Why [head] can never be aimed from [placement] — a cell, a fixed head, no degrees, unplaced — or null. */
internal fun aimSkipReason(head: GroupableFixture, placement: AimPlacement?): String? =
    (aimableHead(head, placement) as? HeadAim.Skip)?.reason

private fun aimableHead(head: GroupableFixture, placement: AimPlacement?): Aimability {
    if (head is FixtureElement<*>) return HeadAim.Skip("an element has no placement of its own — aim its fixture")
    if (head !is Fixture || head !is WithPosition) return HeadAim.Skip("fixed head — no pan or tilt")
    val catalogue = head.fixtureAttributes
    val pan = catalogue.firstOrNull { it.category == AttributeCategory.PAN }
    val tilt = catalogue.firstOrNull { it.category == AttributeCategory.TILT }
    val panSlider = head.pan as? DmxSlider
    val tiltSlider = head.tilt as? DmxSlider
    if (pan == null || tilt == null || panSlider == null || tiltSlider == null) {
        return HeadAim.Skip("position is not DMX-backed")
    }
    val panAxis = aimAxis(pan) ?: return HeadAim.Skip("pan has no degree range annotated")
    val tiltAxis = aimAxis(tilt) ?: return HeadAim.Skip("tilt has no degree range annotated")
    val from = placement?.world ?: return HeadAim.Skip("not placed on the stage")
    return AimableHead(head, pan, tilt, panSlider, tiltSlider, panAxis, tiltAxis, from, placement)
}

private fun aimHead(head: GroupableFixture, placement: AimPlacement?, point: StagePoint): HeadAim =
    when (val aimable = aimableHead(head, placement)) {
        is HeadAim.Skip -> aimable
        is AimableHead -> with(aimable.placement) {
            aimable.aimed(aimAt(aimable.from, baseYawDeg, basePitchDeg, point, aimable.panAxis, aimable.tiltAxis, baseRollDeg))
        }
    }

/** A solution as the writes and the answer for it, or the skip that says why there is none. */
private fun AimableHead.aimed(solution: AimSolution): HeadAim = when (solution) {
    AimSolution.AtFixture -> HeadAim.Skip("the point is where the fixture is")
    is AimSolution.OutOfReach -> HeadAim.Skip(
        "out of reach — needs pan ${formatSigned(solution.panSignedDeg)}° and tilt ${formatSigned(solution.tiltSignedDeg)}° " +
            "from centre; travel is ±${formatTenth(panAxis.halfSpanDeg)}° pan and ±${formatTenth(tiltAxis.halfSpanDeg)}° tilt",
    )
    is AimSolution.Aimed -> {
        val panDmx = travelToDmx(solution.panDeg, pan, panSlider, panFine?.second)
        val tiltDmx = travelToDmx(solution.tiltDeg, tilt, tiltSlider, tiltFine?.second)
        HeadAim.Aimed(
            CueAssignmentResolver.AttributeValue.Position(panDmx.coarse, tiltDmx.coarse),
            buildList {
                if (panFine != null && panDmx.fine != null) add(panFine.first to CueAssignmentResolver.AttributeValue.Slider(panDmx.fine))
                if (tiltFine != null && tiltDmx.fine != null) add(tiltFine.first to CueAssignmentResolver.AttributeValue.Slider(tiltDmx.fine))
            },
            solution.panDeg,
            solution.tiltDeg,
        )
    }
}

private fun aimAxis(attribute: Fixture.Attribute): AimAxis? {
    val min = attribute.degMin ?: return null
    val max = attribute.degMax ?: return null
    return if (min == max) null else AimAxis(min, max, attribute.forwardDeg)
}

/** The head's fine channel for [category], by property name, when it has a DMX-backed one. */
private fun fineSlider(head: Fixture, category: AttributeCategory): Pair<String, DmxSlider>? {
    val attribute = head.fixtureAttributes.firstOrNull { it.category == category } ?: return null
    val slider = AttributeChannelWriter.resolveAttribute(head, attribute.name)?.value as? DmxSlider ?: return null
    return attribute.name to slider
}

private fun roundTenth(value: Double): Double = (value * 10).roundToInt() / 10.0

private fun formatTenth(value: Double): String {
    val rounded = roundTenth(value)
    return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
}

private fun formatSigned(value: Double): String = (if (value >= 0) "+" else "") + formatTenth(value)
