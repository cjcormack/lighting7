package uk.me.cormack.lighting7.routes

import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.request.receive
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import uk.me.cormack.lighting7.fixture.group.FixtureElement
import uk.me.cormack.lighting7.fx.FxInstance
import uk.me.cormack.lighting7.fx.FxLadderRung
import uk.me.cormack.lighting7.fx.FxOutput
import uk.me.cormack.lighting7.fx.FxSampleClock
import uk.me.cormack.lighting7.fx.FxSampleRequest
import uk.me.cormack.lighting7.fx.FxSampleSeries
import uk.me.cormack.lighting7.fx.FxSampleWindow
import uk.me.cormack.lighting7.fx.FxSampledHead
import uk.me.cormack.lighting7.fx.FxSampler
import uk.me.cormack.lighting7.fx.FxSamples
import uk.me.cormack.lighting7.fx.MasterClock
import uk.me.cormack.lighting7.fx.PropertyMaskGroup
import uk.me.cormack.lighting7.fx.SpreadShaping
import uk.me.cormack.lighting7.fx.TargetCoverage
import uk.me.cormack.lighting7.fx.TimingSource
import uk.me.cormack.lighting7.fx.createFxWithPalettes
import uk.me.cormack.lighting7.fx.maskGroupForProperty
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.state.State
import java.util.Base64

/** 400 code: the preview named a problem; every one is in `problems`. */
internal const val CODE_FX_PREVIEW_INVALID = "FX_PREVIEW_INVALID"

/**
 * `POST /projects/{id}/fx/preview` — what the selection's heads will hold over a window (FX editor
 * plan §3.3, D7–D9), for the FX editor's graph: running FX, drafts (an FX not yet added) and running
 * FX with an edit not yet sent, sampled by the desk's own [FxSampler]. Current project only (it needs
 * the rig and the programmer), both roles. **Nothing is written**: no FX is added, no instance field
 * moves, no DMX is sent (P4).
 *
 * - **`fx`**: `{fxId}`, a running FX; `{draft}`, `POST /fx/add`'s body, built by the same write
 *   boundary ([buildFxInstance]) and never added, its `targets` the request's when it names none;
 *   `{fxId, draft}`, the running FX with `draft` — `PUT /fx/{id}`'s body — applied through the same
 *   parse the live edit runs ([parseFxUpdate]), to a copy ([uk.me.cormack.lighting7.fx.FxEngine.editedCopy]).
 *   The first entry is the picked FX: its cycle sets the window and its Order the rows' order.
 * - **`include`**: `STACK` folds every running FX reaching the rows as well; `ONLY` the ones asked for.
 * - **`window`**: one of `cycles` (of the picked FX), `beats` (of its master — a wall-clock FX's rate
 *   master, master 1 for one on its own seconds) or `seconds`; two cycles when none. Longer than
 *   [MAX_WINDOW_CYCLES], [MAX_WINDOW_BEATS] or [MAX_WINDOW_SECONDS] is refused.
 * - **`solo`**: an index into the first [FxSampler.MAX_FX] of `fx`; that FX's family lane is drawn as
 *   though it were alone over what is underneath (D14).
 *
 * The rows are the heads `targets` cover that the sampled FX paint — under `STACK`, that any running
 * FX paints too, so every FX reaching the selection is folded; with no targets, the asked-for FX's
 * heads — grouped by fixture in the picked FX's Order, each with its place in rig order, and cut at
 * the row cap on a fixture's end. A target the rig lacks is a problem. Past the sampler's caps the answer is cut and `truncated` says which. 400
 * [CODE_FX_PREVIEW_INVALID] names every problem at once.
 */
internal fun Route.routeApiRestFxPreview(state: State) {
    post<FxPreviewResource> { resource ->
        withCurrentProject(
            state,
            resource.projectId,
            { p -> "Cannot preview FX in project '${p.name}' - only the current project is running" },
        ) { _ ->
            val request = try {
                call.receive<FxPreviewRequest>()
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Bad request"))
                return@withCurrentProject
            }
            when (val outcome = previewFx(state, request)) {
                is FxPreviewOutcome.Invalid -> call.respond(
                    HttpStatusCode.BadRequest,
                    FxPreviewRefusal(outcome.problems.joinToString("; "), CODE_FX_PREVIEW_INVALID, outcome.problems),
                )
                is FxPreviewOutcome.Done -> call.respond(outcome.response)
            }
        }
    }
}

@Resource("/{projectId}/fx/preview")
internal data class FxPreviewResource(val projectId: String)

@Serializable
internal data class FxPreviewRequest(
    /** The selection, in its order: the heads to draw. */
    val targets: List<CueTargetDto> = emptyList(),
    val fx: List<FxPreviewEntry> = emptyList(),
    /** `STACK` or `ONLY`. */
    val include: String = "STACK",
    val window: FxPreviewWindow = FxPreviewWindow(),
    /** An index into [fx]. */
    val solo: Int? = null,
)

/** A running FX ([fxId]), a draft ([draft], an add's body) or a running FX with an unsent edit (both). */
@Serializable
internal data class FxPreviewEntry(val fxId: Long? = null, val draft: JsonObject? = null)

/** At most one of these; two cycles when none. */
@Serializable
internal data class FxPreviewWindow(val cycles: Double? = null, val beats: Double? = null, val seconds: Double? = null)

@Serializable
internal data class FxPreviewRefusal(val error: String, val code: String, val problems: List<String>)

@Serializable
internal data class FxPreviewResponse(
    val window: FxPreviewWindowAnswer,
    /** The playhead: each clock the sampled FX run on, as it read at the window's start. */
    val clock: List<FxPreviewClock>,
    /** The heads drawn, in graph order. */
    val rows: List<FxPreviewRow>,
    /** Intensity, Colour, Position, Beam, then any attribute no family claims. */
    val lanes: List<FxPreviewLane>,
    /** Every FX sampled, in apply order (bottom first). */
    val fx: List<FxPreviewFx>,
    /** `SAMPLES`, `ROWS` and `FX`: the caps the answer was cut at. */
    val truncated: List<String> = emptyList(),
)

/**
 * The window sampled: its length in beats — of the picked FX's master, a wall-clock FX's rate master,
 * or master 1 for one on its own seconds, as a `beats` window counts them — and in seconds.
 */
@Serializable
internal data class FxPreviewWindowAnswer(val beats: Double, val seconds: Double, val samples: Int, val sampleMs: Double)

/**
 * A clock at the window's start: a speed master's tick and beat length, or `seconds: true` for FX
 * that run on their own seconds (*Seconds*, or a wall-clock FX with no rate master).
 */
@Serializable
internal data class FxPreviewClock(
    val speedMasterUuid: String? = null,
    val seconds: Boolean = false,
    val tickAtStart: Long? = null,
    val msPerBeat: Double? = null,
)

/** A head: its fixture, its label, its element index on a multi-element fixture, its place in rig order. */
@Serializable
internal data class FxPreviewRow(
    val key: String,
    val fixtureKey: String,
    val label: String,
    val element: Int? = null,
    val rigIndex: Int,
)

@Serializable
internal data class FxPreviewLane(val family: String?, val attributes: List<FxPreviewAttribute>)

/** `SLIDER`, `COLOUR` or `POSITION`, read from the samples' planes. */
@Serializable
internal data class FxPreviewAttribute(val attribute: String, val kind: String, val rows: List<FxPreviewAttributeRow>)

/**
 * One row of one attribute: [underneath] (one sample, held across the window), [result] and each FX's
 * own output before Amount, in apply order. [parked]: every channel is parked, and the park is sent.
 */
@Serializable
internal data class FxPreviewAttributeRow(
    /** An index into [FxPreviewResponse.rows]. */
    val row: Int,
    val parked: Boolean = false,
    val underneath: FxPreviewSeries,
    val result: FxPreviewSeries,
    val fx: List<FxPreviewOutput>,
)

/** One FX's output on a row; [heldBackReason] (`STOMP`, `PROGRAMMER`, `BLIND`) when left out of the result. */
@Serializable
internal data class FxPreviewOutput(
    /** An index into [FxPreviewResponse.fx]. */
    val fx: Int,
    val output: FxPreviewSeries,
    val heldBackReason: String? = null,
)

/**
 * Samples as §3.2 sends them: each plane base64, a byte a sample (`level`; `red`, `green`, `blue` and
 * each bundled `white`, `amber`, `uv` the head has; `pan`, `tilt`). A Steps FX's own output carries
 * the step each sample shows ([steps], indices into [stepLabels]) beside its value on the head.
 */
@Serializable
internal data class FxPreviewSeries(
    val planes: Map<String, String>,
    val steps: List<Int>? = null,
    val stepLabels: List<String>? = null,
)

/**
 * One FX sampled. [fxId] for a running FX (an edited one too), [entry] for one the request asked for
 * (an index into its `fx`). The per-row lists line up with [FxPreviewResponse.rows], null where it
 * paints no such head: its phase [offsets], the samples where its [starts] fall, the sample ranges it
 * is [holding] (`[from, to]`, inclusive) and each step of its offset [ladder].
 */
@Serializable
internal data class FxPreviewFx(
    val fxId: Long? = null,
    val entry: Int? = null,
    val fxType: String,
    val family: String?,
    val attribute: String,
    val running: Boolean,
    /** The master it runs on, matching a [FxPreviewClock]; null on its own seconds. */
    val speedMasterUuid: String? = null,
    val offsets: List<Double?>,
    val starts: List<List<Int>?>,
    val holding: List<List<List<Int>>?>,
    /** Its step count after the spread's shape: a step chase's steps. */
    val slots: Int,
    val ladder: FxPreviewLadder,
    /** A stateful FX, sampled from a fresh start: its shape, not the stage's frames. */
    val representative: Boolean = false,
)

/** The offset ladder (D9): each row's rank and rank count after Order, Segments, Wings and Parts; then Spread's offset. */
@Serializable
internal data class FxPreviewLadder(
    val order: FxPreviewRung,
    val segments: FxPreviewRung,
    val wings: FxPreviewRung,
    val parts: FxPreviewRung,
    val spread: List<Double?>,
)

@Serializable
internal data class FxPreviewRung(val ranks: List<Double?>, val counts: List<Int?>)

internal sealed interface FxPreviewOutcome {
    data class Invalid(val problems: List<String>) : FxPreviewOutcome
    data class Done(val response: FxPreviewResponse) : FxPreviewOutcome
}

/** A window longer than this many of the picked FX's cycles is refused. */
internal const val MAX_WINDOW_CYCLES = 64.0

/** … or this many beats. */
internal const val MAX_WINDOW_BEATS = 256.0

/** … or this many seconds. */
internal const val MAX_WINDOW_SECONDS = 600.0

/** Drafts are decoded strictly, so a misspelled field is named rather than ignored. */
private val DRAFT_JSON = Json {
    ignoreUnknownKeys = false
    explicitNulls = false
}

/** The body of the route, separable so a test can drive it without HTTP, on a [clock] it holds still. */
internal fun previewFx(state: State, request: FxPreviewRequest, clock: FxSampleClock? = null): FxPreviewOutcome {
    val engine = state.show.fxEngine
    val problems = ArrayList<String>()

    val include = FxSampleRequest.Include.entries.firstOrNull { it.name == request.include }
    if (include == null) problems += "include: '${request.include}' is not STACK or ONLY"
    val window = windowOf(request.window, problems)
    targetProblems(state, request.targets, problems)
    if (request.fx.isEmpty()) problems += "fx: nothing to preview"
    if (request.solo != null) {
        if (request.solo !in request.fx.indices) problems += "solo: ${request.solo} names no fx entry"
        else if (request.solo >= FxSampler.MAX_FX) problems += "solo: fx[${request.solo}] is past the ${FxSampler.MAX_FX} FX a preview samples"
    }

    val asked = request.fx.mapIndexed { i, entry -> instanceFor(state, request, i, entry, problems) }
    if (problems.isNotEmpty()) return FxPreviewOutcome.Invalid(problems)
    val fx = asked.map { checkNotNull(it) }

    val sampler = engine.sampler { state.show.fxRegistry.createFxWithPalettes(state.show.paletteRegistry, it.fxTypeId, it.effect.parameters) }
    val clock = clock ?: sampler.clock()
    val reads = sampler.snapshot()
    val covered = coveredBy(state, request.targets)
    // Under STACK a selection's rows are every head anything paints in it, so every FX reaching the
    // selected heads is folded; with no selection, the asked-for FX's heads alone.
    val sampled = fx.take(FxSampler.MAX_FX)
    val cut = fx.drop(FxSampler.MAX_FX).toSet()
    val painting = if (include == FxSampleRequest.Include.STACK && request.targets.isNotEmpty()) {
        sampled + sampler.running().filter { it !in cut }
    } else sampled
    // Rig order is the order the FX's walks list the heads; graph order the picked FX's Order over it.
    val heads = sampler.heads(painting, reads).filter { covered(it.key) }
    val rigIndex = heads.withIndex().associate { (i, head) -> head.key to i }
    val (rows, rowsCut) = wholeFixtures(state, graphOrder(state, heads, rigIndex))
    val samples = sampler.sample(FxSampleRequest(fx, checkNotNull(include), rows, checkNotNull(window)), clock, reads)
    val solo = request.solo?.let { soloResults(sampler, reads, fx[it], samples, clock) }

    return FxPreviewOutcome.Done(answer(state, sampler, reads, samples, fx, rigIndex, clock, solo, rowsCut))
}

/** The head's fixture: its parent for a cell, else itself. */
private fun fixtureOf(state: State, key: String): String =
    (runCatching { state.show.fixtures.untypedGroupableFixture(key) }.getOrNull() as? FixtureElement<*>)?.parentFixture?.key ?: key

/**
 * [rows] cut to [FxSampler.MAX_ROWS] at a fixture's end, so the cap drops whole fixtures — or at the
 * cap itself when the first fixture alone is longer — and whether anything was cut.
 */
private fun wholeFixtures(state: State, rows: List<String>): Pair<List<String>, Boolean> {
    if (rows.size <= FxSampler.MAX_ROWS) return rows to false
    var end = 0
    while (end < rows.size) {
        val fixture = fixtureOf(state, rows[end])
        var next = end
        while (next < rows.size && fixtureOf(state, rows[next]) == fixture) next++
        if (next > FxSampler.MAX_ROWS) break
        end = next
    }
    return rows.take(if (end == 0) FxSampler.MAX_ROWS else end) to true
}

/** Each target a fixture, cell or group the rig has, or a problem naming it. */
private fun targetProblems(state: State, targets: List<CueTargetDto>, problems: MutableList<String>) {
    val fixtures = state.show.fixtures
    for ((i, target) in targets.withIndex()) {
        val known = when (TargetRef.ofOrNull(target.type, target.key)) {
            is TargetRef.Fixture -> runCatching { fixtures.untypedGroupableFixture(target.key) }.isSuccess
            is TargetRef.Group -> runCatching { fixtures.untypedGroup(target.key) }.isSuccess
            else -> {
                problems += "targets[$i]: '${target.type}' is not a fixture or a group"
                continue
            }
        }
        if (!known) problems += "targets[$i]: the rig has no ${target.type} '${target.key}'"
    }
}

/**
 * The rows in graph order (D8): fixtures in the order of their first head under the picked FX's Order,
 * each fixture's heads together beneath it in that Order, ties in rig order.
 */
private fun graphOrder(state: State, heads: List<FxSampledHead>, rigIndex: Map<String, Int>): List<String> {
    val byHead = compareBy<FxSampledHead, Double?>(nullsLast()) { it.orderRank }.thenBy { rigIndex.getValue(it.key) }
    val fixtureOf = heads.associate { it.key to fixtureOf(state, it.key) }
    val leader = heads.groupBy { fixtureOf.getValue(it.key) }.mapValues { (_, own) -> own.minWith(byHead) }
    return heads.sortedWith(
        Comparator<FxSampledHead> { a, b -> byHead.compare(leader.getValue(fixtureOf.getValue(a.key)), leader.getValue(fixtureOf.getValue(b.key))) }
            .then(byHead),
    ).map { it.key }
}

private fun windowOf(window: FxPreviewWindow, problems: MutableList<String>): FxSampleWindow? {
    val named = listOfNotNull(
        window.cycles?.let { Triple("cycles", it, MAX_WINDOW_CYCLES) },
        window.beats?.let { Triple("beats", it, MAX_WINDOW_BEATS) },
        window.seconds?.let { Triple("seconds", it, MAX_WINDOW_SECONDS) },
    )
    if (named.size > 1) {
        problems += "window: name one of cycles, beats and seconds, not ${named.joinToString(" and ") { it.first }}"
        return null
    }
    val (name, value, cap) = named.firstOrNull() ?: return FxSampleWindow.Cycles(2.0)
    if (!value.isFinite() || value <= 0.0) {
        problems += "window.$name: $value is not a length"
        return null
    }
    if (value > cap) {
        problems += "window.$name: $value is over the cap of ${cap.toInt()}"
        return null
    }
    return when (name) {
        "cycles" -> FxSampleWindow.Cycles(value)
        "beats" -> FxSampleWindow.Beats(value)
        else -> FxSampleWindow.Seconds(value)
    }
}

/** The instance [entry] names, or null with its problems added. Nothing is added to the engine. */
private fun instanceFor(state: State, request: FxPreviewRequest, i: Int, entry: FxPreviewEntry, problems: MutableList<String>): FxInstance? {
    val where = "fx[$i]"
    val running = entry.fxId?.let { id ->
        state.show.fxEngine.getFx(id) ?: run {
            problems += "$where: no FX $id is running"
            return null
        }
    }
    val draft = entry.draft
    return when {
        running == null && draft == null -> {
            problems += "$where: names neither fxId nor draft"
            null
        }
        running != null && draft == null -> running
        running != null -> {
            val update = decode<UpdateFxRequest>(draft!!, where, problems) ?: return null
            when (val parsed = parseFxUpdate(state, running, update)) {
                is FxUpdateParse.Refusal -> {
                    problems += "$where: ${refusalMessage(parsed.outcome)}"
                    null
                }
                is FxUpdateParse.Parsed -> if (parsed.splitHeads != null) {
                    problems += "$where: a draft for some of FX ${running.id}'s heads is not previewed — send it without targets"
                    null
                } else {
                    state.show.fxEngine.editedCopy(running, parsed.edit)
                }
            }
        }
        else -> {
            val add = decode<AddFxRequest>(draft!!, where, problems) ?: return null
            val targeted = if (add.targets == null && add.fixtureKey.isEmpty()) add.copy(targets = request.targets) else add
            try {
                buildFxInstance(state, targeted)
            } catch (e: Exception) {
                problems += "$where: ${e.message ?: "the draft cannot be built"}"
                null
            }
        }
    }
}

private inline fun <reified T> decode(draft: JsonObject, where: String, problems: MutableList<String>): T? = try {
    DRAFT_JSON.decodeFromJsonElement<T>(draft)
} catch (e: Exception) {
    problems += "$where.draft: ${e.message ?: "unreadable"}"
    null
}

private fun refusalMessage(outcome: FxUpdateOutcome): String = when (outcome) {
    is FxUpdateOutcome.Refused -> outcome.message
    is FxUpdateOutcome.Conflict -> outcome.message
    FxUpdateOutcome.NotFound -> "the FX is no longer running"
    is FxUpdateOutcome.Updated, is FxUpdateOutcome.Split -> "unexpected outcome"
}

/** Whether a head is one [targets] cover, as a press would cover it; every head with no targets. */
private fun coveredBy(state: State, targets: List<CueTargetDto>): (String) -> Boolean {
    if (targets.isEmpty()) return { true }
    val coverage = TargetCoverage { state.show.fixtures }
    val selected = coverage.expand(targets).toSet()
    return { key -> coverage.covers(selected, CueTargetDto(TargetRef.Fixture.TYPE, key)) }
}

/** What solo draws: the solo FX's attribute rows as it alone leaves them over what is underneath (D14), by row key. */
private class Solo(val fx: FxInstance, val results: Map<String, FxSampleSeries>)

private fun soloResults(sampler: FxSampler, reads: FxSampler.Reads, solo: FxInstance, samples: FxSamples, clock: FxSampleClock): Solo {
    val alone = sampler.sampleAt(
        listOf(solo), FxSampleRequest.Include.ONLY, samples.rows, clock, samples.offsetsMs, samples.sampleMs, reads = reads,
    )
    return Solo(solo, alone.attribute(solo.target.propertyName)?.rows.orEmpty().associate { it.key to it.result })
}

private fun answer(
    state: State,
    sampler: FxSampler,
    reads: FxSampler.Reads,
    samples: FxSamples,
    asked: List<FxInstance>,
    rigIndex: Map<String, Int>,
    clock: FxSampleClock,
    solo: Solo?,
    rowsCut: Boolean,
): FxPreviewResponse {
    val masters = state.show.fxEngine.speedMasters.masterStates()
    val n = samples.offsetsMs.size
    val rowIndex = samples.rows.withIndex().associate { (i, key) -> key to i }
    val fxIndex = samples.fx.withIndex().associate { (i, sampled) -> sampled.fx to i }

    fun fixture(key: String) = runCatching { state.show.fixtures.untypedGroupableFixture(key) }.getOrNull()
    fun family(key: String, attribute: String): PropertyMaskGroup? = fixture(key)?.let { maskGroupForProperty(it, attribute) }

    /** The master an FX's clock follows, or null for its own seconds. */
    fun masterSlot(effect: FxInstance): Int? =
        if (effect.timingSource == TimingSource.BEAT) reads.speedSlotOf(effect)
        else reads.rateSlotOf(effect).takeIf { it != FxInstance.NO_RATE_MASTER }
    fun familyOf(effect: FxInstance): PropertyMaskGroup? =
        sampler.heads(listOf(effect), reads).firstNotNullOfOrNull { family(it.key, effect.target.propertyName) }
    val soloFamily = solo?.let { familyOf(it.fx) }
    fun uuidAt(slot: Int) = masters.getOrNull(slot)?.uuid?.toString()

    // Per-FX lists, filled from the rows each paints.
    class PerFx(val sampledIndex: Int) {
        val offsets = arrayOfNulls<Double>(samples.rows.size)
        val starts = arrayOfNulls<List<Int>>(samples.rows.size)
        val holding = arrayOfNulls<List<List<Int>>>(samples.rows.size)
        val rungs = arrayOfNulls<FxLadderRung>(samples.rows.size)
        var slots: Int? = null
        var family: PropertyMaskGroup? = null
    }
    val perFx = samples.fx.indices.map { PerFx(it) }

    val attributes = samples.attributes.map { sampledAttribute ->
        val attribute = sampledAttribute.attribute
        val family = sampledAttribute.rows.firstNotNullOfOrNull { family(it.key, attribute) }
        // Solo draws its family's lane as the solo FX alone: its own rows as it leaves them, the rest
        // of the lane what is underneath.
        val soloRows = when {
            solo == null || family != soloFamily -> null
            attribute == solo.fx.target.propertyName -> solo.results
            else -> emptyMap()
        }
        val rows = sampledAttribute.rows.map { row ->
            val r = rowIndex.getValue(row.key)
            val outputs = row.fx.map { output ->
                val f = fxIndex.getValue(output.fx)
                perFx[f].apply {
                    offsets[r] = output.offset
                    starts[r] = output.starts
                    holding[r] = output.holding.map { listOf(it.first, it.last) }
                    rungs[r] = output.rung
                    if (slots == null) slots = output.slots
                    if (this.family == null) this.family = family
                }
                FxPreviewOutput(f, encode(output.output), output.heldBack?.name)
            }
            val result = when {
                soloRows == null -> row.result
                else -> soloRows[row.key] ?: constant(row.underneath, row.result, n)
            }
            FxPreviewAttributeRow(r, row.parked, encode(constant(row.underneath, row.result, 1)), encode(result), outputs)
        }
        family to FxPreviewAttribute(attribute, kindOf(sampledAttribute.rows.first().result), rows)
    }
    val lanes = attributes.groupBy({ it.first }, { it.second })
        .entries.sortedBy { (family, _) -> family?.let(LANE_ORDER::indexOf) ?: LANE_ORDER.size }
        .map { (family, list) -> FxPreviewLane(family?.name, list) }

    val fx = samples.fx.mapIndexed { i, sampled ->
        val effect = sampled.fx
        val per = perFx[i]
        val entry = asked.indexOf(effect).takeIf { it >= 0 }
        fun rung(step: SpreadShaping.Step) = FxPreviewRung(
            per.rungs.map { it?.rank(step) },
            per.rungs.map { it?.count(step) },
        )
        FxPreviewFx(
            fxId = effect.id.takeIf { it != 0L },
            entry = entry,
            fxType = effect.fxTypeId,
            family = (per.family ?: familyOf(effect))?.name,
            attribute = effect.target.propertyName,
            running = sampled.running,
            speedMasterUuid = masterSlot(effect)?.let(::uuidAt),
            offsets = per.offsets.toList(),
            starts = per.starts.toList(),
            holding = per.holding.toList(),
            slots = per.slots ?: 1,
            ladder = FxPreviewLadder(
                order = rung(SpreadShaping.Step.ORDER),
                segments = rung(SpreadShaping.Step.SEGMENTS),
                wings = rung(SpreadShaping.Step.WINGS),
                parts = rung(SpreadShaping.Step.PARTS),
                spread = per.offsets.toList(),
            ),
            representative = sampled.representative,
        )
    }

    val slots = samples.fx.mapTo(LinkedHashSet()) { masterSlot(it.fx) }
    val clocks = slots.map { slot ->
        if (slot == null) FxPreviewClock(seconds = true)
        else FxPreviewClock(
            speedMasterUuid = uuidAt(slot),
            tickAtStart = clock.tickAt(slot),
            msPerBeat = clock.msPerTick(slot) * MasterClock.TICKS_PER_BEAT,
        )
    }

    val lengthMs = n * samples.sampleMs
    val windowSlot = sampler.windowSlot(asked.first(), reads)
    return FxPreviewResponse(
        window = FxPreviewWindowAnswer(
            beats = lengthMs / (clock.msPerTick(windowSlot) * MasterClock.TICKS_PER_BEAT),
            seconds = lengthMs / 1000.0,
            samples = n,
            sampleMs = samples.sampleMs,
        ),
        clock = clocks,
        rows = samples.rows.map { key -> rowOf(state, key, rigIndex[key] ?: -1) },
        lanes = lanes,
        fx = fx,
        truncated = (samples.truncated.map { it.name } + listOfNotNull(FxSamples.Truncation.ROWS.name.takeIf { rowsCut })).distinct().sorted(),
    )
}

private val LANE_ORDER = listOf(PropertyMaskGroup.INTENSITY, PropertyMaskGroup.COLOUR, PropertyMaskGroup.POSITION, PropertyMaskGroup.BEAM)

private fun rowOf(state: State, key: String, rigIndex: Int): FxPreviewRow {
    val head = runCatching { state.show.fixtures.untypedGroupableFixture(key) }.getOrNull()
    val element = head as? FixtureElement<*>
    return FxPreviewRow(
        key = key,
        fixtureKey = element?.parentFixture?.key ?: key,
        label = head?.displayName ?: key,
        element = element?.elementIndex,
        rigIndex = rigIndex,
    )
}

private fun kindOf(series: FxSampleSeries): String = when (series) {
    is FxSampleSeries.Bytes -> "SLIDER"
    is FxSampleSeries.Colour -> "COLOUR"
    is FxSampleSeries.Position -> "POSITION"
    is FxSampleSeries.Steps -> kindOf(series.values)
}

/** [value] held for [n] samples, in [like]'s planes. */
private fun constant(value: FxOutput, like: FxSampleSeries, n: Int): FxSampleSeries {
    fun plane(byte: UByte) = ByteArray(n) { byte.toByte() }
    return when (like) {
        is FxSampleSeries.Steps -> constant(value, like.values, n)
        is FxSampleSeries.Colour -> {
            val colour = (value as? FxOutput.Colour)?.color
            FxSampleSeries.Colour(
                plane(colour?.color?.red?.toUByte() ?: 0u),
                plane(colour?.color?.green?.toUByte() ?: 0u),
                plane(colour?.color?.blue?.toUByte() ?: 0u),
                like.white?.let { plane(colour?.white ?: 0u) },
                like.amber?.let { plane(colour?.amber ?: 0u) },
                like.uv?.let { plane(colour?.uv ?: 0u) },
            )
        }
        is FxSampleSeries.Position -> {
            val position = value as? FxOutput.Position
            FxSampleSeries.Position(plane(position?.pan ?: 128u), plane(position?.tilt ?: 128u))
        }
        else -> FxSampleSeries.Bytes(plane((value as? FxOutput.Slider)?.value ?: 0u))
    }
}

private fun encode(series: FxSampleSeries): FxPreviewSeries {
    val b64 = Base64.getEncoder()
    fun MutableMap<String, String>.put(name: String, bytes: ByteArray?) {
        if (bytes != null) this[name] = b64.encodeToString(bytes)
    }
    fun planes(of: FxSampleSeries): Map<String, String> = LinkedHashMap<String, String>().apply {
        when (of) {
            is FxSampleSeries.Bytes -> put("level", of.values)
            is FxSampleSeries.Colour -> {
                put("red", of.red)
                put("green", of.green)
                put("blue", of.blue)
                put("white", of.white)
                put("amber", of.amber)
                put("uv", of.uv)
            }
            is FxSampleSeries.Position -> {
                put("pan", of.pan)
                put("tilt", of.tilt)
            }
            is FxSampleSeries.Steps -> putAll(planes(of.values))
        }
    }
    return when (series) {
        is FxSampleSeries.Steps -> FxPreviewSeries(planes(series), series.indices.toList(), series.labels)
        else -> FxPreviewSeries(planes(series))
    }
}
