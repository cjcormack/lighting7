package uk.me.cormack.lighting7.routes

import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.request.receive
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fixture.GroupableFixture
import uk.me.cormack.lighting7.fixture.AttributeCategory
import uk.me.cormack.lighting7.fixture.dmx.DmxSlider
import uk.me.cormack.lighting7.fixture.trait.WithPosition
import uk.me.cormack.lighting7.fx.CueAssignmentResolver.AttributeValue
import uk.me.cormack.lighting7.fx.POSITION_CENTRE_MAX
import uk.me.cormack.lighting7.fx.POSITION_CENTRE_MIN
import uk.me.cormack.lighting7.fixture.group.FixtureElement
import uk.me.cormack.lighting7.fixture.group.MultiElementFixture
import uk.me.cormack.lighting7.fx.CueAssignmentResolver
import uk.me.cormack.lighting7.fx.ProgrammerOwner
import uk.me.cormack.lighting7.fx.ProgrammerStore
import uk.me.cormack.lighting7.fixture.attribute.Slider
import uk.me.cormack.lighting7.state.BuskRigOrder
import uk.me.cormack.lighting7.fx.ProgrammerWriter
import uk.me.cormack.lighting7.fx.FanCurve
import uk.me.cormack.lighting7.fx.FanOver
import uk.me.cormack.lighting7.fx.FanPlan
import uk.me.cormack.lighting7.fx.PaletteFanOut
import uk.me.cormack.lighting7.fx.PaletteIntent
import uk.me.cormack.lighting7.fx.PaletteAttribute
import uk.me.cormack.lighting7.fx.PaletteResolver
import uk.me.cormack.lighting7.fx.genericColourRows
import uk.me.cormack.lighting7.fx.group.SpreadOrder
import uk.me.cormack.lighting7.fx.maskAllows
import uk.me.cormack.lighting7.fx.parseFamilies
import uk.me.cormack.lighting7.fx.parsePaletteColourRefUuid
import uk.me.cormack.lighting7.fx.parsePaletteIntent
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.show.JOG_DEFAULT_PLANE_Z
import uk.me.cormack.lighting7.state.State
import kotlin.math.roundToInt
import uk.me.cormack.lighting7.fx.groupHintsForTargets

/** 400 code: the request is malformed — an unknown property, curve, order, over or mode, an endpoint that is not an intent of the property's shape, `runs` below 1, an `ABOUT` fan off Position or with no amount, a base that is not a position. */
internal const val CODE_FAN_INVALID = "FAN_INVALID"

/** 400 code: no targets — a fan has nothing to land on. */
internal const val CODE_FAN_NEEDS_SELECTION = "FAN_NEEDS_SELECTION"

/**
 * `POST /projects/{id}/programmer/fan` — **Fan**, resolved on the desk (busk-further plan D9,
 * §3.5; terminology plan D6): two intents, a curve, an order, runs and an over-switch in; one literal per head out,
 * written into the programmer as ordinary Local entries.
 *
 * The client never interpolates, for the reason `paletteIntent.ts` is a serialiser: only the desk
 * knows a group's member order, each head's range, which cells a fixture has and what a colour
 * means on a head with amber. Interpolation happens **in the intent's own space** — Lab for a
 * colour ([PaletteResolver.mixLab]), centre-relative steps for a position, percent for a level, a DMX byte for
 * an emitter — and each head's literal then goes through the same [PaletteResolver] a template's
 * *Apply as values* uses, so the far end of a spread is exactly what a template of that hex would have given
 * that head. The write is one batched [ProgrammerWriter.writeAttributes] with owner `WEB`: one
 * `ProgrammerStore.put` per key, so `programmer.entryChanged` carries it, Record captures it and
 * Blind previews it, and Clear releases it like any busked value (D10: a spread is a result, not a
 * template).
 *
 * `order` is a `SpreadOrder` name: `NORMAL` is **rig order** (`state/BuskRigOrder.kt` — a
 * group in member order, cells in element order), `REVERSE` the other way, `RANDOM` seeded from the
 * request. A spread whose one target is a group runs in **that group's member order** instead (cells
 * in element order), because rig order places a fixture at its first appearance on the rig and can
 * so disagree with the order the group sheet shows (venue-visit plan S7). `over = CELLS` spreads across every cell of every multi-head fixture in the selection;
 * `HEADS` treats each fixture as one step. A `pal:{uuid}` endpoint resolves the template's generic
 * colour, as an FX colour reference does. The selection's attribute mask is honoured: a property
 * outside it writes nothing and answers `skippedFamilies`.
 *
 * **Two things changed for the programmer's own Spread (editor-kit plan §3.2, D6).** `write` on
 * the request, default true: `false` runs everything above — the mask, the target expansion, the
 * order, the fractions, the per-head resolve, the skips — and stops short of the batched write, so
 * the desk *answers without writing*. That is the focused-Look-layer arm: the client lands each
 * answered literal in the layer's draft, which coalesces and PUTs as every layer-scope edit does;
 * the desk does not write into the Look itself, because `PUT /looks/{id}` republishes every cue
 * layering it. And `written[].value` is each head's **literal** — [CueAssignmentResolver.AttributeValue.serialize],
 * the grammar Look rows and programmer entries already use (`"0".."255"`, `"#rrggbb;w128"`,
 * `"pan,tilt"`) — rather than the interpolated intent it carried before, so that arm has something
 * a Look row can hold. Nothing else read the field. A client on an older desk can tell the two
 * apart by parsing: an intent string is not a programmer value. A colour-wheel head answers its
 * slot's level under its own `colourWheel`, which a Look row holds like any other. And the curve is
 * spread over the heads that can take the property, found first; a head that cannot is skipped and
 * consumes no position on it.
 *
 * **`mode = ABOUT`** (*About each position*, venue-visit plan S12) is the relative arm, Position only: no endpoints, an
 * amount per axis (`fan`), and each head moved from **its own position** by the amount times its
 * [FanPlan.fanWeights] weight — symmetric about the middle of the same order, under the same
 * curve, runs and over. A head starts from the request's `base` where it names one (the row being
 * edited, or the anchor an earlier answer gave), else — unless `write` is false, whose rows are
 * another context's — from Local's own value for it as output arbitrates it, else from what the
 * desk is sending it now; the answer's `base` says which position each head started from, so a
 * client can hold the gesture's anchor and Live never fans a fan. The sum is a centre-relative
 * intent through the same [PaletteResolver], an axis not fanned staying exactly where it was, and a
 * head taken past its travel is held at the end and named in `clamped`.
 *
 * **A stage fan** is a fan whose amounts are `fan.x` and `fan.y`, metres across (towards SL) and up
 * the stage, rather than `pan` and `tilt`: each head's landing point on the jog's working plane is
 * moved by the amount times its weight and the head aimed at it ([jogHead]), so the beams fan across
 * the floor by the same metres whatever each head's mount. Over HEADS only, since a cell has no
 * placement; a base carries fine bytes as a jog's does, `near` is the previous step's answer, and a
 * head the fan takes out of its travel is skipped as a jog skips it.
 */
internal fun Route.routeApiRestProgrammerFan(state: State) {
    post<ProgrammerFanResource> { resource ->
        withCurrentProject(state, resource.projectId) { project ->
            val request = call.receive<FanRequest>()
            val families = try {
                parseFamilies(request.families)
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Bad mask"))
                return@withCurrentProject
            }
            when (val outcome = fanIntoProgrammer(state, request, families)) {
                is FanOutcome.Invalid ->
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(outcome.message, code = outcome.code))
                is FanOutcome.Done -> call.respond(outcome.response)
            }
        }
    }
}

@Resource("/{projectId}/programmer/fan")
internal data class ProgrammerFanResource(val projectId: String)

@Serializable
internal data class FanRequest(
    val targets: List<CueTargetDto> = emptyList(),
    /** The selection's attribute mask; absent is every attribute. */
    val families: List<String>? = null,
    /** A `PaletteAttribute` name — `rgbColour`, `dimmer`, `position`, `white` … */
    val attribute: String,
    /** Serialised `PaletteIntent`s, or `pal:{uuid}` for a colour. `BETWEEN` only; `ABOUT` has none. */
    val from: String = "",
    val to: String = "",
    /** `LINE` · `MIRROR` · `ARROW` · `MIRROR_OUT`. */
    val curve: String = "LINE",
    /** A `SpreadOrder` name; `NORMAL` is rig order. */
    val order: String = "NORMAL",
    /** Contiguous fans the order is cut into. */
    val runs: Int = 1,
    /** `HEADS` · `CELLS`. */
    val over: String = "HEADS",
    val fadeMs: Long? = null,
    /** For `order = RANDOM`. */
    val seed: Int = 0,
    /**
     * Write the resolved literals into the programmer (true, the busk tab and Local) or only
     * resolve and answer them (false, a focused Look layer — the client lands them in the layer's
     * draft). A defaulted field, so a request that omits it is byte for byte the request the busk
     * tab has always sent; the client omits it whenever it is true, because this Json refuses an
     * unknown key and a desk mid-upgrade would otherwise 400 every Local spread.
     */
    val write: Boolean = true,
    /** `BETWEEN` (*Between two values*, from → to) or `ABOUT` (*About each position*: an amount around each head's own, position only). */
    val mode: String = "BETWEEN",
    /**
     * `ABOUT`: the outer heads' offset per axis — `pan` and `tilt` in centre steps, or `x` and `y` in
     * stage metres (a stage fan), never both; an absent axis is not fanned.
     */
    val fan: FanAmountDto? = null,
    /**
     * `ABOUT`: where each head starts, a `"pan,tilt"` literal per head — the answer's own
     * [FanResponse.base] sent back, or the value of the row being edited. A head absent here is
     * read by the desk.
     */
    val base: List<FanBaseDto>? = null,
    /** A stage fan: the previous step's answer, so each head's solve stays on the side it is on. */
    val near: List<AimNearDto>? = null,
)

@Serializable
internal data class FanAmountDto(
    val pan: Int? = null,
    val tilt: Int? = null,
    /** Stage metres towards SL. */
    val x: Double? = null,
    /** Stage metres upstage. */
    val y: Double? = null,
)

/** A head's start: its `"pan,tilt"` literal and, for a stage fan, the fine bytes it has — a jog's start. */
internal typealias FanBaseDto = AimBaseDto

@Serializable
internal data class FanResponse(
    val written: List<FanWriteDto> = emptyList(),
    val skipped: List<FanSkipDto> = emptyList(),
    /** The property's family, when the mask kept it out — then nothing was written. */
    val skippedFamilies: List<String> = emptyList(),
    /** `ABOUT`: where each head started — the anchor a later request in the same gesture sends back. */
    val base: List<FanBaseDto> = emptyList(),
    /** `ABOUT`: the heads the fan took past the end of their travel, held at it. */
    val clamped: List<CueTargetDto> = emptyList(),
    /** A stage fan: each moved head's pan and tilt in travel degrees, the `near` a later request sends back. */
    val near: List<AimNearDto> = emptyList(),
)

/**
 * One head's literal: the head, the property it actually landed on, and the **value it got** —
 * [CueAssignmentResolver.AttributeValue.serialize]'s form, never the intent. The focused-Look-layer
 * arm (`write = false`) is its reader, and a Look row holds a literal.
 */
@Serializable
internal data class FanWriteDto(val target: CueTargetDto, val attribute: String, val value: String)

@Serializable
internal data class FanSkipDto(val target: CueTargetDto, val reason: String)

internal sealed interface FanOutcome {
    data class Invalid(val message: String, val code: String = CODE_FAN_INVALID) : FanOutcome
    data class Done(val response: FanResponse) : FanOutcome
}

/** What a fan lands between: two endpoint intents, or a fan around each head's own position. */
private sealed interface FanShape {
    /** The intent the heads that can take the property are found with. */
    val probe: PaletteIntent

    data class Between(val from: PaletteIntent, val to: PaletteIntent) : FanShape {
        override val probe get() = from
    }

    data class About(val pan: Int?, val tilt: Int?, val given: Map<String, AttributeValue.Position>) : FanShape {
        override val probe get() = PaletteIntent.Position(0.0, 0.0)
    }

    /** A fan in stage metres: [x] towards SL, [y] upstage. */
    data class StageFan(
        val x: Double,
        val y: Double,
        val given: Map<String, JogStart>,
        val near: Map<String, AimNearDto>,
    ) : FanShape {
        override val probe get() = PaletteIntent.Position(0.0, 0.0)
    }
}

/**
 * The body of the route, separable so a test can drive it without HTTP. [owner] and [rigOrder] are
 * the surface fan's: it writes as `surface`, and reads the rig order once per gesture rather than
 * once per detent.
 */
internal fun fanIntoProgrammer(
    state: State,
    request: FanRequest,
    families: Set<uk.me.cormack.lighting7.fx.AttributeFamily>?,
    owner: ProgrammerOwner = ProgrammerOwner.WEB,
    rigOrder: () -> BuskRigOrder? = state::buskRigOrder,
    placementsOf: (Collection<String>) -> Map<String, AimPlacement> = { aimPlacements(state, state.show.project, it) },
): FanOutcome {
    val attribute = PaletteAttribute.ofOrNull(request.attribute)
        ?: return FanOutcome.Invalid("'${request.attribute}' is not a property a spread can carry")
    val curve = FanCurve.byName(request.curve)
        ?: return FanOutcome.Invalid("Curve '${request.curve}' is not one of ${FanCurve.entries.map { it.name }}")
    val over = FanOver.byName(request.over)
        ?: return FanOutcome.Invalid("Over '${request.over}' is not one of ${FanOver.entries.map { it.name }}")
    val order = SpreadOrder.byName(request.order)
        ?.let { if (it is SpreadOrder.RANDOM) SpreadOrder.RANDOM(request.seed) else it }
        ?: return FanOutcome.Invalid("'${request.order}' is not an Order")
    if (request.runs < 1) return FanOutcome.Invalid("runs must be at least 1")
    val shape = when (request.mode.trim().uppercase()) {
        "BETWEEN" -> {
            val from = fanEndpoint(state, request.from, attribute)
                ?: return FanOutcome.Invalid("'from' (${request.from}) is not a ${attribute.label.lowercase()} intent")
            val to = fanEndpoint(state, request.to, attribute)
                ?: return FanOutcome.Invalid("'to' (${request.to}) is not a ${attribute.label.lowercase()} intent")
            // A position spread interpolates in one unit: centre-relative steps and travel degrees mean
            // different things on a head, so a lerp between the two would be a number in neither.
            if (from is PaletteIntent.Position && to is PaletteIntent.Position && from.unit != to.unit) {
                return FanOutcome.Invalid("'from' and 'to' must be one position unit (pos: or deg:), not both")
            }
            FanShape.Between(from, to)
        }
        "ABOUT" -> fanShape(request, attribute, over).getOrElse { return FanOutcome.Invalid(it.message ?: "Bad fan") }
        else -> return FanOutcome.Invalid("Mode '${request.mode}' is not one of [BETWEEN, ABOUT]")
    }
    if (request.targets.isEmpty()) {
        return FanOutcome.Invalid("A fan needs a selection", CODE_FAN_NEEDS_SELECTION)
    }
    if (families != null && !maskAllows(families, attribute.family)) {
        return FanOutcome.Done(FanResponse(skippedFamilies = listOf(attribute.family.name)))
    }

    val fixtures = state.show.fixtures
    val skipped = ArrayList<FanSkipDto>()

    // Heads: a group to its members in member order, a fixture to itself, a cell to itself; then,
    // over CELLS, every multi-head fixture to its cells. Distinct by key, first spelling wins.
    val heads = LinkedHashMap<String, GroupableFixture>()
    for (target in request.targets) {
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
        for (member in members) {
            val units = if (over == FanOver.CELLS && member is MultiElementFixture<*>) member.elements else listOf(member)
            for (unit in units) heads.putIfAbsent(unit.targetKey, unit)
        }
    }

    // Over HEADS a parent's step lands on its own cells (below), so a cell named beside its parent is
    // already covered — the parent↔cell rule a press reads (`TargetCoverage.covers`). Kept, it would be
    // a second step writing the same cell a second value; the client's marquee drops it the same way.
    if (over == FanOver.HEADS) {
        heads.values.removeIf { it is FixtureElement<*> && it.parentFixture.key in heads }
    }

    // Rig order first (NORMAL); the strategy then says where along the curve each sits. A spread
    // over **one group** keeps that group's own member order (venue-visit plan S7) — what the group
    // sheet shows and its effects run in — where rig order places each fixture at its first
    // appearance on the rig, which an earlier group or tile holding it can put anywhere.
    val oneGroup = request.targets.singleOrNull()?.let { TargetRef.ofOrNull(it.type, it.key) } is TargetRef.Group
    val rigOrdered = (if (oneGroup) null else rigOrder())
        ?.sort(heads.keys.map { CueTargetDto(TargetRef.Fixture.TYPE, it) })
        ?.map { heads.getValue(it.key) }
        ?: heads.values.toList()
    val fittedMediaFor: (String) -> uk.me.cormack.lighting7.fixture.media.FittedMedia? = fixtures::fittedMediaFor
    fun media(head: GroupableFixture) = PaletteFanOut.mediaOf(head, fittedMediaFor)
    // **Two passes: the steps that can take the property are found first, and the curve is spread
    // over those alone** (editor-kit session 3 review). A head that resolves nothing — a par in a
    // Position spread, a dimmer-only head in a Colour one — is skipped by name, as before; what
    // changed is that it no longer *consumes a position on the curve*: a marquee on the programmer
    // is geometric and sweeps such heads up with the rest, so two RGB heads among eight rows landed
    // at `0` and `1/7` and *to* never reached the rig. Support is a property of the head and the
    // shape, not of the fraction, so it is asked with `from` (a fan's probe is the centre).
    //
    // **A step lands on the heads [PaletteFanOut] says it stands for** (venue-visit plan S1): over
    // HEADS a multi-head fixture whose property lives only on its cells — the Shehds' colour, on its
    // three zones — is still one step on the curve, and its value lands on every cell that carries
    // the property, where the parent alone resolved nothing and was skipped. Over CELLS each cell is
    // already its own step, and stands for itself.
    val ordered = rigOrdered.mapNotNull { step ->
        val landing = PaletteFanOut.headsFor(step, attribute.attribute, fittedMediaFor)
            .filter { PaletteResolver.resolve(it, attribute.attribute, shape.probe, media(it)).isSupported }
        if (landing.isEmpty()) {
            val probe = PaletteResolver.resolve(step, attribute.attribute, shape.probe, media(step))
            skipped += FanSkipDto(
                CueTargetDto(TargetRef.Fixture.TYPE, step.targetKey),
                (probe.note as? PaletteResolver.Note.Unsupported)?.reason ?: "unsupported",
            )
            null
        } else {
            step to landing
        }
    }
    // A stage fan's steps are the heads that can be aimed at all, so one that never can — unplaced,
    // no degrees — takes no place on the curve, as one that cannot take the property takes none.
    val placements = if (shape is FanShape.StageFan) {
        placementsOf(ordered.flatMap { (_, landing) -> landing.map { it.targetKey } })
    } else {
        emptyMap()
    }
    val stepped = if (shape !is FanShape.StageFan) ordered else ordered.mapNotNull { (step, landing) ->
        val aimable = landing.filter { head ->
            val reason = aimSkipReason(head, placements[head.targetKey])
            if (reason != null) skipped += FanSkipDto(CueTargetDto(TargetRef.Fixture.TYPE, head.targetKey), reason)
            reason == null
        }
        if (aimable.isEmpty()) null else step to aimable
    }
    val fractions = FanPlan.fractions(stepped.size, order, curve, request.runs)
    val weights = FanPlan.fanWeights(stepped.size, order, curve, request.runs)
    val hints = groupHintsForTargets(fixtures, request.targets.mapNotNull { TargetRef.ofOrNull(it.type, it.key) })

    val writes = ArrayList<ProgrammerWriter.AttributeWrite>()
    val written = ArrayList<FanWriteDto>()
    val bases = ArrayList<FanBaseDto>()
    val clamped = ArrayList<CueTargetDto>()
    val near = ArrayList<AimNearDto>()
    stepped.forEachIndexed { index, (step, landing) ->
        for (head in landing) {
            val target = CueTargetDto(TargetRef.Fixture.TYPE, head.targetKey)
            val sourceGroup = hints[head.targetKey] ?: hints[step.targetKey]
                ?: (head as? FixtureElement<*>)?.parentFixture?.key?.let { hints[it] }
            if (shape is FanShape.StageFan) {
                val weight = weights[index]
                val jog = jogHead(
                    state, head, placements[head.targetKey], shape.given[head.targetKey], readLocal = request.write,
                    shape.near[head.targetKey], shape.x * weight, shape.y * weight, JOG_DEFAULT_PLANE_Z,
                )
                jog.base?.let { bases += FanBaseDto(target, it.position.serialize(), it.panFine?.toInt(), it.tiltFine?.toInt()) }
                when (jog) {
                    is HeadJog.Skip -> skipped += FanSkipDto(target, jog.reason)
                    is HeadJog.Moved -> {
                        writes += jog.attributeWrites(head, sourceGroup)
                        written += FanWriteDto(target, "position", jog.position.serialize())
                        near += AimNearDto(target, jog.panDeg, jog.tiltDeg)
                    }
                }
                continue
            }
            val intent = when (shape) {
                is FanShape.Between -> interpolateIntent(shape.from, shape.to, fractions[index])
                is FanShape.About -> {
                    // A `write: false` host hands in the row it is editing; a head the row lacks
                    // starts from the stage, never from Local, which is another context's.
                    val base = fanBase(state, head, shape.given, readLocal = request.write)
                    if (base == null) {
                        skipped += FanSkipDto(target, "no position to fan from")
                        continue
                    }
                    bases += FanBaseDto(target, base.serialize())
                    fanIntent(head, base, shape, weights[index]) ?: run {
                        skipped += FanSkipDto(target, "position is not DMX-backed")
                        null
                    } ?: continue
                }
                is FanShape.StageFan -> error("a stage fan is solved above")
            }
            val resolution = PaletteResolver.resolve(head, attribute.attribute, intent, media(head))
            val value = resolution.value
            if (value == null) {
                skipped += FanSkipDto(target, (resolution.note as? PaletteResolver.Note.Unsupported)?.reason ?: "unsupported")
                continue
            }
            if (shape is FanShape.About && resolution.note is PaletteResolver.Note.Clamped) clamped += target
            // A colour-wheel head answers a wheel *slot* (`AttributeValue.Setting`) under its own
            // property (`colourWheel`), and its literal is the slot's level: the cook reads a level on
            // a slot-backed COLOUR row as a slot (`Fixture.Property.settingBacked`), so both arms land
            // it — the write arm as a typed entry, the answer-only arm as a Look row.
            writes += ProgrammerWriter.AttributeWrite(head, resolution.attribute, value, sourceGroup)
            written += FanWriteDto(target, resolution.attribute, value.serialize())
        }
    }
    // `write = false` answers what *would* land and lands nothing — the Look-layer arm.
    if (request.write && writes.isNotEmpty()) {
        state.show.fxEngine.programmer.writeAttributes(owner, writes, fadeMs = request.fadeMs ?: 0)
    }
    return FanOutcome.Done(FanResponse(written, skipped, base = bases, clamped = clamped, near = near))
}

/** An `ABOUT` request's amounts and given bases, or the reason it is not one. */
private fun fanShape(request: FanRequest, attribute: PaletteAttribute, over: FanOver): Result<FanShape> = runCatching {
    require(attribute == PaletteAttribute.POSITION) { "A fan is a position spread; '${request.attribute}' has no position of its own to fan from" }
    val fan = request.fan
    if (fan != null && (fan.x != null || fan.y != null)) return@runCatching stageFanShape(request, fan, over).getOrThrow()
    require(fan != null && (fan.pan != null || fan.tilt != null)) { "A fan needs a pan or a tilt amount, or an x or a y" }
    for ((axis, amount) in listOf("pan" to fan.pan, "tilt" to fan.tilt)) {
        require(amount == null || amount in POSITION_CENTRE_MIN..POSITION_CENTRE_MAX) {
            "The $axis amount must be $POSITION_CENTRE_MIN..$POSITION_CENTRE_MAX centre steps"
        }
    }
    val given = LinkedHashMap<String, AttributeValue.Position>()
    for (entry in request.base.orEmpty()) {
        val position = CueAssignmentResolver.parseAssignmentValue(AttributeCategory.PAN, "position", entry.value) as? AttributeValue.Position
        require(position != null) { "base for '${entry.target.key}' (${entry.value}) is not a pan,tilt position" }
        given[entry.target.key] = position
    }
    FanShape.About(fan.pan, fan.tilt, given)
}

/** A stage fan's amounts, starts and previous step, or the reason it is not one. */
private fun stageFanShape(request: FanRequest, fan: FanAmountDto, over: FanOver): Result<FanShape.StageFan> = runCatching {
    require(fan.pan == null && fan.tilt == null) { "A fan is in pan and tilt or in stage metres, not both" }
    require(over == FanOver.HEADS) { "A stage fan moves heads; a cell has no placement of its own" }
    for ((axis, amount) in listOf("x" to fan.x, "y" to fan.y)) {
        checkStageCoord(axis, amount)?.let { throw IllegalArgumentException(it) }
    }
    val given = jogStarts(request.base.orEmpty()).getOrThrow()
    FanShape.StageFan(fan.x ?: 0.0, fan.y ?: 0.0, given, jogNear(request.near.orEmpty()).getOrThrow())
}

/**
 * Where a fanned head starts: the request's own base for it, else — where [readLocal] — Local's own
 * position for it (what Record would capture: a fade in flight, a position effect or Blind leave the
 * output short of it), else what the desk is sending its pan and tilt now, axis by axis. Null when
 * there is nothing to read.
 */
private fun fanBase(
    state: State,
    head: GroupableFixture,
    given: Map<String, AttributeValue.Position>,
    readLocal: Boolean,
): AttributeValue.Position? {
    given[head.targetKey]?.let { return it }
    val positioned = head as? WithPosition ?: return null
    val panSlider = positioned.pan as? DmxSlider ?: return null
    val tiltSlider = positioned.tilt as? DmxSlider ?: return null
    val local = if (readLocal) localPosition(state.show.programmerStore, head) else null
    fun read(slider: DmxSlider): UByte? = state.show.fixtures.controllerOrNull(slider.universe)?.getValue(slider.channelNo)
    return AttributeValue.Position(
        local?.pan ?: read(panSlider) ?: return null,
        local?.tilt ?: read(tiltSlider) ?: return null,
    )
}

/** Local's pan and tilt for one head; an axis Local does not hold is null. */
internal data class LocalPosition(val pan: UByte?, val tilt: UByte?)

/**
 * Local's own position for [head], axis by axis, as output arbitrates it
 * (`PositionTarget.composeProgrammerOver`): the newest of the `position` entry, the axis's own
 * property entry (a `tilt` fader on the selection) and the axis channel's sideband slot (a raw
 * write). Null when Local holds neither axis.
 */
internal fun localPosition(store: ProgrammerStore, head: GroupableFixture): LocalPosition? {
    val positioned = head as? WithPosition ?: return null
    val entry = store.get(head.targetKey, "position")
    val position = entry?.value?.resolved as? AttributeValue.Position
    val catalogue = (head as? Fixture)?.fixtureAttributes
    fun axis(fromPosition: UByte?, slider: Slider, category: AttributeCategory, fallbackName: String): UByte? {
        val candidates = ArrayList<Pair<Long, UByte>>(3)
        if (fromPosition != null) candidates += entry!!.seq to fromPosition
        val name = catalogue?.firstOrNull { it.category == category }?.name ?: fallbackName
        store.get(head.targetKey, name)?.let { slot ->
            (slot.value.resolved as? AttributeValue.Slider)?.let { candidates += slot.seq to it.value }
        }
        (slider as? DmxSlider)?.let { dmx ->
            store.getChannelSlot(dmx.universe.universe, dmx.channelNo)?.let { slot ->
                (slot.value.resolved as? AttributeValue.Slider)?.let { candidates += slot.seq to it.value }
            }
        }
        return candidates.maxByOrNull { it.first }?.second
    }
    val pan = axis(position?.pan, positioned.pan, AttributeCategory.PAN, "pan")
    val tilt = axis(position?.tilt, positioned.tilt, AttributeCategory.TILT, "tilt")
    return if (pan == null && tilt == null) null else LocalPosition(pan, tilt)
}

/**
 * [base] moved by the fan: each fanned axis by its amount times [weight], in centre steps on the
 * head's own slider range (the resolver's own inverse), an axis not fanned left on its base exactly.
 * Null for a head whose position is not DMX-backed.
 */
private fun fanIntent(head: GroupableFixture, base: AttributeValue.Position, fan: FanShape.About, weight: Double): PaletteIntent.Position? {
    val centre = PaletteResolver.centreIntentOf(head, base) ?: return null
    return PaletteIntent.Position(centre.pan + (fan.pan ?: 0) * weight, centre.tilt + (fan.tilt ?: 0) * weight)
}

/**
 * One endpoint: a serialised intent of [attribute]'s shape, or — for a colour — a `pal:{uuid}`
 * resolved to the template's generic colour row (its hex and policy), the way an FX colour
 * reference resolves one. Null when it is neither.
 */
private fun fanEndpoint(state: State, raw: String, attribute: PaletteAttribute): PaletteIntent? {
    val paletteUuid = parsePaletteColourRefUuid(raw)
    val intent = if (paletteUuid != null) {
        if (attribute != PaletteAttribute.COLOUR) return null
        val snapshot = state.show.paletteRegistry.snapshot(paletteUuid) ?: return null
        val rows = genericColourRows(snapshot.rows, { it.attribute }, { it.isDeferred }) ?: return null
        rows.firstNotNullOfOrNull { row ->
            if (PaletteAttribute.ofOrNull(row.attribute) == PaletteAttribute.COLOUR) parsePaletteIntent(row.value) else null
        } ?: return null
    } else {
        parsePaletteIntent(raw) ?: return null
    }
    if (!attribute.accepts(intent)) return null
    if (intent is PaletteIntent.Colour && PaletteResolver.parseHexColour(intent.hex) == null) return null
    return intent
}

/** The point [t] of the way from [from] to [to], in the intent's own space. Both are one arm. */
internal fun interpolateIntent(from: PaletteIntent, to: PaletteIntent, t: Double): PaletteIntent {
    val f = t.coerceIn(0.0, 1.0)
    fun lerp(a: Double, b: Double) = a + (b - a) * f
    return when (from) {
        is PaletteIntent.Colour -> {
            val target = to as PaletteIntent.Colour
            val a = PaletteResolver.parseHexColour(from.hex)!!
            val b = PaletteResolver.parseHexColour(target.hex)!!
            PaletteIntent.Colour(PaletteResolver.toHex(PaletteResolver.mixLab(a, b, f)), from.policy)
        }
        is PaletteIntent.Percent -> PaletteIntent.Percent(lerp(from.value, (to as PaletteIntent.Percent).value))
        is PaletteIntent.Position -> {
            val target = to as PaletteIntent.Position
            PaletteIntent.Position(lerp(from.pan, target.pan), lerp(from.tilt, target.tilt), from.unit)
        }
        is PaletteIntent.Level -> PaletteIntent.Level(lerp(from.value.toDouble(), (to as PaletteIntent.Level).value.toDouble()).roundToInt())
        is PaletteIntent.Switch -> if (f < 0.5) from else to
    }
}
