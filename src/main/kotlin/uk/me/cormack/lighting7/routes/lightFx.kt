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

            post<ClockBpm> {
                val request = call.receive<SetBpmRequest>()
                state.show.fxEngine.masterClock.setBpm(request.bpm)
                call.respond(ClockStatusResponse(
                    bpm = state.show.fxEngine.masterClock.bpm.value,
                    isRunning = state.show.fxEngine.masterClock.isRunning.value
                ))
            }

            post<ClockTap> {
                state.show.fxEngine.masterClock.tap()
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
                    state.show.fxRegistry.createEffect(
                        effectType, params,
                        paletteSupplier = engine::getPalette,
                        paletteVersionSupplier = { engine.paletteVersion },
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
                    newStepTiming = request.stepTiming
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
    val stepTiming: Boolean? = null
)

@Serializable
data class AddEffectResponse(val effectId: Long)

@Serializable
data class ClearEffectsResponse(val removedCount: Int)

@Serializable
data class ErrorResponse(val error: String)

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
    val stepTiming: Boolean = false
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
    val stepTiming: Boolean? = null
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
    val cueId: Int? = null,
    val timingSource: String = "BEAT",
)


// Helper functions

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
    cueId = cueId,
    timingSource = timingSource.name,
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
    stepTiming = stepTiming
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
    val engine = state.show.fxEngine
    return state.show.fxRegistry.createEffect(
        request.effectType,
        request.parameters,
        paletteSupplier = engine::getPalette,
        paletteVersionSupplier = { engine.paletteVersion },
    )
}

// Effect creation, parse helpers, and the effect library have moved to the fx package:
// - fx/EffectParamUtils.kt (parseExtendedColour, parseColor, toUByteParam, toEasingCurveParam)
// - fx/FxRegistry.kt (FxRegistry.createEffect replaces createEffectFromTypeAndParams)
// - fx/BuiltInEffects.kt (registerBuiltInEffects replaces effectLibrary)
