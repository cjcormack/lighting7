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
import uk.me.cormack.lighting7.fixture.group.FixtureElement
import uk.me.cormack.lighting7.fixture.group.MultiElementFixture
import uk.me.cormack.lighting7.fx.ProgrammerOwner
import uk.me.cormack.lighting7.fx.ProgrammerWriter
import uk.me.cormack.lighting7.fx.SpreadCurve
import uk.me.cormack.lighting7.fx.SpreadOver
import uk.me.cormack.lighting7.fx.SpreadPlan
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
import uk.me.cormack.lighting7.state.State
import kotlin.math.roundToInt

/** 400 code: the request is malformed — an unknown property, curve, order or over, an endpoint that is not an intent of the property's shape, `parts` below 1. */
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
 * colour ([TemplateResolver.mixLab]), degrees for a position, percent for a level, a DMX byte for
 * an emitter — and each head's literal then goes through the same [TemplateResolver] a template
 * click uses, so the far end of a spread is exactly what a template of that hex would have given
 * that head. The write is one batched [ProgrammerWriter.writeProperties] with owner `WEB`: one
 * `ProgrammerStore.put` per key, so `programmer.entryChanged` carries it, Record captures it and
 * Blind previews it, and Clear releases it like any busked value (D10: a spread is a result, not a
 * template).
 *
 * `order` is a `DistributionStrategy` name: `LINEAR` is **rig order** (`state/BuskRigOrder.kt` — a
 * group in member order, cells in element order), `REVERSE` the other way, `RANDOM` seeded from the
 * request. `over = CELLS` spreads across every cell of every multi-head fixture in the selection;
 * `HEADS` treats each fixture as one step. A `tmpl:{uuid}` endpoint resolves the template's generic
 * colour, as an FX colour reference does. The selection's attribute mask is honoured the way a
 * Look's press honours it: a property outside it writes nothing and answers `skippedFamilies`.
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
    /** Serialised `TemplateIntent`s, or `tmpl:{uuid}` for a colour. */
    val from: String,
    val to: String,
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
)

@Serializable
internal data class SpreadResponse(
    val written: List<SpreadWriteDto> = emptyList(),
    val skipped: List<SpreadSkipDto> = emptyList(),
    /** The property's family, when the mask kept it out — then nothing was written. */
    val skippedFamilies: List<String> = emptyList(),
)

/** One head's literal: the head, the property it actually landed on, and the intent it was given. */
@Serializable
internal data class SpreadWriteDto(val target: CueTargetDto, val propertyName: String, val value: String)

@Serializable
internal data class SpreadSkipDto(val target: CueTargetDto, val reason: String)

internal sealed interface SpreadOutcome {
    data class Invalid(val message: String, val code: String = CODE_SPREAD_INVALID) : SpreadOutcome
    data class Done(val response: SpreadResponse) : SpreadOutcome
}

/** The body of the route, separable so a test can drive it without HTTP. */
internal fun spreadIntoProgrammer(
    state: State,
    request: SpreadRequest,
    families: Set<uk.me.cormack.lighting7.fx.PropertyMaskGroup>?,
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
    val from = spreadEndpoint(state, request.from, property)
        ?: return SpreadOutcome.Invalid("'from' (${request.from}) is not a ${property.label.lowercase()} intent")
    val to = spreadEndpoint(state, request.to, property)
        ?: return SpreadOutcome.Invalid("'to' (${request.to}) is not a ${property.label.lowercase()} intent")
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

    // Rig order first (LINEAR); the strategy then says where along the curve each sits.
    val ordered = state.buskRigOrder()
        ?.sort(heads.keys.map { CueTargetDto(TargetRef.Fixture.TYPE, it) })
        ?.map { heads.getValue(it.key) }
        ?: heads.values.toList()
    val fractions = SpreadPlan.fractions(ordered.size, order, curve, request.parts)
    val hints = groupHintsForTargets(fixtures, request.targets.mapNotNull { TargetRef.ofOrNull(it.type, it.key) })

    val writes = ArrayList<ProgrammerWriter.PropertyWrite>()
    val written = ArrayList<SpreadWriteDto>()
    ordered.forEachIndexed { index, head ->
        val target = CueTargetDto(TargetRef.Fixture.TYPE, head.targetKey)
        val intent = interpolateIntent(from, to, fractions[index])
        val resolution = TemplateResolver.resolve(head, property.propertyName, intent)
        val value = resolution.value
        if (value == null) {
            skipped += SpreadSkipDto(target, (resolution.note as? TemplateResolver.Note.Unsupported)?.reason ?: "unsupported")
            return@forEachIndexed
        }
        val parentKey = (head as? FixtureElement<*>)?.parentFixture?.key
        writes += ProgrammerWriter.PropertyWrite(
            head, resolution.propertyName, value,
            sourceGroup = hints[head.targetKey] ?: parentKey?.let { hints[it] },
        )
        written += SpreadWriteDto(target, resolution.propertyName, intent.serialize())
    }
    if (writes.isNotEmpty()) {
        state.show.fxEngine.programmer.writeProperties(ProgrammerOwner.WEB, writes, fadeMs = request.fadeMs ?: 0)
    }
    return SpreadOutcome.Done(SpreadResponse(written, skipped))
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
            TemplateIntent.Position(lerp(from.panDeg, target.panDeg), lerp(from.tiltDeg, target.tiltDeg))
        }
        is TemplateIntent.Level -> TemplateIntent.Level(lerp(from.value.toDouble(), (to as TemplateIntent.Level).value.toDouble()).roundToInt())
        is TemplateIntent.Switch -> if (f < 0.5) from else to
    }
}
