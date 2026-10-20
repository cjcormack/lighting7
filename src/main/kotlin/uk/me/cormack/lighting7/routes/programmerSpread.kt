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
import uk.me.cormack.lighting7.fixture.PropertyCategory
import uk.me.cormack.lighting7.fixture.dmx.DmxSlider
import uk.me.cormack.lighting7.fixture.trait.WithPosition
import uk.me.cormack.lighting7.fx.CueAssignmentResolver.PropertyValue
import uk.me.cormack.lighting7.fx.POSITION_CENTRE_MAX
import uk.me.cormack.lighting7.fx.POSITION_CENTRE_MIN
import uk.me.cormack.lighting7.fixture.group.FixtureElement
import uk.me.cormack.lighting7.fixture.group.MultiElementFixture
import uk.me.cormack.lighting7.fx.CueAssignmentResolver
import uk.me.cormack.lighting7.fx.ProgrammerOwner
import uk.me.cormack.lighting7.fx.ProgrammerStore
import uk.me.cormack.lighting7.fixture.property.Slider
import uk.me.cormack.lighting7.state.BuskRigOrder
import uk.me.cormack.lighting7.fx.ProgrammerWriter
import uk.me.cormack.lighting7.fx.SpreadCurve
import uk.me.cormack.lighting7.fx.SpreadOver
import uk.me.cormack.lighting7.fx.SpreadPlan
import uk.me.cormack.lighting7.fx.TemplateFanOut
import uk.me.cormack.lighting7.fx.TemplateIntent
import uk.me.cormack.lighting7.fx.TemplateProperty
import uk.me.cormack.lighting7.fx.TemplateResolver
import uk.me.cormack.lighting7.fx.genericColourRows
import uk.me.cormack.lighting7.fx.group.DistributionStrategy
import uk.me.cormack.lighting7.fx.maskAllows
import uk.me.cormack.lighting7.fx.parseMaskGroups
import uk.me.cormack.lighting7.fx.parseTemplateColourRefUuid
import uk.me.cormack.lighting7.fx.parseTemplateIntent
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.show.JOG_DEFAULT_PLANE_Z
import uk.me.cormack.lighting7.state.State
import kotlin.math.roundToInt
import uk.me.cormack.lighting7.fx.groupHintsForTargets

/** 400 code: the request is malformed — an unknown property, curve, order, over or mode, an endpoint that is not an intent of the property's shape, `parts` below 1, a fan off Position or with no amount, a base that is not a position. */
internal const val CODE_SPREAD_INVALID = "SPREAD_INVALID"

/** 400 code: no targets — a spread has nothing to land on. */
internal const val CODE_SPREAD_NEEDS_SELECTION = "SPREAD_NEEDS_SELECTION"

/**
 * `POST /projects/{id}/programmer/spread` — **fan**, resolved on the desk (busk-further plan D9,
 * §3.5): two intents, a curve, an order, parts and an over-switch in; one literal per head out,
 * written into the programmer as ordinary Local entries.
 *
 * The client never interpolates, for the reason `templateIntent.ts` is a serialiser: only the desk
 * knows a group's member order, each head's range, which cells a fixture has and what a colour
 * means on a head with amber. Interpolation happens **in the intent's own space** — Lab for a
 * colour ([TemplateResolver.mixLab]), centre-relative steps for a position, percent for a level, a DMX byte for
 * an emitter — and each head's literal then goes through the same [TemplateResolver] a template's
 * *Apply as values* uses, so the far end of a spread is exactly what a template of that hex would have given
 * that head. The write is one batched [ProgrammerWriter.writeProperties] with owner `WEB`: one
 * `ProgrammerStore.put` per key, so `programmer.entryChanged` carries it, Record captures it and
 * Blind previews it, and Clear releases it like any busked value (D10: a spread is a result, not a
 * template).
 *
 * `order` is a `DistributionStrategy` name: `LINEAR` is **rig order** (`state/BuskRigOrder.kt` — a
 * group in member order, cells in element order), `REVERSE` the other way, `RANDOM` seeded from the
 * request. A spread whose one target is a group runs in **that group's member order** instead (cells
 * in element order), because rig order places a fixture at its first appearance on the rig and can
 * so disagree with the order the group sheet shows (venue-visit plan S7). `over = CELLS` spreads across every cell of every multi-head fixture in the selection;
 * `HEADS` treats each fixture as one step. A `tmpl:{uuid}` endpoint resolves the template's generic
 * colour, as an FX colour reference does. The selection's attribute mask is honoured the way a
 * Look's press honours it: a property outside it writes nothing and answers `skippedFamilies`.
 *
 * **Two things changed for the programmer's own Spread (editor-kit plan §3.2, D6).** `write` on
 * the request, default true: `false` runs everything above — the mask, the target expansion, the
 * order, the fractions, the per-head resolve, the skips — and stops short of the batched write, so
 * the desk *answers without writing*. That is the focused-Look-layer arm: the client lands each
 * answered literal in the layer's draft, which coalesces and PUTs as every layer-scope edit does;
 * the desk does not write into the Look itself, because `PUT /looks/{id}` republishes every cue
 * layering it. And `written[].value` is each head's **literal** — [CueAssignmentResolver.PropertyValue.serialize],
 * the grammar Look rows and programmer entries already use (`"0".."255"`, `"#rrggbb;w128"`,
 * `"pan,tilt"`) — rather than the interpolated intent it carried before, so that arm has something
 * a Look row can hold. Nothing else read the field. A client on an older desk can tell the two
 * apart by parsing: an intent string is not a programmer value. A colour-wheel head answers its
 * slot's level under its own `colourWheel`, which a Look row holds like any other. And the curve is
 * spread over the heads that can take the property, found first; a head that cannot is skipped and
 * consumes no position on it.
 *
 * **`mode = FAN`** (venue-visit plan S12) is the relative arm, Position only: no endpoints, an
 * amount per axis (`fan`), and each head moved from **its own position** by the amount times its
 * [SpreadPlan.fanWeights] weight — symmetric about the middle of the same order, under the same
 * curve, parts and over. A head starts from the request's `base` where it names one (the row being
 * edited, or the anchor an earlier answer gave), else — unless `write` is false, whose rows are
 * another context's — from Local's own value for it as output arbitrates it, else from what the
 * desk is sending it now; the answer's `base` says which position each head started from, so a
 * client can hold the gesture's anchor and Live never fans a fan. The sum is a centre-relative
 * intent through the same [TemplateResolver], an axis not fanned staying exactly where it was, and a
 * head taken past its travel is held at the end and named in `clamped`.
 *
 * **A stage fan** is a fan whose amounts are `fan.x` and `fan.y`, metres across (towards SL) and up
 * the stage, rather than `pan` and `tilt`: each head's landing point on the jog's working plane is
 * moved by the amount times its weight and the head aimed at it ([jogHead]), so the beams fan across
 * the floor by the same metres whatever each head's mount. Over HEADS only, since a cell has no
 * placement; a base carries fine bytes as a jog's does, `near` is the previous step's answer, and a
 * head the fan takes out of its travel is skipped as a jog skips it.
 */
internal fun Route.routeApiRestProgrammerSpread(state: State) {
    post<ProgrammerSpreadResource> { resource ->
        withCurrentProject(state, resource.projectId) { project ->
            val request = call.receive<SpreadRequest>()
            val families = try {
                parseMaskGroups(request.families)
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Bad mask"))
                return@withCurrentProject
            }
            when (val outcome = spreadIntoProgrammer(state, request, families)) {
                is SpreadOutcome.Invalid ->
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(outcome.message, code = outcome.code))
                is SpreadOutcome.Done -> call.respond(outcome.response)
            }
        }
    }
}

@Resource("/{projectId}/programmer/spread")
internal data class ProgrammerSpreadResource(val projectId: String)

@Serializable
internal data class SpreadRequest(
    val targets: List<CueTargetDto> = emptyList(),
    /** The selection's attribute mask; absent is every attribute. */
    val families: List<String>? = null,
    /** A `TemplateProperty` name — `rgbColour`, `dimmer`, `position`, `white` … */
    val property: String,
    /** Serialised `TemplateIntent`s, or `tmpl:{uuid}` for a colour. `ENDS` only; a fan has none. */
    val from: String = "",
    val to: String = "",
    /** `LINE` · `MIRROR` · `ARROW` · `WINGS`. */
    val curve: String = "LINE",
    /** A `DistributionStrategy` name; `LINEAR` is rig order. */
    val order: String = "LINEAR",
    val parts: Int = 1,
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
    /** `ENDS` (from → to) or `FAN` (an amount around each head's own position, position only). */
    val mode: String = "ENDS",
    /**
     * `FAN`: the outer heads' offset per axis — `pan` and `tilt` in centre steps, or `x` and `y` in
     * stage metres (a stage fan), never both; an absent axis is not fanned.
     */
    val fan: SpreadFanDto? = null,
    /**
     * `FAN`: where each head starts, a `"pan,tilt"` literal per head — the answer's own
     * [SpreadResponse.base] sent back, or the value of the row being edited. A head absent here is
     * read by the desk.
     */
    val base: List<SpreadBaseDto>? = null,
    /** A stage fan: the previous step's answer, so each head's solve stays on the side it is on. */
    val near: List<AimNearDto>? = null,
)

@Serializable
internal data class SpreadFanDto(
    val pan: Int? = null,
    val tilt: Int? = null,
    /** Stage metres towards SL. */
    val x: Double? = null,
    /** Stage metres upstage. */
    val y: Double? = null,
)

/** A head's start: its `"pan,tilt"` literal and, for a stage fan, the fine bytes it has — a jog's start. */
internal typealias SpreadBaseDto = AimBaseDto

@Serializable
internal data class SpreadResponse(
    val written: List<SpreadWriteDto> = emptyList(),
    val skipped: List<SpreadSkipDto> = emptyList(),
    /** The property's family, when the mask kept it out — then nothing was written. */
    val skippedFamilies: List<String> = emptyList(),
    /** `FAN`: where each head started — the anchor a later request in the same gesture sends back. */
    val base: List<SpreadBaseDto> = emptyList(),
    /** `FAN`: the heads the fan took past the end of their travel, held at it. */
    val clamped: List<CueTargetDto> = emptyList(),
    /** A stage fan: each moved head's pan and tilt in travel degrees, the `near` a later request sends back. */
    val near: List<AimNearDto> = emptyList(),
)

/**
 * One head's literal: the head, the property it actually landed on, and the **value it got** —
 * [CueAssignmentResolver.PropertyValue.serialize]'s form, never the intent. The focused-Look-layer
 * arm (`write = false`) is its reader, and a Look row holds a literal.
 */
@Serializable
internal data class SpreadWriteDto(val target: CueTargetDto, val propertyName: String, val value: String)

@Serializable
internal data class SpreadSkipDto(val target: CueTargetDto, val reason: String)

internal sealed interface SpreadOutcome {
    data class Invalid(val message: String, val code: String = CODE_SPREAD_INVALID) : SpreadOutcome
    data class Done(val response: SpreadResponse) : SpreadOutcome
}

/** What a spread lands between: two endpoint intents, or a fan around each head's own position. */
private sealed interface SpreadShape {
    /** The intent the heads that can take the property are found with. */
    val probe: TemplateIntent

    data class Ends(val from: TemplateIntent, val to: TemplateIntent) : SpreadShape {
        override val probe get() = from
    }

    data class Fan(val pan: Int?, val tilt: Int?, val given: Map<String, PropertyValue.Position>) : SpreadShape {
        override val probe get() = TemplateIntent.Position(0.0, 0.0)
    }

    /** A fan in stage metres: [x] towards SL, [y] upstage. */
    data class StageFan(
        val x: Double,
        val y: Double,
        val given: Map<String, JogStart>,
        val near: Map<String, AimNearDto>,
    ) : SpreadShape {
        override val probe get() = TemplateIntent.Position(0.0, 0.0)
    }
}

/**
 * The body of the route, separable so a test can drive it without HTTP. [owner] and [rigOrder] are
 * the surface fan's: it writes as `surface`, and reads the rig order once per gesture rather than
 * once per detent.
 */
internal fun spreadIntoProgrammer(
    state: State,
    request: SpreadRequest,
    families: Set<uk.me.cormack.lighting7.fx.PropertyMaskGroup>?,
    owner: ProgrammerOwner = ProgrammerOwner.WEB,
    rigOrder: () -> BuskRigOrder? = state::buskRigOrder,
    placementsOf: (Collection<String>) -> Map<String, AimPlacement> = { aimPlacements(state, state.show.project, it) },
): SpreadOutcome {
    val property = TemplateProperty.ofOrNull(request.property)
        ?: return SpreadOutcome.Invalid("'${request.property}' is not a property a spread can carry")
    val curve = SpreadCurve.byName(request.curve)
        ?: return SpreadOutcome.Invalid("Curve '${request.curve}' is not one of ${SpreadCurve.entries.map { it.name }}")
    val over = SpreadOver.byName(request.over)
        ?: return SpreadOutcome.Invalid("Over '${request.over}' is not one of ${SpreadOver.entries.map { it.name }}")
    val order = DistributionStrategy.byName(request.order)
        ?.let { if (it is DistributionStrategy.RANDOM) DistributionStrategy.RANDOM(request.seed) else it }
        ?: return SpreadOutcome.Invalid("Order '${request.order}' is not a distribution strategy")
    if (request.parts < 1) return SpreadOutcome.Invalid("parts must be at least 1")
    val shape = when (request.mode.trim().uppercase()) {
        "ENDS" -> {
            val from = spreadEndpoint(state, request.from, property)
                ?: return SpreadOutcome.Invalid("'from' (${request.from}) is not a ${property.label.lowercase()} intent")
            val to = spreadEndpoint(state, request.to, property)
                ?: return SpreadOutcome.Invalid("'to' (${request.to}) is not a ${property.label.lowercase()} intent")
            // A position spread interpolates in one unit: centre-relative steps and travel degrees mean
            // different things on a head, so a lerp between the two would be a number in neither.
            if (from is TemplateIntent.Position && to is TemplateIntent.Position && from.unit != to.unit) {
                return SpreadOutcome.Invalid("'from' and 'to' must be one position unit (pos: or deg:), not both")
            }
            SpreadShape.Ends(from, to)
        }
        "FAN" -> fanShape(request, property, over).getOrElse { return SpreadOutcome.Invalid(it.message ?: "Bad fan") }
        else -> return SpreadOutcome.Invalid("Mode '${request.mode}' is not one of [ENDS, FAN]")
    }
    if (request.targets.isEmpty()) {
        return SpreadOutcome.Invalid("A spread needs a selection", CODE_SPREAD_NEEDS_SELECTION)
    }
    if (families != null && !maskAllows(families, property.family)) {
        return SpreadOutcome.Done(SpreadResponse(skippedFamilies = listOf(property.family.name)))
    }

    val fixtures = state.show.fixtures
    val skipped = ArrayList<SpreadSkipDto>()

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
            skipped += SpreadSkipDto(target, "not patched")
            continue
        }
        for (member in members) {
            val units = if (over == SpreadOver.CELLS && member is MultiElementFixture<*>) member.elements else listOf(member)
            for (unit in units) heads.putIfAbsent(unit.targetKey, unit)
        }
    }

    // Over HEADS a parent's step lands on its own cells (below), so a cell named beside its parent is
    // already covered — the parent↔cell rule a press reads (`TargetCoverage.covers`). Kept, it would be
    // a second step writing the same cell a second value; the client's marquee drops it the same way.
    if (over == SpreadOver.HEADS) {
        heads.values.removeIf { it is FixtureElement<*> && it.parentFixture.key in heads }
    }

    // Rig order first (LINEAR); the strategy then says where along the curve each sits. A spread
    // over **one group** keeps that group's own member order (venue-visit plan S7) — what the group
    // sheet shows and its effects run in — where rig order places each fixture at its first
    // appearance on the rig, which an earlier group or tile holding it can put anywhere.
    val oneGroup = request.targets.singleOrNull()?.let { TargetRef.ofOrNull(it.type, it.key) } is TargetRef.Group
    val rigOrdered = (if (oneGroup) null else rigOrder())
        ?.sort(heads.keys.map { CueTargetDto(TargetRef.Fixture.TYPE, it) })
        ?.map { heads.getValue(it.key) }
        ?: heads.values.toList()
    val fittedMediaFor: (String) -> uk.me.cormack.lighting7.fixture.media.FittedMedia? = fixtures::fittedMediaFor
    fun media(head: GroupableFixture) = TemplateFanOut.mediaOf(head, fittedMediaFor)
    // **Two passes: the steps that can take the property are found first, and the curve is spread
    // over those alone** (editor-kit session 3 review). A head that resolves nothing — a par in a
    // Position spread, a dimmer-only head in a Colour one — is skipped by name, as before; what
    // changed is that it no longer *consumes a position on the curve*: a marquee on the programmer
    // is geometric and sweeps such heads up with the rest, so two RGB heads among eight rows landed
    // at `0` and `1/7` and *to* never reached the rig. Support is a property of the head and the
    // shape, not of the fraction, so it is asked with `from` (a fan's probe is the centre).
    //
    // **A step lands on the heads [TemplateFanOut] says it stands for** (venue-visit plan S1): over
    // HEADS a multi-head fixture whose property lives only on its cells — the Shehds' colour, on its
    // three zones — is still one step on the curve, and its value lands on every cell that carries
    // the property, where the parent alone resolved nothing and was skipped. Over CELLS each cell is
    // already its own step, and stands for itself.
    val ordered = rigOrdered.mapNotNull { step ->
        val landing = TemplateFanOut.headsFor(step, property.propertyName, fittedMediaFor)
            .filter { TemplateResolver.resolve(it, property.propertyName, shape.probe, media(it)).isSupported }
        if (landing.isEmpty()) {
            val probe = TemplateResolver.resolve(step, property.propertyName, shape.probe, media(step))
            skipped += SpreadSkipDto(
                CueTargetDto(TargetRef.Fixture.TYPE, step.targetKey),
                (probe.note as? TemplateResolver.Note.Unsupported)?.reason ?: "unsupported",
            )
            null
        } else {
            step to landing
        }
    }
    // A stage fan's steps are the heads that can be aimed at all, so one that never can — unplaced,
    // no degrees — takes no place on the curve, as one that cannot take the property takes none.
    val placements = if (shape is SpreadShape.StageFan) {
        placementsOf(ordered.flatMap { (_, landing) -> landing.map { it.targetKey } })
    } else {
        emptyMap()
    }
    val stepped = if (shape !is SpreadShape.StageFan) ordered else ordered.mapNotNull { (step, landing) ->
        val aimable = landing.filter { head ->
            val reason = aimSkipReason(head, placements[head.targetKey])
            if (reason != null) skipped += SpreadSkipDto(CueTargetDto(TargetRef.Fixture.TYPE, head.targetKey), reason)
            reason == null
        }
        if (aimable.isEmpty()) null else step to aimable
    }
    val fractions = SpreadPlan.fractions(stepped.size, order, curve, request.parts)
    val weights = SpreadPlan.fanWeights(stepped.size, order, curve, request.parts)
    val hints = groupHintsForTargets(fixtures, request.targets.mapNotNull { TargetRef.ofOrNull(it.type, it.key) })

    val writes = ArrayList<ProgrammerWriter.PropertyWrite>()
    val written = ArrayList<SpreadWriteDto>()
    val bases = ArrayList<SpreadBaseDto>()
    val clamped = ArrayList<CueTargetDto>()
    val near = ArrayList<AimNearDto>()
    stepped.forEachIndexed { index, (step, landing) ->
        for (head in landing) {
            val target = CueTargetDto(TargetRef.Fixture.TYPE, head.targetKey)
            val sourceGroup = hints[head.targetKey] ?: hints[step.targetKey]
                ?: (head as? FixtureElement<*>)?.parentFixture?.key?.let { hints[it] }
            if (shape is SpreadShape.StageFan) {
                val weight = weights[index]
                val jog = jogHead(
                    state, head, placements[head.targetKey], shape.given[head.targetKey], readLocal = request.write,
                    shape.near[head.targetKey], shape.x * weight, shape.y * weight, JOG_DEFAULT_PLANE_Z,
                )
                jog.base?.let { bases += SpreadBaseDto(target, it.position.serialize(), it.panFine?.toInt(), it.tiltFine?.toInt()) }
                when (jog) {
                    is HeadJog.Skip -> skipped += SpreadSkipDto(target, jog.reason)
                    is HeadJog.Moved -> {
                        writes += jog.propertyWrites(head, sourceGroup)
                        written += SpreadWriteDto(target, "position", jog.position.serialize())
                        near += AimNearDto(target, jog.panDeg, jog.tiltDeg)
                    }
                }
                continue
            }
            val intent = when (shape) {
                is SpreadShape.Ends -> interpolateIntent(shape.from, shape.to, fractions[index])
                is SpreadShape.Fan -> {
                    // A `write: false` host hands in the row it is editing; a head the row lacks
                    // starts from the stage, never from Local, which is another context's.
                    val base = fanBase(state, head, shape.given, readLocal = request.write)
                    if (base == null) {
                        skipped += SpreadSkipDto(target, "no position to fan from")
                        continue
                    }
                    bases += SpreadBaseDto(target, base.serialize())
                    fanIntent(head, base, shape, weights[index]) ?: run {
                        skipped += SpreadSkipDto(target, "position is not DMX-backed")
                        null
                    } ?: continue
                }
                is SpreadShape.StageFan -> error("a stage fan is solved above")
            }
            val resolution = TemplateResolver.resolve(head, property.propertyName, intent, media(head))
            val value = resolution.value
            if (value == null) {
                skipped += SpreadSkipDto(target, (resolution.note as? TemplateResolver.Note.Unsupported)?.reason ?: "unsupported")
                continue
            }
            if (shape is SpreadShape.Fan && resolution.note is TemplateResolver.Note.Clamped) clamped += target
            // A colour-wheel head answers a wheel *slot* (`PropertyValue.Setting`) under its own
            // property (`colourWheel`), and its literal is the slot's level: the cook reads a level on
            // a slot-backed COLOUR row as a slot (`Fixture.Property.settingBacked`), so both arms land
            // it — the write arm as a typed entry, the answer-only arm as a Look row.
            writes += ProgrammerWriter.PropertyWrite(head, resolution.propertyName, value, sourceGroup)
            written += SpreadWriteDto(target, resolution.propertyName, value.serialize())
        }
    }
    // `write = false` answers what *would* land and lands nothing — the Look-layer arm.
    if (request.write && writes.isNotEmpty()) {
        state.show.fxEngine.programmer.writeProperties(owner, writes, fadeMs = request.fadeMs ?: 0)
    }
    return SpreadOutcome.Done(SpreadResponse(written, skipped, base = bases, clamped = clamped, near = near))
}

/** A `FAN` request's amounts and given bases, or the reason it is not one. */
private fun fanShape(request: SpreadRequest, property: TemplateProperty, over: SpreadOver): Result<SpreadShape> = runCatching {
    require(property == TemplateProperty.POSITION) { "A fan is a position spread; '${request.property}' has no position of its own to fan from" }
    val fan = request.fan
    if (fan != null && (fan.x != null || fan.y != null)) return@runCatching stageFanShape(request, fan, over).getOrThrow()
    require(fan != null && (fan.pan != null || fan.tilt != null)) { "A fan needs a pan or a tilt amount, or an x or a y" }
    for ((axis, amount) in listOf("pan" to fan.pan, "tilt" to fan.tilt)) {
        require(amount == null || amount in POSITION_CENTRE_MIN..POSITION_CENTRE_MAX) {
            "The $axis amount must be $POSITION_CENTRE_MIN..$POSITION_CENTRE_MAX centre steps"
        }
    }
    val given = LinkedHashMap<String, PropertyValue.Position>()
    for (entry in request.base.orEmpty()) {
        val position = CueAssignmentResolver.parseAssignmentValue(PropertyCategory.PAN, "position", entry.value) as? PropertyValue.Position
        require(position != null) { "base for '${entry.target.key}' (${entry.value}) is not a pan,tilt position" }
        given[entry.target.key] = position
    }
    SpreadShape.Fan(fan.pan, fan.tilt, given)
}

/** A stage fan's amounts, starts and previous step, or the reason it is not one. */
private fun stageFanShape(request: SpreadRequest, fan: SpreadFanDto, over: SpreadOver): Result<SpreadShape.StageFan> = runCatching {
    require(fan.pan == null && fan.tilt == null) { "A fan is in pan and tilt or in stage metres, not both" }
    require(over == SpreadOver.HEADS) { "A stage fan moves heads; a cell has no placement of its own" }
    for ((axis, amount) in listOf("x" to fan.x, "y" to fan.y)) {
        checkStageCoord(axis, amount)?.let { throw IllegalArgumentException(it) }
    }
    val given = jogStarts(request.base.orEmpty()).getOrThrow()
    SpreadShape.StageFan(fan.x ?: 0.0, fan.y ?: 0.0, given, jogNear(request.near.orEmpty()).getOrThrow())
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
    given: Map<String, PropertyValue.Position>,
    readLocal: Boolean,
): PropertyValue.Position? {
    given[head.targetKey]?.let { return it }
    val positioned = head as? WithPosition ?: return null
    val panSlider = positioned.pan as? DmxSlider ?: return null
    val tiltSlider = positioned.tilt as? DmxSlider ?: return null
    val local = if (readLocal) localPosition(state.show.programmerStore, head) else null
    fun read(slider: DmxSlider): UByte? = state.show.fixtures.controllerOrNull(slider.universe)?.getValue(slider.channelNo)
    return PropertyValue.Position(
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
    val position = entry?.value?.resolved as? PropertyValue.Position
    val catalogue = (head as? Fixture)?.fixtureProperties
    fun axis(fromPosition: UByte?, slider: Slider, category: PropertyCategory, fallbackName: String): UByte? {
        val candidates = ArrayList<Pair<Long, UByte>>(3)
        if (fromPosition != null) candidates += entry!!.seq to fromPosition
        val name = catalogue?.firstOrNull { it.category == category }?.name ?: fallbackName
        store.get(head.targetKey, name)?.let { slot ->
            (slot.value.resolved as? PropertyValue.Slider)?.let { candidates += slot.seq to it.value }
        }
        (slider as? DmxSlider)?.let { dmx ->
            store.getChannelSlot(dmx.universe.universe, dmx.channelNo)?.let { slot ->
                (slot.value.resolved as? PropertyValue.Slider)?.let { candidates += slot.seq to it.value }
            }
        }
        return candidates.maxByOrNull { it.first }?.second
    }
    val pan = axis(position?.pan, positioned.pan, PropertyCategory.PAN, "pan")
    val tilt = axis(position?.tilt, positioned.tilt, PropertyCategory.TILT, "tilt")
    return if (pan == null && tilt == null) null else LocalPosition(pan, tilt)
}

/**
 * [base] moved by the fan: each fanned axis by its amount times [weight], in centre steps on the
 * head's own slider range (the resolver's own inverse), an axis not fanned left on its base exactly.
 * Null for a head whose position is not DMX-backed.
 */
private fun fanIntent(head: GroupableFixture, base: PropertyValue.Position, fan: SpreadShape.Fan, weight: Double): TemplateIntent.Position? {
    val centre = TemplateResolver.centreIntentOf(head, base) ?: return null
    return TemplateIntent.Position(centre.pan + (fan.pan ?: 0) * weight, centre.tilt + (fan.tilt ?: 0) * weight)
}

/**
 * One endpoint: a serialised intent of [property]'s shape, or — for a colour — a `tmpl:{uuid}`
 * resolved to the template's generic colour row (its hex and policy), the way an FX colour
 * reference resolves one. Null when it is neither.
 */
private fun spreadEndpoint(state: State, raw: String, property: TemplateProperty): TemplateIntent? {
    val templateUuid = parseTemplateColourRefUuid(raw)
    val intent = if (templateUuid != null) {
        if (property != TemplateProperty.COLOUR) return null
        val snapshot = state.show.templateRegistry.snapshot(templateUuid) ?: return null
        val rows = genericColourRows(snapshot.rows, { it.propertyName }, { it.isDeferred }) ?: return null
        rows.firstNotNullOfOrNull { row ->
            if (TemplateProperty.ofOrNull(row.propertyName) == TemplateProperty.COLOUR) parseTemplateIntent(row.value) else null
        } ?: return null
    } else {
        parseTemplateIntent(raw) ?: return null
    }
    if (!property.accepts(intent)) return null
    if (intent is TemplateIntent.Colour && TemplateResolver.parseHexColour(intent.hex) == null) return null
    return intent
}

/** The point [t] of the way from [from] to [to], in the intent's own space. Both are one arm. */
internal fun interpolateIntent(from: TemplateIntent, to: TemplateIntent, t: Double): TemplateIntent {
    val f = t.coerceIn(0.0, 1.0)
    fun lerp(a: Double, b: Double) = a + (b - a) * f
    return when (from) {
        is TemplateIntent.Colour -> {
            val target = to as TemplateIntent.Colour
            val a = TemplateResolver.parseHexColour(from.hex)!!
            val b = TemplateResolver.parseHexColour(target.hex)!!
            TemplateIntent.Colour(TemplateResolver.toHex(TemplateResolver.mixLab(a, b, f)), from.policy)
        }
        is TemplateIntent.Percent -> TemplateIntent.Percent(lerp(from.value, (to as TemplateIntent.Percent).value))
        is TemplateIntent.Position -> {
            val target = to as TemplateIntent.Position
            TemplateIntent.Position(lerp(from.pan, target.pan), lerp(from.tilt, target.tilt), from.unit)
        }
        is TemplateIntent.Level -> TemplateIntent.Level(lerp(from.value.toDouble(), (to as TemplateIntent.Level).value.toDouble()).roundToInt())
        is TemplateIntent.Switch -> if (f < 0.5) from else to
    }
}
