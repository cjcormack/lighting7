package uk.me.cormack.lighting7.routes

import io.ktor.http.*
import uk.me.cormack.lighting7.models.Timing
import io.ktor.resources.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.resources.*
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.fixture.CommandIndex
import uk.me.cormack.lighting7.fixture.CommandNotStorableException
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fixture.TriggerIndex
import uk.me.cormack.lighting7.fixture.TriggerNotStorableException
import uk.me.cormack.lighting7.fx.*
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.FxTargetsRef
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.show.referenceFixtureFor
import uk.me.cormack.lighting7.state.State

/**
 * REST API routes for FX (effects) control.
 */
internal fun Route.routeApiRestFx(state: State) {
    route("/fx") {
        // Tempo is not here. Speed masters own it: `PUT /projects/{id}/speed-masters/{mid}`
        // for a typed/stored tempo, and the `speedMasters.*` WS family for live control.
        // Active effects endpoints
        get<ActiveFx> {
            val engine = state.show.fxEngine
            call.respond(engine.fxDtos(engine.getActiveFx()))
        }

        post<AddFx> {
            val request = call.receive<AddFxRequest>()
            try {
                val instance = buildFxInstance(state, request)
                val fxId = state.show.fxEngine.addFx(instance)
                call.respond(AddFxResponse(fxId))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Unknown error"))
            }
        }

        delete<FxId> {
            val removed = state.show.fxEngine.removeFx(it.id)
            if (removed) {
                call.respond(HttpStatusCode.NoContent)
            } else {
                call.respond(HttpStatusCode.NotFound)
            }
        }

        post<FxId.Pause> {
            state.show.fxEngine.pauseFx(it.parent.id)
            call.respond(HttpStatusCode.OK)
        }

        post<FxId.Resume> {
            state.show.fxEngine.resumeFx(it.parent.id)
            call.respond(HttpStatusCode.OK)
        }

        // Update a running effect — in place, or, with `targets` naming some of its heads, by
        // splitting those heads off (apply-model plan §3.4). The parsing is [applyFxUpdate]'s,
        // shared with the `updateFx` socket frame so the two doors cannot drift.
        put<FxId> { resource ->
            val request = call.receive<UpdateFxRequest>()
            val engine = state.show.fxEngine
            when (val outcome = applyFxUpdate(state, resource.id, request)) {
                is FxUpdateOutcome.Updated -> call.respond(engine.fxDto(outcome.instance))
                is FxUpdateOutcome.Split -> call.respond(
                    FxSplitResponse(
                        splitFxId = resource.id,
                        edited = engine.fxDtos(outcome.edited),
                        rest = engine.fxDtos(outcome.rest),
                    ),
                )
                FxUpdateOutcome.NotFound ->
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("FX not found", CODE_FX_NOT_FOUND))
                is FxUpdateOutcome.Refused ->
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(outcome.message, CODE_FX_UPDATE_REFUSED))
                is FxUpdateOutcome.Conflict ->
                    call.respond(HttpStatusCode.Conflict, ErrorResponse(outcome.message, outcome.code))
            }
        }

        // Re-apply a template layer's current effect to the instance it spawned, phase kept.
        post<FxId.Reset> { resource ->
            when (val outcome = state.show.programmerLayerStack.resetFxToPalette(resource.parent.id)) {
                is ResetToPaletteOutcome.Reset -> call.respond(state.show.fxEngine.fxDto(outcome.instance))
                ResetToPaletteOutcome.NotFound ->
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("FX not found", CODE_FX_NOT_FOUND))
                is ResetToPaletteOutcome.Refused ->
                    call.respond(HttpStatusCode.Conflict, ErrorResponse(outcome.message, outcome.code))
            }
        }

        // Get active effects for a specific fixture (direct + indirect via groups)
        get<FixtureFx> { resource ->
            val fixtureKey = resource.fixtureKey
            val engine = state.show.fxEngine
            val direct = engine.fxDtos(engine.getFxForFixture(fixtureKey))
            val indirect = engine.getIndirectFxForFixture(fixtureKey).map { it.toIndirectDto() }
            call.respond(FixtureFxResponse(direct, indirect))
        }

        // Effect library - list available effect types
        get<FxLibrary> {
            call.respond(state.show.fxRegistry.getLibrary())
        }
    }
}

// Resource classes for type-safe routing

@Resource("/active")
data object ActiveFx

@Resource("/add")
data object AddFx

@Resource("/{id}")
data class FxId(val id: Long) {
    @Resource("/pause")
    data class Pause(val parent: FxId)

    @Resource("/resume")
    data class Resume(val parent: FxId)

    @Resource("/reset")
    data class Reset(val parent: FxId)
}

@Resource("/fixture/{fixtureKey}")
data class FixtureFx(val fixtureKey: String)

@Resource("/library")
data object FxLibrary

// Request/Response DTOs

@Serializable
data class AddFxRequest(
    val fxType: String,
    /** The fixture or cell to run on. Ignored when [targets] is sent. */
    val fixtureKey: String = "",
    val attribute: String,
    /**
     * One effect over a selection (effects plan D3): the fixtures, cells and groups in the order the
     * effect spreads across them, as a group's members are. One item is that fixture or group alone.
     */
    val targets: List<CueTargetDto>? = null,
    /** The FX's own default cycle when omitted — one bar but where its frontmatter says otherwise (D16). */
    val cycle: Double? = null,
    val blendMode: String = "OVERRIDE",
    val startOnBeat: Boolean = true,
    val phaseOffset: Double = 0.0,
    val parameters: Map<String, String> = emptyMap(),
    val order: String? = null,
    val elementMode: String? = null,
    val elementFilter: String? = null,
    val timing: Timing? = null,
    /** Speed master to subscribe to, as the master's uuid (null → master 1). */
    val speedMasterUuid: String? = null,
    /**
     * Wall-clock rate master, as the master's uuid (null → unscaled). Read only by
     * WALL_CLOCK effects; it coexists with [speedMasterUuid] rather than replacing it.
     */
    val rateSpeedMasterUuid: String? = null,
    /**
     * Run on *Seconds* (venue-visit plan D7): [cycle] is the cycle in seconds and the effect
     * follows no master. Refused beside either master.
     */
    val seconds: Boolean = false,
    /** Amount (effects plan D2), 0–1; refused outside it. */
    val amount: Double = 1.0,
    /** Direction (D5): `FORWARD`, `BACKWARD` or `BOUNCE`; the FX's own default when omitted. */
    val direction: String? = null,
    /** Runs (D9): stop after this many cycles, at least 1; absent runs forever. */
    val runs: Int? = null,
    /**
     * The spread's shape (effects plan D4): Spread 0–2, Wings and Segments at least 1, Parts 0 (off) or
     * more. Refused out of range, and Wings above 1 under a mirrored order (Centre out, Centre in).
     */
    val spread: Double = 1.0,
    val wings: Int = 1,
    val segments: Int = 1,
    val parts: Int = 0,
    /**
     * Create this effect in the programmer's reserved priority band
     * ([FxEngine.PROGRAMMER_FX_PRIORITY_BASE]) rather than as a plain manual effect. Band
     * effects compose *on top of* programmer values instead of being suppressed by them,
     * and are swept by `programmer.clearAll`. Set by the busking UI; scripts and cue
     * authoring leave it false.
     */
    val programmerOwned: Boolean = false,
)

@Serializable
data class AddFxResponse(val fxId: Long)

@Serializable
data class FixtureFxResponse(
    val direct: List<FxDto>,
    val indirect: List<IndirectFxDto>
)

@Serializable
data class IndirectFxDto(
    val id: Long,
    val fxType: String,
    /** The group reaching the fixture; a selection's canonical key when [targetType] is `selection`. */
    val groupName: String,
    /** `group`, or `selection` for an effect over a selection that names the fixture (effects plan D3). */
    val targetType: String = TargetRef.Group.TYPE,
    val attribute: String,
    val cycle: Double,
    val blendMode: String,
    val isRunning: Boolean,
    val phaseOffset: Double,
    val currentPhase: Double,
    val parameters: Map<String, String>,
    val order: String,
    val timing: Timing = Timing.CYCLE,
    /** True when this effect sits in the programmer's reserved priority band. */
    val programmerOwned: Boolean = false,
    /** The envelope — see [FxDto.intensityMultiplier]. */
    val intensityMultiplier: Double = 1.0,
    /** Speed master this effect subscribes to (null → master 1). */
    val speedMasterUuid: String? = null,
    /** Wall-clock rate master (null → unscaled). Only meaningful for WALL_CLOCK effects. */
    val rateSpeedMasterUuid: String? = null,
    /** D7's reading — see [FxDto.seconds]. */
    val seconds: Boolean = false,
    /** See [FxDto.amount], [FxDto.direction], [FxDto.runs] and [FxDto.spent]. */
    val amount: Double = 1.0,
    val direction: String = "FORWARD",
    val runs: Int? = null,
    val spent: Boolean = false,
    /** See [FxDto.spread] and its three siblings. */
    val spread: Double = 1.0,
    val wings: Int = 1,
    val segments: Int = 1,
    val parts: Int = 0,
)

@Serializable
data class UpdateFxRequest(
    val fxType: String? = null,
    val parameters: Map<String, String>? = null,
    val cycle: Double? = null,
    val blendMode: String? = null,
    val phaseOffset: Double? = null,
    val order: String? = null,
    val elementMode: String? = null,
    val elementFilter: String? = null,
    val timing: Timing? = null,
    /**
     * Reassign the effect's speed master (null = no change, consistent with every other
     * field). To return an effect to the default, send master 1's uuid — master 1 always
     * exists and explicit-master-1 behaves identically to the null default.
     */
    val speedMasterUuid: String? = null,
    /** Reassign the wall-clock rate master; null = no change, as above. */
    val rateSpeedMasterUuid: String? = null,
    /**
     * Move the effect between *Seconds* and a master (venue-visit plan D7); null = no change.
     *
     * `true` puts it on Seconds: a beat effect runs its division as seconds with no master, and a
     * wall-clock effect drops its rate master — the explicit *unscaled* `FU-FX-RATE-MASTER-CLEAR`
     * asked for, since a null [rateSpeedMasterUuid] can only mean "no change". Refused beside either
     * master. `false` puts it on a master: [speedMasterUuid] for a beat effect (absent → master 1),
     * [rateSpeedMasterUuid] for a wall-clock one, which must then be named. Naming a master without
     * this field also leaves Seconds. A move between Seconds and a master changes the loop the effect
     * runs on, so it is a swap: the id is kept but the phase restarts.
     */
    val seconds: Boolean? = null,
    /** Amount (effects plan D2), 0–1; null = no change. Edited in place, phase kept. */
    val amount: Double? = null,
    /** Direction (D5); null = no change. Edited in place. */
    val direction: String? = null,
    /**
     * Runs (D9), at least 1; null = no change, so [runForever] is how an N-shot goes back to
     * running forever. A new count runs from the next pass, and wakes a spent effect.
     */
    val runs: Int? = null,
    /** True clears [runs]: the effect runs forever. Refused beside an [runs]. */
    val runForever: Boolean = false,
    /**
     * The spread's shape (effects plan D4); each null = no change. Groups 0 turns it off. Edited in
     * place like the Order, phase kept. Checked against the order the effect will run under — this
     * request's [order] or the one it has: Wings above 1 under a mirrored one is refused.
     */
    val spread: Double? = null,
    val wings: Int? = null,
    val segments: Int? = null,
    val parts: Int? = null,
    /**
     * The heads this edit is for (apply-model plan §3.4, D5): fixtures, cells and groups, as a press
     * names them. Absent, or naming every head the effect paints, is the in-place edit. Naming some
     * of them splits those heads off: the effect becomes one instance per head it paints, and the
     * edit lands on the named heads' alone, each starting where it was — answered as
     * [FxSplitResponse]. Only a Local effect splits (409 `FX_SPLIT_LAYER_FX` for a layer's,
     * `FX_SPLIT_NOT_LOCAL` for a cue's or a manual one); a change to the spread's shape (Order,
     * Spread, Wings, Segments, Parts, element mode or filter) cannot go to some heads alone and is
     * refused, as are targets naming none of its heads.
     */
    val targets: List<CueTargetDto>? = null,
)

/**
 * `PUT /fx/{id}`'s answer when its `targets` split the effect (apply-model plan §3.4): the effect
 * that is gone, the copies the edit landed on and the copies of the heads it left as they were.
 */
@Serializable
data class FxSplitResponse(
    val splitFxId: Long,
    val edited: List<FxDto>,
    val rest: List<FxDto>,
)


// Helper functions

/** The effect an update or reset names is not running (404 / `fxError`). */
internal const val CODE_FX_NOT_FOUND = "FX_NOT_FOUND"

/** An update named a field value the strict policy refuses, an unknown effect type, a bad master (400 / `fxError`). */
internal const val CODE_FX_UPDATE_REFUSED = "FX_UPDATE_REFUSED"

/** A strict-subset edit named a programmer layer's effect, which splits only with its layer (409, apply-model plan P5). */
internal const val CODE_FX_SPLIT_LAYER_FX = "FX_SPLIT_LAYER_FX"

/** A strict-subset edit named a cue's effect, or a manual one outside the programmer band: only a Local effect splits (409). */
internal const val CODE_FX_SPLIT_NOT_LOCAL = "FX_SPLIT_NOT_LOCAL"

/** `programmer.moveFx` named an FX that is not Local, as the moved FX or as `above` (FX editor plan §3.5). */
internal const val CODE_FX_MOVE_NOT_LOCAL = "FX_MOVE_NOT_LOCAL"

/** What [applyFxUpdate] did. */
internal sealed interface FxUpdateOutcome {
    data class Updated(val instance: FxInstance) : FxUpdateOutcome

    /** `targets` named some of the effect's heads: it is gone, [edited] took the edit and [rest] did not. */
    data class Split(val edited: List<FxInstance>, val rest: List<FxInstance>) : FxUpdateOutcome
    data object NotFound : FxUpdateOutcome
    data class Refused(val message: String) : FxUpdateOutcome

    /** 409: [code] is [CODE_FX_SPLIT_LAYER_FX] or [CODE_FX_SPLIT_NOT_LOCAL]. */
    data class Conflict(val code: String, val message: String) : FxUpdateOutcome
}

/**
 * Apply [request] to running effect [fxId] through [FxEngine.updateFx] — the id and the
 * phase kept — or, when its `targets` name some of the effect's heads, split those heads off and
 * edit theirs alone (apply-model plan §3.4, [planSplit]). **The one parse of an effect update**: `PUT /fx/{id}` and the `updateFx` socket frame
 * (fixture-fx-sheets plan W3) both call it, so the strict coercion
 * ([FxSpecCoercion.Strict]), the output-type check and the master-uuid check cannot be one
 * thing on one door and another on the other.
 *
 * Every refusal is decided before anything moves, so a refused update changes nothing.
 */
internal fun applyFxUpdate(state: State, fxId: Long, request: UpdateFxRequest): FxUpdateOutcome {
    val engine = state.show.fxEngine
    val existing = engine.getFx(fxId) ?: return FxUpdateOutcome.NotFound
    val (edit, splitHeads) = when (val parsed = parseFxUpdate(state, existing, request)) {
        is FxUpdateParse.Refusal -> return parsed.outcome
        is FxUpdateParse.Parsed -> parsed
    }
    return try {
        if (splitHeads == null) {
            engine.updateFx(fxId, edit)?.let { FxUpdateOutcome.Updated(it) } ?: FxUpdateOutcome.NotFound
        } else {
            // Every head becomes its own copy, still running exactly as it was, and then the named
            // heads' copies take the edit, each through the same in-place edit a whole effect gets.
            val copies = splitIntoHeads(state, fxId) ?: return FxUpdateOutcome.NotFound
            val (named, rest) = copies.partition { it.target.targetKey in splitHeads }
            FxUpdateOutcome.Split(edited = named.mapNotNull { engine.updateFx(it.id, edit) }, rest = rest)
        }
    } catch (e: Exception) {
        FxUpdateOutcome.Refused(e.message ?: "Failed to update FX")
    }
}

/** What [parseFxUpdate] made of a request. */
internal sealed interface FxUpdateParse {
    /** The edit, and the heads it is split off to — null for every head the effect paints. */
    data class Parsed(val edit: FxEngine.FxEdit, val splitHeads: Set<String>?) : FxUpdateParse

    data class Refusal(val outcome: FxUpdateOutcome) : FxUpdateParse
}

/**
 * [request] checked against running effect [existing] and turned into the edit [applyFxUpdate]
 * makes: the one parse of an effect update, which the FX preview (`POST /fx/preview`) also runs on
 * an edit not yet sent. Nothing moves; every refusal is decided here.
 */
internal fun parseFxUpdate(state: State, existing: FxInstance, request: UpdateFxRequest): FxUpdateParse {
    fun refused(message: String) = FxUpdateParse.Refusal(FxUpdateOutcome.Refused(message))
    // Which heads the edit is for (apply-model plan §3.4): every one, or some of them split off.
    val splitHeads = when (val plan = planSplit(state, existing, request.targets)) {
        SplitPlan.InPlace -> null
        is SplitPlan.Heads -> plan.selected
        is SplitPlan.Refusal -> return FxUpdateParse.Refusal(plan.outcome)
    }
    return try {
        // Resolve new effect if type or parameters changed
        val newFx = if (request.fxType != null || request.parameters != null) {
            // `existing.fxTypeId`, not the display name: a parameters-only edit of a
            // user-defined effect otherwise looks up a type the registry has never heard
            // of and the whole update 400s.
            val fxType = request.fxType ?: existing.fxTypeId
            val params = request.parameters ?: existing.effect.parameters
            state.show.fxRegistry.fxWriteProblem(fxType, params)
                ?.let { throw IllegalArgumentException(it) }
            state.show.fxRegistry.createFxWithPalettes(
                state.show.paletteRegistry, fxType, params,
            )
        } else null

        // An effect-type swap keeps the instance's existing target, so it can land an
        // effect whose output that target discards. Reject rather than silently going
        // dark — same rule the add path applies.
        newFx?.let { requireOutputTypeMatch(it, existing.target) }

        val newTiming = request.cycle?.let { FxTiming(it, existing.timing.startOnBeat) }
        val newBlendMode = request.blendMode?.let { FxSpecCoercion.Strict.blendMode(it) }
        val newOrder = request.order?.let { FxSpecCoercion.Strict.order(it) }
        val newElementMode = request.elementMode?.let { FxSpecCoercion.Strict.elementMode(it) }
        val newElementFilter = request.elementFilter?.let { FxSpecCoercion.Strict.elementFilter(it) }
        val newSpeedMaster = requireSpeedMasterUuid(request.speedMasterUuid)
        val newRateSpeedMaster = requireSpeedMasterUuid(request.rateSpeedMasterUuid)
        val newAmount = request.amount?.let { FxSpecCoercion.Strict.amount(it) }
        val newDirection = request.direction?.let { FxSpecCoercion.Strict.direction(it) }
        val newRuns = FxSpecCoercion.Strict.runs(request.runs)
        if (request.runForever && newRuns != null) {
            return refused("runs and runForever are two answers to one question — send one")
        }
        // The spread's shape (D4), checked as the effect will run it: this request's Order or the one
        // it has, and the wings it is sent or has — so moving a winged effect onto Centre out is
        // refused as surely as sending wings to one.
        // The instance's own wings are checked only when the Order actually moves: a stored row may
        // hold wings under a mirrored Order (lenient on spawn, ignored by [SpreadShaping]), and an edit
        // of its Spread, Segments or Parts — or a frame re-sending the Order it already has — must not
        // be refused for them.
        if (request.spread != null || request.wings != null || request.segments != null || request.parts != null ||
            newOrder != null
        ) {
            val dyn = existing.dynamics
            val order = newOrder ?: dyn.order
            val orderMoved = newOrder != null && newOrder != dyn.order
            val wings = request.wings ?: dyn.wings.takeIf { orderMoved }
            FxSpecCoercion.Strict.spreadProblem(request.spread, wings, request.segments, request.parts, order)
                ?.let { return refused(it) }
        }
        // Only when the client actually renamed the type; a parameters-only edit leaves the
        // instance's registration id — and the clock it runs on — alone. A type swap takes the new
        // type's timing source with it, as the add path does, or a beat effect swapped for a
        // wall-clock one would run on the beat loop.
        val registration = request.fxType?.let { state.show.fxRegistry.getRegistration(it) }
        val newTimingSource = request.fxType?.let { registration?.timingSource ?: TimingSource.BEAT }

        // D7. Seconds and a master are two answers to one question, so both is refused; a master
        // named on an effect that is on Seconds takes it off Seconds.
        secondsTimingConflict(request.seconds, request.speedMasterUuid, request.rateSpeedMasterUuid)
            ?.let { return refused(it) }
        val kind = newTimingSource ?: existing.registrationTimingSource
        val namesItsMaster = if (kind == TimingSource.BEAT) newSpeedMaster != null else newRateSpeedMaster != null
        val newSeconds = request.seconds ?: if (namesItsMaster && existing.onSeconds) false else null
        // A wall-clock effect's master *is* its rate master, so leaving Seconds has to say which.
        if (newSeconds == false && kind == TimingSource.WALL_CLOCK && newRateSpeedMaster == null &&
            existing.rateSpeedMasterUuid == null
        ) {
            return refused("seconds: false on a wall-clock FX names its master — send rateSpeedMasterUuid")
        }

        // A split cannot spread the shape over some heads: each split-off head runs alone. Refused
        // only for a field that would change, so an editor re-sending the shape it shows still splits.
        if (splitHeads != null) {
            val dyn = existing.dynamics
            val shapeMoved = buildList {
                if (newOrder != null && newOrder != dyn.order) add("order")
                if (newElementMode != null && newElementMode != dyn.elementMode) add("elementMode")
                if (newElementFilter != null && newElementFilter != dyn.elementFilter) add("elementFilter")
                if (request.spread != null && request.spread != dyn.spread) add("spread")
                if (request.wings != null && request.wings != dyn.wings) add("wings")
                if (request.segments != null && request.segments != dyn.segments) add("segments")
                if (request.parts != null && request.parts != dyn.parts) add("parts")
            }
            if (shapeMoved.isNotEmpty()) {
                return refused(
                    "${shapeMoved.joinToString(", ")} spread the FX over all its heads, and the heads " +
                        "split off here would each run alone — select every head the FX runs on to change it",
                )
            }
        }

        FxUpdateParse.Parsed(
            FxEngine.FxEdit(
                newFx = newFx,
                newTiming = newTiming,
                newBlendMode = newBlendMode,
                newPhaseOffset = request.phaseOffset,
                newOrder = newOrder,
                newElementMode = newElementMode,
                newElementFilter = newElementFilter,
                newTimingMode = request.timing,
                newSpeedMasterUuid = newSpeedMaster,
                newRateSpeedMasterUuid = newRateSpeedMaster,
                newRegistrationId = registration?.id,
                newTimingSource = newTimingSource,
                newSeconds = newSeconds,
                newAmount = newAmount,
                newDirection = newDirection,
                newRuns = newRuns,
                clearRuns = request.runForever,
                newSpread = request.spread,
                newWings = request.wings,
                newSegments = request.segments,
                newParts = request.parts,
            ),
            splitHeads,
        )
    } catch (e: Exception) {
        refused(e.message ?: "Failed to update FX")
    }
}

/**
 * A new effect's Size, Direction, Runs and spread shape (effects plan D2, D5, D9, D4) from a request,
 * the FX's own direction where it names none, strictly: a size outside 0–1, an unknown direction, a count below one or a shape out of range (or
 * wings under a mirrored order — read from the instance, so its Order is set first) throws for the
 * add route's 400. Shared by the fixture and group add routes, as [markProgrammerOwned] is.
 */
internal fun FxInstance.applyRequestedDynamics(size: Double, direction: String?, runs: Int?, shape: SpreadShape) {
    val checkedSize = FxSpecCoercion.Strict.amount(size)
    val checkedDirection = direction?.let { FxSpecCoercion.Strict.direction(it) } ?: effect.defaultDirection
    val checkedRuns = FxSpecCoercion.Strict.runs(runs)
    val checkedShape = FxSpecCoercion.Strict.spreadShape(shape, order)
    updateDynamics {
        it.copy(
            amount = checkedSize, direction = checkedDirection, runs = checkedRuns,
            spread = checkedShape.spread, wings = checkedShape.wings,
            segments = checkedShape.segments, parts = checkedShape.parts,
        )
    }
}

/**
 * Stamp an effect into the programmer's reserved priority band when the request asked for
 * it. Shared by the fixture (`/fx/add`) and group (`/groups/{name}/fx`) add routes so the
 * two can't drift. Must run *before* [FxEngine.addFx], which reads the priority to
 * decide whether to auto-tag the effect with the running cue context.
 */
internal fun markProgrammerOwned(instance: FxInstance, programmerOwned: Boolean) {
    if (programmerOwned) instance.priority = FxEngine.PROGRAMMER_FX_PRIORITY_BASE
}

/**
 * Parse a request's speed-master reference, treating a PRESENT-but-malformed uuid as a
 * client error rather than silently degrading. The lenient [speedMasterUuidOrNull] is for
 * *stored* data, where degrading a corrupted reference to master 1 beats failing the show;
 * on the REST boundary the same leniency would make a garbled uuid indistinguishable from
 * an omitted field — a 200 no-op masking a client bug. Callers' catch blocks turn the
 * throw into a 400.
 */
internal fun requireSpeedMasterUuid(raw: String?): java.util.UUID? = raw?.let {
    speedMasterUuidOrNull(it)
        ?: throw IllegalArgumentException("Invalid speedMasterUuid: '$it' is not a uuid")
}

private fun FxInstance.toIndirectDto() = IndirectFxDto(
    id = id,
    fxType = fxTypeId,
    groupName = target.targetKey,
    targetType = target.targetRef.discriminator,
    attribute = target.attribute,
    cycle = timing.cycle,
    blendMode = blendMode.name,
    isRunning = isRunning,
    phaseOffset = phaseOffset,
    currentPhase = lastPhase,
    parameters = effect.parameters,
    order = order.javaClass.simpleName,
    timing = dynamics.timing,
    programmerOwned = FxEngine.isProgrammerFxPriority(priority),
    intensityMultiplier = intensityMultiplier,
    speedMasterUuid = reportedSpeedMasterUuid,
    rateSpeedMasterUuid = reportedRateSpeedMasterUuid,
    seconds = onSeconds,
    amount = dynamics.amount,
    direction = dynamics.direction.name,
    runs = dynamics.runs,
    spread = dynamics.spread,
    wings = dynamics.wings,
    segments = dynamics.segments,
    parts = dynamics.parts,
    spent = spent,
)

/**
 * The instance [request] describes, checked and ready for [FxEngine.addFx] — `POST /fx/add`'s
 * body, shared with the programmer's bare effect press (`POST /programmer/fx`, apply-model plan
 * §3.4), so the two build the same effect from the same fields. Throws on anything the strict policy
 * refuses; the callers answer 400.
 */
internal fun buildFxInstance(state: State, request: AddFxRequest): FxInstance {
    val effect = createFxFromRequest(request, state)
    // The effect first: its output type is what tells FxTargetFactory that a
    // `pan`/`tilt` property name means the position pair (sweep item A11).
    val target = createTargetFromRequest(request, state, effect.outputType)
    requireOutputTypeMatch(effect, target)
    val registration = state.show.fxRegistry.getRegistration(request.fxType)
    val timing = FxTiming(
        cycle = request.cycle ?: registration?.defaultCycle ?: CycleLength.ONE_BAR,
        startOnBeat = request.startOnBeat
    )
    val blendMode = FxSpecCoercion.Strict.blendMode(request.blendMode)

    val instance = FxInstance(effect, target, timing, blendMode)
    instance.phaseOffset = request.phaseOffset
    request.order?.let {
        instance.order = FxSpecCoercion.Strict.order(it)
    }
    request.elementMode?.let {
        instance.elementMode = FxSpecCoercion.Strict.elementMode(it)
    }
    request.elementFilter?.let {
        instance.elementFilter = FxSpecCoercion.Strict.elementFilter(it)
    }
    request.timing?.let { t ->
        instance.updateDynamics { it.copy(timing = t) }
    }
    instance.applyRequestedDynamics(
        request.amount, request.direction, request.runs,
        SpreadShape(request.spread, request.wings, request.segments, request.parts),
    )

    // Propagate timing source and identity from the effect's registration
    registration?.timingSource?.let { instance.timingSource = it }
    instance.registrationId = registration?.id

    secondsTimingConflict(request.seconds, request.speedMasterUuid, request.rateSpeedMasterUuid)
        ?.let { throw IllegalArgumentException(it) }
    instance.speedMasterUuid = requireSpeedMasterUuid(request.speedMasterUuid)
    instance.rateSpeedMasterUuid = requireSpeedMasterUuid(request.rateSpeedMasterUuid)
    instance.applyTimingChoice(registration?.timingSource ?: TimingSource.BEAT, request.seconds)

    markProgrammerOwned(instance, request.programmerOwned)
    if (request.targets != null) {
        require(state.show.fxEngine.fixtureKeysCoveredBy(instance).isNotEmpty()) {
            "No fixture in targets has '${request.attribute}'"
        }
    }
    return instance
}

private fun createTargetFromRequest(
    request: AddFxRequest,
    state: State,
    outputType: FxOutputType?,
): FxTarget {
    request.targets?.let { return createTargetFromList(it, request.attribute, state, outputType) }
    require(request.fixtureKey.isNotBlank()) { "Name the FX's fixtureKey, or its targets" }
    // `untypedGroupableFixture`, as `FxSpawner.createFixtureTargetForCue` does: the two doors
    // must build the same target for the same cell, and the factory's fallback reads its catalogue.
    val fixture = try {
        state.show.fixtures.untypedGroupableFixture(request.fixtureKey)
    } catch (_: Exception) { null }
    return FxTargetFactory.forFixture(request.fixtureKey, request.attribute, outputType, fixture)
}

/**
 * The target for a `targets` list: every item must name a fixture, cell or group the rig has (all
 * problems at once). Whether any of them has the property is the route's check, on the instance.
 */
private fun createTargetFromList(
    targets: List<CueTargetDto>,
    attribute: String,
    state: State,
    outputType: FxOutputType?,
): FxTarget {
    require(targets.isNotEmpty()) { "targets names no fixture or group" }
    val fixtures = state.show.fixtures
    val problems = ArrayList<String>()
    val refs = targets.mapIndexedNotNull { i, t ->
        val ref = TargetRef.ofOrNull(t.type, t.key)
        when {
            ref == null -> problems += "targets[$i]: '${t.type}' is not a fixture or a group"
            ref is TargetRef.Fixture && runCatching { fixtures.untypedGroupableFixture(ref.key) }.isFailure ->
                problems += "targets[$i]: no fixture '${ref.key}'"
            ref is TargetRef.Group && runCatching { fixtures.untypedGroup(ref.key) }.isFailure ->
                problems += "targets[$i]: no group '${ref.key}'"
        }
        ref
    }
    require(problems.isEmpty()) { problems.joinToString("; ") }
    val target = FxTargetsRef.of(refs)
    // No effect may drive a one-shot trigger or a fixture command on anything the list names.
    TriggerIndex.refusalLive(fixtures, target.discriminator, target.key, attribute, "FX")
        ?.let { throw TriggerNotStorableException(it) }
    CommandIndex.refusalLive(fixtures, target.discriminator, target.key, attribute, "FX")
        ?.let { throw CommandNotStorableException(it) }
    val fxTarget = when (target) {
        is FxTargetsRef.Selection -> FxSpawner.createSelectionTarget(state, target, attribute, outputType)
        is FxTargetsRef.Single -> when (val single = target.target) {
            is TargetRef.Fixture -> FxTargetFactory.forFixture(
                single.key, attribute, outputType, runCatching { fixtures.untypedGroupableFixture(single.key) }.getOrNull(),
            )
            is TargetRef.Group -> FxTargetFactory.forGroup(
                single.key, attribute, outputType, fixtures.referenceFixtureFor(target, attribute) as? Fixture,
            )
        }
    }
    return fxTarget
}

private fun createFxFromRequest(request: AddFxRequest, state: State): Fx {
    state.show.fxRegistry.fxWriteProblem(request.fxType, request.parameters)
        ?.let { throw IllegalArgumentException(it) }
    return state.show.fxRegistry.createFxWithPalettes(
        state.show.paletteRegistry,
        request.fxType,
        request.parameters,
    )
}

// Effect creation, parse helpers, and the effect library have moved to the fx package:
// - fx/FxParamUtils.kt (parseExtendedColour, parseColor, toUByteParam, toEasingCurveParam)
// - fx/FxRegistry.kt (FxRegistry.createFx replaces createFxFromTypeAndParams)
// - fx/FxFileLoader.kt (loads built-in effects from .fx.kts resource files)
