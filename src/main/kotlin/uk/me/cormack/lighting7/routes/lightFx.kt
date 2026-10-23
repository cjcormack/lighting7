package uk.me.cormack.lighting7.routes

import io.ktor.http.*
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
import uk.me.cormack.lighting7.models.EffectTargetRef
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
        get<ActiveEffects> {
            val engine = state.show.fxEngine
            call.respond(engine.effectDtos(engine.getActiveEffects()))
        }

        post<AddEffect> {
            val request = call.receive<AddEffectRequest>()
            try {
                val instance = buildEffectInstance(state, request)
                val effectId = state.show.fxEngine.addEffect(instance)
                call.respond(AddEffectResponse(effectId))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Unknown error"))
            }
        }

        delete<EffectId> {
            val removed = state.show.fxEngine.removeEffect(it.id)
            if (removed) {
                call.respond(HttpStatusCode.NoContent)
            } else {
                call.respond(HttpStatusCode.NotFound)
            }
        }

        post<EffectId.Pause> {
            state.show.fxEngine.pauseEffect(it.parent.id)
            call.respond(HttpStatusCode.OK)
        }

        post<EffectId.Resume> {
            state.show.fxEngine.resumeEffect(it.parent.id)
            call.respond(HttpStatusCode.OK)
        }

        // Update a running effect — in place, or, with `targets` naming some of its heads, by
        // splitting those heads off (apply-model plan §3.4). The parsing is [applyEffectUpdate]'s,
        // shared with the `updateFx` socket frame so the two doors cannot drift.
        put<EffectId> { resource ->
            val request = call.receive<UpdateEffectRequest>()
            val engine = state.show.fxEngine
            when (val outcome = applyEffectUpdate(state, resource.id, request)) {
                is EffectUpdateOutcome.Updated -> call.respond(engine.effectDto(outcome.instance))
                is EffectUpdateOutcome.Split -> call.respond(
                    EffectSplitResponse(
                        splitEffectId = resource.id,
                        edited = engine.effectDtos(outcome.edited),
                        rest = engine.effectDtos(outcome.rest),
                    ),
                )
                EffectUpdateOutcome.NotFound ->
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Effect not found", CODE_FX_NOT_FOUND))
                is EffectUpdateOutcome.Refused ->
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(outcome.message, CODE_FX_UPDATE_REFUSED))
                is EffectUpdateOutcome.Conflict ->
                    call.respond(HttpStatusCode.Conflict, ErrorResponse(outcome.message, outcome.code))
            }
        }

        // Re-apply a template layer's current effect to the instance it spawned, phase kept.
        post<EffectId.Reset> { resource ->
            when (val outcome = state.show.programmerLayerStack.resetEffectToTemplate(resource.parent.id)) {
                is ResetToTemplateOutcome.Reset -> call.respond(state.show.fxEngine.effectDto(outcome.instance))
                ResetToTemplateOutcome.NotFound ->
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Effect not found", CODE_FX_NOT_FOUND))
                is ResetToTemplateOutcome.Refused ->
                    call.respond(HttpStatusCode.Conflict, ErrorResponse(outcome.message, outcome.code))
            }
        }

        // Get active effects for a specific fixture (direct + indirect via groups)
        get<FixtureEffects> { resource ->
            val fixtureKey = resource.fixtureKey
            val engine = state.show.fxEngine
            val direct = engine.effectDtos(engine.getEffectsForFixture(fixtureKey))
            val indirect = engine.getIndirectEffectsForFixture(fixtureKey).map { it.toIndirectDto() }
            call.respond(FixtureEffectsResponse(direct, indirect))
        }

        // Effect library - list available effect types
        get<EffectLibrary> {
            call.respond(state.show.fxRegistry.getLibrary())
        }
    }
}

// Resource classes for type-safe routing

@Resource("/active")
data object ActiveEffects

@Resource("/add")
data object AddEffect

@Resource("/{id}")
data class EffectId(val id: Long) {
    @Resource("/pause")
    data class Pause(val parent: EffectId)

    @Resource("/resume")
    data class Resume(val parent: EffectId)

    @Resource("/reset")
    data class Reset(val parent: EffectId)
}

@Resource("/fixture/{fixtureKey}")
data class FixtureEffects(val fixtureKey: String)

@Resource("/library")
data object EffectLibrary

// Request/Response DTOs

@Serializable
data class AddEffectRequest(
    val effectType: String,
    /** The fixture or cell to run on. Ignored when [targets] is sent. */
    val fixtureKey: String = "",
    val propertyName: String,
    /**
     * One effect over a selection (effects plan D3): the fixtures, cells and groups in the order the
     * effect spreads across them, as a group's members are. One item is that fixture or group alone.
     */
    val targets: List<CueTargetDto>? = null,
    /** The FX's own default cycle when omitted — one bar but where its frontmatter says otherwise (D16). */
    val beatDivision: Double? = null,
    val blendMode: String = "OVERRIDE",
    val startOnBeat: Boolean = true,
    val phaseOffset: Double = 0.0,
    val parameters: Map<String, String> = emptyMap(),
    val distributionStrategy: String? = null,
    val elementMode: String? = null,
    val elementFilter: String? = null,
    val stepTiming: Boolean? = null,
    /** Speed master to subscribe to, as the master's uuid (null → master 1). */
    val speedMasterUuid: String? = null,
    /**
     * Wall-clock rate master, as the master's uuid (null → unscaled). Read only by
     * WALL_CLOCK effects; it coexists with [speedMasterUuid] rather than replacing it.
     */
    val rateSpeedMasterUuid: String? = null,
    /**
     * Run on *Seconds* (venue-visit plan D7): [beatDivision] is the cycle in seconds and the effect
     * follows no master. Refused beside either master.
     */
    val seconds: Boolean = false,
    /** Size (effects plan D2), 0–1; refused outside it. */
    val size: Double = 1.0,
    /** Direction (D5): `FORWARD`, `BACKWARD` or `BOUNCE`; the FX's own default when omitted. */
    val direction: String? = null,
    /** Runs (D9): stop after this many cycles, at least 1; absent runs forever. */
    val nShot: Int? = null,
    /**
     * The spread's shape (effects plan D4): Spread 0–2, Wings and Blocks at least 1, Groups 0 (off) or
     * more. Refused out of range, and Wings above 1 under a mirrored order (Centre out, Centre in).
     */
    val spread: Double = 1.0,
    val wings: Int = 1,
    val blocks: Int = 1,
    val groups: Int = 0,
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
data class AddEffectResponse(val effectId: Long)

@Serializable
data class FixtureEffectsResponse(
    val direct: List<EffectDto>,
    val indirect: List<IndirectEffectDto>
)

@Serializable
data class IndirectEffectDto(
    val id: Long,
    val effectType: String,
    /** The group reaching the fixture; a selection's canonical key when [targetType] is `selection`. */
    val groupName: String,
    /** `group`, or `selection` for an effect over a selection that names the fixture (effects plan D3). */
    val targetType: String = TargetRef.Group.TYPE,
    val propertyName: String,
    val beatDivision: Double,
    val blendMode: String,
    val isRunning: Boolean,
    val phaseOffset: Double,
    val currentPhase: Double,
    val parameters: Map<String, String>,
    val distributionStrategy: String,
    val stepTiming: Boolean = false,
    /** True when this effect sits in the programmer's reserved priority band. */
    val programmerOwned: Boolean = false,
    /** The envelope — see [EffectDto.intensityMultiplier]. */
    val intensityMultiplier: Double = 1.0,
    /** Speed master this effect subscribes to (null → master 1). */
    val speedMasterUuid: String? = null,
    /** Wall-clock rate master (null → unscaled). Only meaningful for WALL_CLOCK effects. */
    val rateSpeedMasterUuid: String? = null,
    /** D7's reading — see [EffectDto.seconds]. */
    val seconds: Boolean = false,
    /** See [EffectDto.size], [EffectDto.direction], [EffectDto.nShot] and [EffectDto.spent]. */
    val size: Double = 1.0,
    val direction: String = "FORWARD",
    val nShot: Int? = null,
    val spent: Boolean = false,
    /** See [EffectDto.spread] and its three siblings. */
    val spread: Double = 1.0,
    val wings: Int = 1,
    val blocks: Int = 1,
    val groups: Int = 0,
)

@Serializable
data class UpdateEffectRequest(
    val effectType: String? = null,
    val parameters: Map<String, String>? = null,
    val beatDivision: Double? = null,
    val blendMode: String? = null,
    val phaseOffset: Double? = null,
    val distributionStrategy: String? = null,
    val elementMode: String? = null,
    val elementFilter: String? = null,
    val stepTiming: Boolean? = null,
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
    /** Size (effects plan D2), 0–1; null = no change. Edited in place, phase kept. */
    val size: Double? = null,
    /** Direction (D5); null = no change. Edited in place. */
    val direction: String? = null,
    /**
     * Runs (D9), at least 1; null = no change, so [runForever] is how an N-shot goes back to
     * running forever. A new count runs from the next pass, and wakes a spent effect.
     */
    val nShot: Int? = null,
    /** True clears [nShot]: the effect runs forever. Refused beside an [nShot]. */
    val runForever: Boolean = false,
    /**
     * The spread's shape (effects plan D4); each null = no change. Groups 0 turns it off. Edited in
     * place like the Order, phase kept. Checked against the order the effect will run under — this
     * request's [distributionStrategy] or the one it has: Wings above 1 under a mirrored one is refused.
     */
    val spread: Double? = null,
    val wings: Int? = null,
    val blocks: Int? = null,
    val groups: Int? = null,
    /**
     * The heads this edit is for (apply-model plan §3.4, D5): fixtures, cells and groups, as a press
     * names them. Absent, or naming every head the effect paints, is the in-place edit. Naming some
     * of them splits those heads off: the effect becomes one instance per head it paints, and the
     * edit lands on the named heads' alone, each starting where it was — answered as
     * [EffectSplitResponse]. Only a Local effect splits (409 `FX_SPLIT_LAYER_EFFECT` for a layer's,
     * `FX_SPLIT_NOT_LOCAL` for a cue's or a manual one); a change to the spread's shape (Order,
     * Spread, Wings, Blocks, Groups, element mode or filter) cannot go to some heads alone and is
     * refused, as are targets naming none of its heads.
     */
    val targets: List<CueTargetDto>? = null,
)

/**
 * `PUT /fx/{id}`'s answer when its `targets` split the effect (apply-model plan §3.4): the effect
 * that is gone, the copies the edit landed on and the copies of the heads it left as they were.
 */
@Serializable
data class EffectSplitResponse(
    val splitEffectId: Long,
    val edited: List<EffectDto>,
    val rest: List<EffectDto>,
)


// Helper functions

/** The effect an update or reset names is not running (404 / `fxError`). */
internal const val CODE_FX_NOT_FOUND = "FX_NOT_FOUND"

/** An update named a field value the strict policy refuses, an unknown effect type, a bad master (400 / `fxError`). */
internal const val CODE_FX_UPDATE_REFUSED = "FX_UPDATE_REFUSED"

/** A strict-subset edit named a programmer layer's effect, which splits only with its layer (409, apply-model plan P5). */
internal const val CODE_FX_SPLIT_LAYER_EFFECT = "FX_SPLIT_LAYER_EFFECT"

/** A strict-subset edit named a cue's effect, or a manual one outside the programmer band: only a Local effect splits (409). */
internal const val CODE_FX_SPLIT_NOT_LOCAL = "FX_SPLIT_NOT_LOCAL"

/** What [applyEffectUpdate] did. */
internal sealed interface EffectUpdateOutcome {
    data class Updated(val instance: FxInstance) : EffectUpdateOutcome

    /** `targets` named some of the effect's heads: it is gone, [edited] took the edit and [rest] did not. */
    data class Split(val edited: List<FxInstance>, val rest: List<FxInstance>) : EffectUpdateOutcome
    data object NotFound : EffectUpdateOutcome
    data class Refused(val message: String) : EffectUpdateOutcome

    /** 409: [code] is [CODE_FX_SPLIT_LAYER_EFFECT] or [CODE_FX_SPLIT_NOT_LOCAL]. */
    data class Conflict(val code: String, val message: String) : EffectUpdateOutcome
}

/**
 * Apply [request] to running effect [effectId] through [FxEngine.updateEffect] — the id and the
 * phase kept — or, when its `targets` name some of the effect's heads, split those heads off and
 * edit theirs alone (apply-model plan §3.4, [planSplit]). **The one parse of an effect update**: `PUT /fx/{id}` and the `updateFx` socket frame
 * (fixture-fx-sheets plan W3) both call it, so the strict coercion
 * ([EffectSpecCoercion.Strict]), the output-type check and the master-uuid check cannot be one
 * thing on one door and another on the other.
 *
 * Every refusal is decided before anything moves, so a refused update changes nothing.
 */
internal fun applyEffectUpdate(state: State, effectId: Long, request: UpdateEffectRequest): EffectUpdateOutcome {
    val engine = state.show.fxEngine
    val existing = engine.getEffect(effectId) ?: return EffectUpdateOutcome.NotFound
    // Which heads the edit is for (apply-model plan §3.4): every one, or some of them split off.
    val splitHeads = when (val plan = planSplit(state, existing, request.targets)) {
        SplitPlan.InPlace -> null
        is SplitPlan.Heads -> plan.selected
        is SplitPlan.Refusal -> return plan.outcome
    }
    return try {
        // Resolve new effect if type or parameters changed
        val newEffect = if (request.effectType != null || request.parameters != null) {
            // `existing.effectTypeId`, not the display name: a parameters-only edit of a
            // user-defined effect otherwise looks up a type the registry has never heard
            // of and the whole update 400s.
            val effectType = request.effectType ?: existing.effectTypeId
            val params = request.parameters ?: existing.effect.parameters
            state.show.fxRegistry.fxWriteProblem(effectType, params)
                ?.let { throw IllegalArgumentException(it) }
            state.show.fxRegistry.createEffectWithTemplates(
                state.show.templateRegistry, effectType, params,
            )
        } else null

        // An effect-type swap keeps the instance's existing target, so it can land an
        // effect whose output that target discards. Reject rather than silently going
        // dark — same rule the add path applies.
        newEffect?.let { requireOutputTypeMatch(it, existing.target) }

        val newTiming = request.beatDivision?.let { FxTiming(it, existing.timing.startOnBeat) }
        val newBlendMode = request.blendMode?.let { EffectSpecCoercion.Strict.blendMode(it) }
        val newDistribution = request.distributionStrategy?.let { EffectSpecCoercion.Strict.distribution(it) }
        val newElementMode = request.elementMode?.let { EffectSpecCoercion.Strict.elementMode(it) }
        val newElementFilter = request.elementFilter?.let { EffectSpecCoercion.Strict.elementFilter(it) }
        val newSpeedMaster = requireSpeedMasterUuid(request.speedMasterUuid)
        val newRateSpeedMaster = requireSpeedMasterUuid(request.rateSpeedMasterUuid)
        val newSize = request.size?.let { EffectSpecCoercion.Strict.size(it) }
        val newDirection = request.direction?.let { EffectSpecCoercion.Strict.direction(it) }
        val newNShot = EffectSpecCoercion.Strict.nShot(request.nShot)
        if (request.runForever && newNShot != null) {
            return EffectUpdateOutcome.Refused("nShot and runForever are two answers to one question — send one")
        }
        // The spread's shape (D4), checked as the effect will run it: this request's Order or the one
        // it has, and the wings it is sent or has — so moving a winged effect onto Centre out is
        // refused as surely as sending wings to one.
        // The instance's own wings are checked only when the Order actually moves: a stored row may
        // hold wings under a mirrored Order (lenient on spawn, ignored by [SpreadShaping]), and an edit
        // of its Spread, Blocks or Groups — or a frame re-sending the Order it already has — must not
        // be refused for them.
        if (request.spread != null || request.wings != null || request.blocks != null || request.groups != null ||
            newDistribution != null
        ) {
            val dyn = existing.dynamics
            val order = newDistribution ?: dyn.distributionStrategy
            val orderMoved = newDistribution != null && newDistribution != dyn.distributionStrategy
            val wings = request.wings ?: dyn.wings.takeIf { orderMoved }
            EffectSpecCoercion.Strict.spreadProblem(request.spread, wings, request.blocks, request.groups, order)
                ?.let { return EffectUpdateOutcome.Refused(it) }
        }
        // Only when the client actually renamed the type; a parameters-only edit leaves the
        // instance's registration id — and the clock it runs on — alone. A type swap takes the new
        // type's timing source with it, as the add path does, or a beat effect swapped for a
        // wall-clock one would run on the beat loop.
        val registration = request.effectType?.let { state.show.fxRegistry.getRegistration(it) }
        val newTimingSource = request.effectType?.let { registration?.timingSource ?: TimingSource.BEAT }

        // D7. Seconds and a master are two answers to one question, so both is refused; a master
        // named on an effect that is on Seconds takes it off Seconds.
        secondsTimingConflict(request.seconds, request.speedMasterUuid, request.rateSpeedMasterUuid)
            ?.let { return EffectUpdateOutcome.Refused(it) }
        val kind = newTimingSource ?: existing.registrationTimingSource
        val namesItsMaster = if (kind == TimingSource.BEAT) newSpeedMaster != null else newRateSpeedMaster != null
        val newSeconds = request.seconds ?: if (namesItsMaster && existing.onSeconds) false else null
        // A wall-clock effect's master *is* its rate master, so leaving Seconds has to say which.
        if (newSeconds == false && kind == TimingSource.WALL_CLOCK && newRateSpeedMaster == null &&
            existing.rateSpeedMasterUuid == null
        ) {
            return EffectUpdateOutcome.Refused(
                "seconds: false on a wall-clock effect names its master — send rateSpeedMasterUuid",
            )
        }

        // A split cannot spread the shape over some heads: each split-off head runs alone. Refused
        // only for a field that would change, so an editor re-sending the shape it shows still splits.
        if (splitHeads != null) {
            val dyn = existing.dynamics
            val shapeMoved = buildList {
                if (newDistribution != null && newDistribution != dyn.distributionStrategy) add("distributionStrategy")
                if (newElementMode != null && newElementMode != dyn.elementMode) add("elementMode")
                if (newElementFilter != null && newElementFilter != dyn.elementFilter) add("elementFilter")
                if (request.spread != null && request.spread != dyn.spread) add("spread")
                if (request.wings != null && request.wings != dyn.wings) add("wings")
                if (request.blocks != null && request.blocks != dyn.blocks) add("blocks")
                if (request.groups != null && request.groups != dyn.groups) add("groups")
            }
            if (shapeMoved.isNotEmpty()) {
                return EffectUpdateOutcome.Refused(
                    "${shapeMoved.joinToString(", ")} spread the effect over all its heads, and the heads " +
                        "split off here would each run alone — select every head the effect runs on to change it",
                )
            }
        }

        fun edit(id: Long) = engine.updateEffect(
            effectId = id,
            newEffect = newEffect,
            newTiming = newTiming,
            newBlendMode = newBlendMode,
            newPhaseOffset = request.phaseOffset,
            newDistributionStrategy = newDistribution,
            newElementMode = newElementMode,
            newElementFilter = newElementFilter,
            newStepTiming = request.stepTiming,
            newSpeedMasterUuid = newSpeedMaster,
            newRateSpeedMasterUuid = newRateSpeedMaster,
            newRegistrationId = registration?.id,
            newTimingSource = newTimingSource,
            newSeconds = newSeconds,
            newSize = newSize,
            newDirection = newDirection,
            newNShot = newNShot,
            clearNShot = request.runForever,
            newSpread = request.spread,
            newWings = request.wings,
            newBlocks = request.blocks,
            newGroups = request.groups,
        )
        if (splitHeads == null) {
            edit(effectId)?.let { EffectUpdateOutcome.Updated(it) } ?: EffectUpdateOutcome.NotFound
        } else {
            // Every head becomes its own copy, still running exactly as it was, and then the named
            // heads' copies take the edit, each through the same in-place edit a whole effect gets.
            val copies = splitIntoHeads(state, effectId) ?: return EffectUpdateOutcome.NotFound
            val (named, rest) = copies.partition { it.target.targetKey in splitHeads }
            EffectUpdateOutcome.Split(edited = named.mapNotNull { edit(it.id) }, rest = rest)
        }
    } catch (e: Exception) {
        EffectUpdateOutcome.Refused(e.message ?: "Failed to update effect")
    }
}

/**
 * A new effect's Size, Direction, Runs and spread shape (effects plan D2, D5, D9, D4) from a request,
 * the FX's own direction where it names none, strictly: a size outside 0–1, an unknown direction, a count below one or a shape out of range (or
 * wings under a mirrored order — read from the instance, so its Order is set first) throws for the
 * add route's 400. Shared by the fixture and group add routes, as [markProgrammerOwned] is.
 */
internal fun FxInstance.applyRequestedDynamics(size: Double, direction: String?, nShot: Int?, shape: SpreadShape) {
    val checkedSize = EffectSpecCoercion.Strict.size(size)
    val checkedDirection = direction?.let { EffectSpecCoercion.Strict.direction(it) } ?: effect.defaultDirection
    val checkedNShot = EffectSpecCoercion.Strict.nShot(nShot)
    val checkedShape = EffectSpecCoercion.Strict.spreadShape(shape, distributionStrategy)
    updateDynamics {
        it.copy(
            size = checkedSize, direction = checkedDirection, nShot = checkedNShot,
            spread = checkedShape.spread, wings = checkedShape.wings,
            blocks = checkedShape.blocks, groups = checkedShape.groups,
        )
    }
}

/**
 * Stamp an effect into the programmer's reserved priority band when the request asked for
 * it. Shared by the fixture (`/fx/add`) and group (`/groups/{name}/fx`) add routes so the
 * two can't drift. Must run *before* [FxEngine.addEffect], which reads the priority to
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

private fun FxInstance.toIndirectDto() = IndirectEffectDto(
    id = id,
    effectType = effectTypeId,
    groupName = target.targetKey,
    targetType = target.targetRef.discriminator,
    propertyName = target.propertyName,
    beatDivision = timing.beatDivision,
    blendMode = blendMode.name,
    isRunning = isRunning,
    phaseOffset = phaseOffset,
    currentPhase = lastPhase,
    parameters = effect.parameters,
    distributionStrategy = distributionStrategy.javaClass.simpleName,
    stepTiming = stepTiming,
    programmerOwned = FxEngine.isProgrammerFxPriority(priority),
    intensityMultiplier = intensityMultiplier,
    speedMasterUuid = reportedSpeedMasterUuid,
    rateSpeedMasterUuid = reportedRateSpeedMasterUuid,
    seconds = onSeconds,
    size = dynamics.size,
    direction = dynamics.direction.name,
    nShot = dynamics.nShot,
    spread = dynamics.spread,
    wings = dynamics.wings,
    blocks = dynamics.blocks,
    groups = dynamics.groups,
    spent = spent,
)

/**
 * The instance [request] describes, checked and ready for [FxEngine.addEffect] — `POST /fx/add`'s
 * body, shared with the programmer's bare effect press (`POST /programmer/effects`, apply-model plan
 * §3.4), so the two build the same effect from the same fields. Throws on anything the strict policy
 * refuses; the callers answer 400.
 */
internal fun buildEffectInstance(state: State, request: AddEffectRequest): FxInstance {
    val effect = createEffectFromRequest(request, state)
    // The effect first: its output type is what tells FxTargetFactory that a
    // `pan`/`tilt` property name means the position pair (sweep item A11).
    val target = createTargetFromRequest(request, state, effect.outputType)
    requireOutputTypeMatch(effect, target)
    val registration = state.show.fxRegistry.getRegistration(request.effectType)
    val timing = FxTiming(
        beatDivision = request.beatDivision ?: registration?.defaultBeatDivision ?: BeatDivision.ONE_BAR,
        startOnBeat = request.startOnBeat
    )
    val blendMode = EffectSpecCoercion.Strict.blendMode(request.blendMode)

    val instance = FxInstance(effect, target, timing, blendMode)
    instance.phaseOffset = request.phaseOffset
    request.distributionStrategy?.let {
        instance.distributionStrategy = EffectSpecCoercion.Strict.distribution(it)
    }
    request.elementMode?.let {
        instance.elementMode = EffectSpecCoercion.Strict.elementMode(it)
    }
    request.elementFilter?.let {
        instance.elementFilter = EffectSpecCoercion.Strict.elementFilter(it)
    }
    request.stepTiming?.let {
        instance.stepTiming = it
    }
    instance.applyRequestedDynamics(
        request.size, request.direction, request.nShot,
        SpreadShape(request.spread, request.wings, request.blocks, request.groups),
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
            "No fixture in targets has '${request.propertyName}'"
        }
    }
    return instance
}

private fun createTargetFromRequest(
    request: AddEffectRequest,
    state: State,
    outputType: FxOutputType?,
): FxTarget {
    request.targets?.let { return createTargetFromList(it, request.propertyName, state, outputType) }
    require(request.fixtureKey.isNotBlank()) { "Name the effect's fixtureKey, or its targets" }
    // `untypedGroupableFixture`, as `EffectSpawner.createFixtureTargetForCue` does: the two doors
    // must build the same target for the same cell, and the factory's fallback reads its catalogue.
    val fixture = try {
        state.show.fixtures.untypedGroupableFixture(request.fixtureKey)
    } catch (_: Exception) { null }
    return FxTargetFactory.forFixture(request.fixtureKey, request.propertyName, outputType, fixture)
}

/**
 * The target for a `targets` list: every item must name a fixture, cell or group the rig has (all
 * problems at once). Whether any of them has the property is the route's check, on the instance.
 */
private fun createTargetFromList(
    targets: List<CueTargetDto>,
    propertyName: String,
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
    val target = EffectTargetRef.of(refs)
    // No effect may drive a one-shot trigger or a fixture command on anything the list names.
    TriggerIndex.refusalLive(fixtures, target.discriminator, target.key, propertyName, "effect")
        ?.let { throw TriggerNotStorableException(it) }
    CommandIndex.refusalLive(fixtures, target.discriminator, target.key, propertyName, "effect")
        ?.let { throw CommandNotStorableException(it) }
    val fxTarget = when (target) {
        is EffectTargetRef.Selection -> EffectSpawner.createSelectionTarget(state, target, propertyName, outputType)
        is EffectTargetRef.Single -> when (val single = target.target) {
            is TargetRef.Fixture -> FxTargetFactory.forFixture(
                single.key, propertyName, outputType, runCatching { fixtures.untypedGroupableFixture(single.key) }.getOrNull(),
            )
            is TargetRef.Group -> FxTargetFactory.forGroup(
                single.key, propertyName, outputType, fixtures.referenceFixtureFor(target, propertyName) as? Fixture,
            )
        }
    }
    return fxTarget
}

private fun createEffectFromRequest(request: AddEffectRequest, state: State): Effect {
    state.show.fxRegistry.fxWriteProblem(request.effectType, request.parameters)
        ?.let { throw IllegalArgumentException(it) }
    return state.show.fxRegistry.createEffectWithTemplates(
        state.show.templateRegistry,
        request.effectType,
        request.parameters,
    )
}

// Effect creation, parse helpers, and the effect library have moved to the fx package:
// - fx/EffectParamUtils.kt (parseExtendedColour, parseColor, toUByteParam, toEasingCurveParam)
// - fx/FxRegistry.kt (FxRegistry.createEffect replaces createEffectFromTypeAndParams)
// - fx/FxFileLoader.kt (loads built-in effects from .fx.kts resource files)
