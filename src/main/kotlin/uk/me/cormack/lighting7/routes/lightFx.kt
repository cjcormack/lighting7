package uk.me.cormack.lighting7.routes

import io.ktor.http.*
import io.ktor.resources.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.resources.*
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import uk.me.cormack.lighting7.fx.group.DistributionStrategy
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fixture.property.Slider
import uk.me.cormack.lighting7.fx.*
import uk.me.cormack.lighting7.models.SpeedMasterSource
import uk.me.cormack.lighting7.state.State

/**
 * REST API routes for FX (effects) control.
 */
internal fun Route.routeApiRestFx(state: State) {
    route("/fx") {
        // Master Clock endpoints
        route("/clock") {
            get<ClockStatus> {
                val clock = state.show.fxEngine.masterClock
                call.respond(ClockStatusResponse(
                    bpm = clock.bpm.value,
                    isRunning = clock.isRunning.value
                ))
            }

            // The legacy clock endpoints mean master 1; routing through the bank keeps
            // source tracking, the speedMasters.changed push, and write-through working.
            post<ClockBpm> {
                val request = call.receive<SetBpmRequest>()
                state.show.fxEngine.speedMasters.setBpm(null, request.bpm, SpeedMasterSource.MANUAL)
                call.respond(ClockStatusResponse(
                    bpm = state.show.fxEngine.masterClock.bpm.value,
                    isRunning = state.show.fxEngine.masterClock.isRunning.value
                ))
            }

            post<ClockTap> {
                state.show.fxEngine.speedMasters.tap(null)
                call.respond(ClockStatusResponse(
                    bpm = state.show.fxEngine.masterClock.bpm.value,
                    isRunning = state.show.fxEngine.masterClock.isRunning.value
                ))
            }
        }

        // Active effects endpoints
        get<ActiveEffects> {
            val engine = state.show.fxEngine
            val effects = engine.getActiveEffects().map { it.toDto(engine.isMultiElementExpanded(it)) }
            call.respond(effects)
        }

        post<AddEffect> {
            val request = call.receive<AddEffectRequest>()
            try {
                val effect = createEffectFromRequest(request, state)
                val target = createTargetFromRequest(request, state)
                val timing = FxTiming(
                    beatDivision = request.beatDivision,
                    startOnBeat = request.startOnBeat
                )
                val blendMode = BlendMode.valueOf(request.blendMode)

                val instance = FxInstance(effect, target, timing, blendMode)
                instance.phaseOffset = request.phaseOffset
                request.distributionStrategy?.let {
                    instance.distributionStrategy = DistributionStrategy.fromName(it)
                }
                request.elementFilter?.let {
                    instance.elementFilter = ElementFilter.fromName(it)
                }
                request.stepTiming?.let {
                    instance.stepTiming = it
                }

                // Propagate timing source from the effect's registration
                val registration = state.show.fxRegistry.getRegistration(request.effectType)
                registration?.timingSource?.let { instance.timingSource = it }

                instance.speedMasterUuid = requireSpeedMasterUuid(request.speedMasterUuid)
                instance.rateSpeedMasterUuid = requireSpeedMasterUuid(request.rateSpeedMasterUuid)

                markProgrammerOwned(instance, request.programmerOwned)

                val effectId = state.show.fxEngine.addEffect(instance)
                call.respond(AddEffectResponse(effectId))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Unknown error"))
            }
        }

        delete<EffectId> {
            val removed = state.show.fxEngine.removeEffect(it.id)
            if (removed) {
                call.respond(HttpStatusCode.OK)
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

        // Update a running effect
        put<EffectId> { resource ->
            val request = call.receive<UpdateEffectRequest>()
            try {
                val engine = state.show.fxEngine
                val existing = engine.getEffect(resource.id)
                if (existing == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Effect not found"))
                    return@put
                }

                // Resolve new effect if type or parameters changed
                val newEffect = if (request.effectType != null || request.parameters != null) {
                    val effectType = request.effectType ?: existing.effect.name.replace(" ", "")
                    val params = request.parameters ?: existing.effect.parameters
                    // On the request thread, before the effect can tick — see
                    // [prewarmTemplateColours].
                    val templates = state.show.templateRegistry
                    prewarmTemplateColours(templates, params)
                    state.show.fxRegistry.createEffect(
                        effectType, params,
                        resolveColourSource = templateColourSource(templates),
                        colourSourceVersion = { templates.version },
                    )
                } else null

                val newTiming = request.beatDivision?.let { FxTiming(it, existing.timing.startOnBeat) }
                val newBlendMode = request.blendMode?.let { BlendMode.valueOf(it) }
                val newDistribution = request.distributionStrategy?.let { DistributionStrategy.fromName(it) }
                val newElementMode = request.elementMode?.let { ElementMode.valueOf(it) }
                val newElementFilter = request.elementFilter?.let { ElementFilter.fromName(it) }

                val updated = engine.updateEffect(
                    effectId = resource.id,
                    newEffect = newEffect,
                    newTiming = newTiming,
                    newBlendMode = newBlendMode,
                    newPhaseOffset = request.phaseOffset,
                    newDistributionStrategy = newDistribution,
                    newElementMode = newElementMode,
                    newElementFilter = newElementFilter,
                    newStepTiming = request.stepTiming,
                    newSpeedMasterUuid = requireSpeedMasterUuid(request.speedMasterUuid),
                    newRateSpeedMasterUuid = requireSpeedMasterUuid(request.rateSpeedMasterUuid),
                )

                if (updated != null) {
                    call.respond(updated.toDto(engine.isMultiElementExpanded(updated)))
                } else {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Effect not found"))
                }
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Failed to update effect"))
            }
        }

        // Get active effects for a specific fixture (direct + indirect via groups)
        get<FixtureEffects> { resource ->
            val fixtureKey = resource.fixtureKey
            val engine = state.show.fxEngine
            val direct = engine.getEffectsForFixture(fixtureKey).map { it.toDto(engine.isMultiElementExpanded(it)) }
            val indirect = engine.getIndirectEffectsForFixture(fixtureKey).map { it.toIndirectDto() }
            call.respond(FixtureEffectsResponse(direct, indirect))
        }

        // Clear all effects for a fixture
        delete<FixtureEffects> {
            val count = state.show.fxEngine.removeEffectsForFixture(it.fixtureKey)
            call.respond(ClearEffectsResponse(count))
        }

        // Clear all effects
        post<ClearAll> {
            state.show.fxEngine.clearAllEffects()
            call.respond(HttpStatusCode.OK)
        }

        // Effect library - list available effect types
        get<EffectLibrary> {
            call.respond(state.show.fxRegistry.getLibrary())
        }
    }
}

// Resource classes for type-safe routing

@Resource("/clock/status")
data object ClockStatus

@Resource("/clock/bpm")
data object ClockBpm

@Resource("/clock/tap")
data object ClockTap

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
}

@Resource("/fixture/{fixtureKey}")
data class FixtureEffects(val fixtureKey: String)

@Resource("/clear")
data object ClearAll

@Resource("/library")
data object EffectLibrary

// Request/Response DTOs

@Serializable
data class ClockStatusResponse(
    val bpm: Double,
    val isRunning: Boolean
)

@Serializable
data class SetBpmRequest(val bpm: Double)

@Serializable
data class AddEffectRequest(
    val effectType: String,
    val fixtureKey: String,
    val propertyName: String,
    val beatDivision: Double = BeatDivision.QUARTER,
    val blendMode: String = "OVERRIDE",
    val startOnBeat: Boolean = true,
    val phaseOffset: Double = 0.0,
    val parameters: Map<String, String> = emptyMap(),
    val distributionStrategy: String? = null,
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
data class ClearEffectsResponse(val removedCount: Int)

@Serializable
data class ErrorResponse(
    val error: String,
    /** Machine-readable error code for responses a client branches on (e.g. delete guards). */
    val code: String? = null,
)

@Serializable
data class FixtureEffectsResponse(
    val direct: List<EffectDto>,
    val indirect: List<IndirectEffectDto>
)

@Serializable
data class IndirectEffectDto(
    val id: Long,
    val effectType: String,
    val groupName: String,
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
    /** Fade envelope in `[0, 1]`; the effect's output is scaled by this before blending. */
    val intensityMultiplier: Double = 1.0,
    /** Speed master this effect subscribes to (null → master 1). */
    val speedMasterUuid: String? = null,
    /** Wall-clock rate master (null → unscaled). Only meaningful for WALL_CLOCK effects. */
    val rateSpeedMasterUuid: String? = null,
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
)

@Serializable
data class EffectDto(
    val id: Long,
    val effectType: String,
    val targetKey: String,
    val propertyName: String,
    val beatDivision: Double,
    val blendMode: String,
    val isRunning: Boolean,
    val phaseOffset: Double,
    val currentPhase: Double,
    val parameters: Map<String, String>,
    val isGroupTarget: Boolean,
    val distributionStrategy: String? = null,
    val elementMode: String? = null,
    val elementFilter: String? = null,
    val stepTiming: Boolean = false,
    val presetId: Int? = null,
    /**
     * The Look this effect came from, when it came from one.
     *
     * Separate from [presetId], which names a `DaoFxPreset` — the two are ids in different tables.
     * The Look toggle used to stamp the Look id into `presetId`, which is what made the busking
     * pads' active ring work and `captureCurrentState` mint a preset application naming an
     * unrelated preset. Now that a Look reaches the programmer as a layer, `presetId` stays null and
     * this is the field to match on.
     */
    val lookId: Int? = null,
    /** The programmer layer that spawned this effect, when one did. */
    val programmerLayerId: Int? = null,
    val cueId: Int? = null,
    val timingSource: String = "BEAT",
    /** True when this effect sits in the programmer's reserved priority band. */
    val programmerOwned: Boolean = false,
    /** Fade envelope in `[0, 1]`; the effect's output is scaled by this before blending. */
    val intensityMultiplier: Double = 1.0,
    /** Speed master this effect subscribes to (null → master 1). */
    val speedMasterUuid: String? = null,
    /** Wall-clock rate master (null → unscaled). Only meaningful for WALL_CLOCK effects. */
    val rateSpeedMasterUuid: String? = null,
)


// Helper functions

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

private fun FxInstance.toDto(isMultiElementExpanded: Boolean = false) = EffectDto(
    id = id,
    effectType = effect.name.replace(" ", ""),
    targetKey = target.targetKey,
    propertyName = target.propertyName,
    beatDivision = timing.beatDivision,
    blendMode = blendMode.name,
    isRunning = isRunning,
    phaseOffset = phaseOffset,
    currentPhase = lastPhase,
    parameters = effect.parameters,
    isGroupTarget = isGroupEffect,
    distributionStrategy = if (isGroupEffect || isMultiElementExpanded)
        distributionStrategy.javaClass.simpleName else null,
    elementMode = if (isGroupEffect && isMultiElementExpanded)
        elementMode.name else null,
    elementFilter = if ((isGroupEffect || isMultiElementExpanded) && elementFilter != ElementFilter.ALL)
        elementFilter.name else null,
    stepTiming = stepTiming,
    presetId = presetId,
    lookId = lookId,
    programmerLayerId = programmerLayerId,
    cueId = cueId,
    timingSource = timingSource.name,
    programmerOwned = FxEngine.isProgrammerFxPriority(priority),
    intensityMultiplier = intensityMultiplier,
    speedMasterUuid = speedMasterUuid?.toString(),
    rateSpeedMasterUuid = rateSpeedMasterUuid?.toString(),
)

private fun FxInstance.toIndirectDto() = IndirectEffectDto(
    id = id,
    effectType = effect.name.replace(" ", ""),
    groupName = target.targetKey,
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
    speedMasterUuid = speedMasterUuid?.toString(),
    rateSpeedMasterUuid = rateSpeedMasterUuid?.toString(),
)

private fun createTargetFromRequest(request: AddEffectRequest, state: State): FxTarget {
    return when (request.propertyName) {
        "dimmer" -> SliderTarget(request.fixtureKey, "dimmer")
        "uv" -> SliderTarget(request.fixtureKey, "uv")
        "rgbColour", "colour" -> ColourTarget(request.fixtureKey)
        "position" -> PositionTarget(request.fixtureKey)
        else -> {
            // Check if the property is a slider or a setting on the fixture
            val fixture = try {
                state.show.fixtures.untypedFixture(request.fixtureKey) as? Fixture
            } catch (_: Exception) { null }
            val prop = fixture?.fixtureProperties?.find { it.name == request.propertyName }
            val propValue = prop?.classProperty?.call(fixture)
            if (propValue is Slider) {
                SliderTarget(request.fixtureKey, request.propertyName)
            } else {
                SettingTarget(request.fixtureKey, request.propertyName)
            }
        }
    }
}

private fun createEffectFromRequest(request: AddEffectRequest, state: State): Effect {
    // On the request thread, before the effect can tick — see [prewarmTemplateColours].
    val templates = state.show.templateRegistry
    prewarmTemplateColours(templates, request.parameters)
    return state.show.fxRegistry.createEffect(
        request.effectType,
        request.parameters,
        resolveColourSource = templateColourSource(templates),
        colourSourceVersion = { templates.version },
    )
}

// Effect creation, parse helpers, and the effect library have moved to the fx package:
// - fx/EffectParamUtils.kt (parseExtendedColour, parseColor, toUByteParam, toEasingCurveParam)
// - fx/FxRegistry.kt (FxRegistry.createEffect replaces createEffectFromTypeAndParams)
// - fx/FxFileLoader.kt (loads built-in effects from .fx.kts resource files)
