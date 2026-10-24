package uk.me.cormack.lighting7.routes

import io.ktor.http.HttpStatusCode
import uk.me.cormack.lighting7.models.Timing
import io.ktor.resources.Resource
import io.ktor.server.request.receive
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.fx.FxEngine
import uk.me.cormack.lighting7.fx.FxInstance
import uk.me.cormack.lighting7.fx.FxStacking
import uk.me.cormack.lighting7.fx.AttributeFamily
import uk.me.cormack.lighting7.fx.TargetCoverage
import uk.me.cormack.lighting7.fx.maskAllows
import uk.me.cormack.lighting7.fx.familyForAttribute
import uk.me.cormack.lighting7.fx.parseFamilies
import uk.me.cormack.lighting7.fx.familyMasterUuidFor
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.state.State

/** 400 code: the bare effect's family is outside the selection's attribute mask, so nothing would land. */
internal const val CODE_FX_OUTSIDE_MASK = "FX_OUTSIDE_MASK"

/** 400 code: a bare effect press with no targets — a Local effect has nothing to run on. */
internal const val CODE_FX_NEEDS_SELECTION = "FX_NEEDS_SELECTION"

/**
 * `POST /projects/{id}/programmer/fx` — the **bare effect press** (apply-model plan §3.4, D5): an
 * effect type from the library, with parameters and no record, onto the selection as **one Local
 * effect** over it (effects plan D3) — a group stays one group effect, several targets one effect
 * spread over them in the order given. Local means the programmer's own: in its priority band, owned
 * by no layer, Look or cue ([FxInstance.isLocalFx]), so Clear and Release take it and Record
 * writes it as an ad-hoc cue effect.
 *
 * - **The mask.** The effect's family — the group of the property it drives ([fxFamily]) — must
 *   be in the selection's mask (`families`, absent = every attribute), else 400
 *   `FX_OUTSIDE_MASK`. No targets is 400 `FX_NEEDS_SELECTION`.
 * - **The timing.** No `cycle` is one bar, and a beat effect that names no master and is not
 *   on Seconds is stamped with the master whose `family` is its library family, else master 1
 *   (venue-visit plan D16, [familyMasterUuidFor]) — the stamp MCP's effect tools make. The category,
 *   not the property group the mask reads.
 * - **Local replacement.** Before it starts, the press takes the selected heads off every other
 *   Local effect of its family, by the same reading: one whose every painted head is selected stops; one that paints some
 *   of them and some others is narrowed — rewritten as one instance per head it keeps, each where it
 *   was ([uk.me.cormack.lighting7.fx.FxEngine.headCopies], the split with no edit). A layer's effect,
 *   a palette's or a Look's, is left alone: the press's twin for layers is the family replacement in
 *   `fx/LayerPress.kt`. All of it, the new effect included, is one engine mutation, so no head
 *   drops to the layer below between the old effect and the new.
 * - **Stacking** (FX editor plan D6, §3.6). `stack: true` skips the replacement: the FX is added on
 *   top of the Local FX already running, and nothing is stopped or narrowed. A blend or Amount
 *   (`size`) the request leaves out takes its family's stacking start ([FxStacking]): Intensity
 *   *Within it*, Position *Around current position* with its centres at 128, Colour and Beam
 *   *Replace it* at 50 %.
 *
 * A new Local FX takes the top of its lanes (FX editor plan §3.5, [FxEngine.addFx]), so it
 * applies above every Local FX it shares a key with, stacked or not.
 *
 * Every field `POST /fx/add` reads that describes the effect is taken (the timing and dynamics
 * fields of S15 and the effects plan's sessions 1–2 among them), and built by the same
 * [buildFxInstance]; this route only fixes where it runs and whose it is.
 */
internal fun Route.routeApiRestProgrammerFx(state: State) {
    post<ProgrammerFxResource> { resource ->
        withCurrentProject(state, resource.projectId) { _ ->
            val request = call.receive<ProgrammerFxRequest>()
            val families = try {
                parseFamilies(request.families)
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Bad mask"))
                return@withCurrentProject
            }
            when (val outcome = pressBareFx(state, request, families)) {
                is BareFxOutcome.Invalid ->
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(outcome.message, outcome.code))
                is BareFxOutcome.Done -> call.respond(outcome.response)
            }
        }
    }
}

@Resource("/{projectId}/programmer/fx")
internal data class ProgrammerFxResource(val projectId: String)

/** [AddFxRequest]'s effect fields, less where it runs and whose it is: the selection, always Local. */
@Serializable
internal data class ProgrammerFxRequest(
    val fxType: String,
    val attribute: String,
    /** The selection, in the order the effect spreads over it: fixtures, cells and groups. */
    val targets: List<CueTargetDto> = emptyList(),
    /** The selection's attribute mask; absent is every attribute. */
    val families: List<String>? = null,
    val parameters: Map<String, String> = emptyMap(),
    /** The FX's own default cycle when absent (venue-visit plan D16). */
    val cycle: Double? = null,
    /** `OVERRIDE` when absent, or the family's stacking start under [stack]. */
    val blendMode: String? = null,
    val startOnBeat: Boolean = true,
    val phaseOffset: Double = 0.0,
    val order: String? = null,
    val elementMode: String? = null,
    val elementFilter: String? = null,
    val timing: Timing? = null,
    /** Absent on a beat effect not on Seconds: the master tagged for its family, else master 1 (D16). */
    val speedMasterUuid: String? = null,
    val rateSpeedMasterUuid: String? = null,
    val seconds: Boolean = false,
    /** 1.0 when absent, or the family's stacking start under [stack]. */
    val amount: Double? = null,
    val direction: String? = null,
    val runs: Int? = null,
    val spread: Double = 1.0,
    val wings: Int = 1,
    val segments: Int = 1,
    val parts: Int = 0,
    /** Add the FX on top of the Local FX on the selection, replacing none (FX editor plan D6). */
    val stack: Boolean = false,
)

/**
 * The press's answer: the new effect, the Local effects it stopped, and those it narrowed with the
 * copies that now run their heads it left.
 */
@Serializable
internal data class ProgrammerFxResponse(
    val fxId: Long,
    val stopped: List<Long> = emptyList(),
    val narrowed: List<NarrowedFxDto> = emptyList(),
)

/** A Local effect the press took some heads off: [fxId] is gone, and [into] run the heads it kept. */
@Serializable
internal data class NarrowedFxDto(val fxId: Long, val into: List<Long>)

internal sealed interface BareFxOutcome {
    data class Invalid(val message: String, val code: String? = null) : BareFxOutcome
    data class Done(val response: ProgrammerFxResponse) : BareFxOutcome
}

/** The body of the route, separable so a test can drive it without HTTP. */
internal fun pressBareFx(
    state: State,
    request: ProgrammerFxRequest,
    families: Set<AttributeFamily>?,
): BareFxOutcome {
    if (request.targets.isEmpty()) {
        return BareFxOutcome.Invalid("A Local effect needs a selection", CODE_FX_NEEDS_SELECTION)
    }
    val engine = state.show.fxEngine
    val stamped = request.speedMasterUuid ?: if (request.seconds) {
        null
    } else {
        familyMasterUuidFor(state.show.fxRegistry, engine.speedMasters, request.fxType)?.toString()
    }
    fun build(start: FxStacking.Start?) = buildFxInstance(
        state,
        AddFxRequest(
            fxType = request.fxType,
            attribute = request.attribute,
            targets = request.targets,
            cycle = request.cycle,
            blendMode = request.blendMode ?: start?.blendMode?.name ?: "OVERRIDE",
            startOnBeat = request.startOnBeat,
            phaseOffset = request.phaseOffset,
            parameters = request.parameters + stackingParameters(request, start),
            order = request.order,
            elementMode = request.elementMode,
            elementFilter = request.elementFilter,
            timing = request.timing,
            speedMasterUuid = stamped,
            rateSpeedMasterUuid = request.rateSpeedMasterUuid,
            seconds = request.seconds,
            amount = request.amount ?: start?.amount ?: 1.0,
            direction = request.direction,
            runs = request.runs,
            spread = request.spread,
            wings = request.wings,
            segments = request.segments,
            parts = request.parts,
            programmerOwned = true,
        ),
    )
    var instance = try {
        build(start = null)
    } catch (e: Exception) {
        return BareFxOutcome.Invalid(e.message ?: "Could not start the effect")
    }
    val family = fxFamily(state, instance)
    if (families != null && !maskAllows(families, family)) {
        return BareFxOutcome.Invalid(
            "'${request.attribute}' is ${family?.name ?: "no attribute"}, outside the selection's mask",
            CODE_FX_OUTSIDE_MASK,
        )
    }

    if (request.stack) {
        // The family is read off a built instance, so one that stacks is built again on its start.
        if (family != null && (request.blendMode == null || request.amount == null)) {
            val declared = state.show.fxRegistry.getRegistration(request.fxType)?.parameters
                ?.mapTo(HashSet()) { it.name }.orEmpty()
            instance = try {
                build(FxStacking.startFor(family, declared))
            } catch (e: Exception) {
                return BareFxOutcome.Invalid(e.message ?: "Could not start the effect")
            }
        }
        engine.addFx(instance)
        return BareFxOutcome.Done(ProgrammerFxResponse(fxId = instance.id))
    }

    // Local replacement: the selected heads come off every other Local effect of this family.
    val coverage = TargetCoverage { state.show.fixtures }
    val selected = coverage.expand(request.targets).toSet()
    fun isSelected(head: String) = coverage.covers(selected, CueTargetDto(TargetRef.Fixture.TYPE, head))
    /** [other]'s copies less the selected heads, empty to stop it, null when the press leaves it. */
    fun takeOff(other: FxInstance): List<FxInstance>? {
        if (!other.isLocalFx || family == null || fxFamily(state, other) != family) return null
        val heads = engine.paintedHeads(other).map { it.key }
        val taken = heads.filterTo(HashSet()) { isSelected(it) }
        if (taken.isEmpty()) return null
        return if (taken.size == heads.size) emptyList() else engine.headCopies(other, drop = taken)
    }
    fun plan(candidates: List<FxInstance>) =
        candidates.mapNotNull { other -> takeOff(other)?.let { other to it } }.toMap(LinkedHashMap())

    val done = LinkedHashMap<Long, List<FxInstance>>()
    var replace = plan(engine.getActiveFx())
    var fresh = listOf(instance)
    for (attempt in 0 until SPLIT_ATTEMPTS) {
        val replaced = engine.replaceFx(replace, fresh)
        fresh = emptyList()
        for ((parent, copies) in replace) if (parent.id in replaced) done[parent.id] = copies
        // One another request swapped meanwhile is taken off its current instance.
        replace = plan(replace.keys.filter { it.id !in replaced }.mapNotNull { engine.getFx(it.id) })
        if (replace.isEmpty()) break
    }
    val (stopped, narrowed) = done.entries.partition { it.value.isEmpty() }
    return BareFxOutcome.Done(
        ProgrammerFxResponse(
            fxId = instance.id,
            stopped = stopped.map { it.key },
            narrowed = narrowed.map { (id, copies) -> NarrowedFxDto(id, copies.map { it.id }) },
        ),
    )
}

/**
 * The parameters a stacking start adds: those it names that the request does not set, and only when
 * the start's blend is the one used — Position's centres belong to *Around*.
 */
private fun stackingParameters(request: ProgrammerFxRequest, start: FxStacking.Start?): Map<String, String> {
    if (start == null || request.blendMode != null) return emptyMap()
    return start.parameters.filterKeys { it !in request.parameters }
}

/**
 * The attribute family an effect lands on: its property's group on the first head it paints
 * ([familyForAttribute], as Record and Include read it), so an Intensity Pulse on `uv` is
 * Colour. Null when it paints nothing; a null family is in no mask and replaces nothing. Agreed
 * with Chris during session 2 over the library category an effect palette's family reads.
 */
internal fun fxFamily(state: State, effect: FxInstance): AttributeFamily? {
    val head = state.show.fxEngine.paintedHeads(effect).firstOrNull() ?: return null
    val fixture = runCatching { state.show.fixtures.untypedGroupableFixture(head.key) }.getOrNull() ?: return null
    return familyForAttribute(fixture, effect.target.attribute)
}

/** Which heads an effect edit is for: [planSplit]'s answer. */
internal sealed interface SplitPlan {
    /** Every head the effect paints, or no `targets`: today's in-place edit. */
    data object InPlace : SplitPlan

    /** Some of its heads, by key: split those off. */
    data class Heads(val selected: Set<String>) : SplitPlan

    /** The edit cannot be made as asked. */
    data class Refusal(val outcome: FxUpdateOutcome) : SplitPlan
}

/**
 * Which of [effect]'s heads an edit naming [targets] is for (apply-model plan §3.4, D5 — MagicQ's
 * rule): a painted head is named when [targets] cover it as a press would — a group by its members,
 * a cell by itself or its fixture ([TargetCoverage.covers]). Naming all of them, or sending no
 * targets, is the in-place edit; naming some splits them off, which only a **Local** effect may do
 * (409 [CODE_FX_SPLIT_LAYER_FX] for a programmer layer's, whose split would need its layer's
 * — apply-model plan P5; [CODE_FX_SPLIT_NOT_LOCAL] for a cue's or a manual one). Naming none of
 * them, or a target type that is neither fixture nor group, is refused. An effect that paints nothing
 * is edited in place: there is nothing to split.
 */
internal fun planSplit(state: State, effect: FxInstance, targets: List<CueTargetDto>?): SplitPlan {
    if (targets == null) return SplitPlan.InPlace
    targets.withIndex().firstOrNull { TargetRef.ofOrNull(it.value.type, it.value.key) == null }?.let { (i, t) ->
        return SplitPlan.Refusal(FxUpdateOutcome.Refused("targets[$i]: '${t.type}' is not a fixture or a group"))
    }
    val heads = state.show.fxEngine.paintedHeads(effect).map { it.key }
    if (heads.isEmpty()) return SplitPlan.InPlace
    val coverage = TargetCoverage { state.show.fixtures }
    val named = coverage.expand(targets).toSet()
    val selected = heads.filterTo(LinkedHashSet()) { coverage.covers(named, CueTargetDto(TargetRef.Fixture.TYPE, it)) }
    return when {
        selected.isEmpty() -> SplitPlan.Refusal(
            FxUpdateOutcome.Refused("targets name none of the heads effect ${effect.id} runs on"),
        )
        selected.size == heads.size -> SplitPlan.InPlace
        effect.programmerLayerFxKey != null -> SplitPlan.Refusal(
            FxUpdateOutcome.Conflict(
                CODE_FX_SPLIT_LAYER_FX,
                "Effect ${effect.id} belongs to a programmer layer, and splits only with it — edit it on " +
                    "all its heads, or Detach the layer first",
            ),
        )
        !effect.isLocalFx -> SplitPlan.Refusal(
            FxUpdateOutcome.Conflict(
                CODE_FX_SPLIT_NOT_LOCAL,
                "Effect ${effect.id} is not the programmer's own — only a Local effect splits",
            ),
        )
        else -> SplitPlan.Heads(selected)
    }
}

/**
 * Swap effect [fxId] for its [FxEngine.headCopies] less [drop], answering the copies, or null
 * when it is no longer running. Tried again when another request swapped the instance between the
 * read and the swap (a live edit's frame does), so a busy effect is not reported gone.
 */
internal fun splitIntoHeads(state: State, fxId: Long, drop: Set<String> = emptySet()): List<FxInstance>? {
    val engine = state.show.fxEngine
    repeat(SPLIT_ATTEMPTS) {
        val current = engine.getFx(fxId) ?: return null
        val copies = engine.headCopies(current, drop)
        if (current.id in engine.replaceFx(mapOf(current to copies))) return copies
    }
    return null
}

private const val SPLIT_ATTEMPTS = 3
