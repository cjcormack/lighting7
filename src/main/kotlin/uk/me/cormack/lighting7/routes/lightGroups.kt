package uk.me.cormack.lighting7.routes

import io.ktor.http.*
import io.ktor.resources.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.resources.*
import io.ktor.server.resources.post
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fixture.group.*
import uk.me.cormack.lighting7.fx.*
import uk.me.cormack.lighting7.fx.group.DistributionStrategy
import uk.me.cormack.lighting7.fx.group.clearFx
import uk.me.cormack.lighting7.state.State

/**
 * REST API routes for fixture group management and effects.
 */
internal fun Route.routeApiRestGroups(state: State) {
    route("/groups") {
        // List all groups
        get<GroupsResource> {
            val currentProject = state.projectManager.currentProject
            val looks = loadLookCompatibilityInfos(state, currentProject.id.value)

            val groups = state.show.fixtures.groups.map { group ->
                val capabilities = group.detectCapabilities().toSet()
                val memberTypeKeys = group.fixtures.filterIsInstance<Fixture>().map { it.typeKey }.toSet()
                group.toDto(looks.compatibleIdsFor(capabilities))
            }
            call.respond(groups)
        }

        // Get a specific group
        get<GroupResource> { resource ->
            try {
                val group = state.show.fixtures.untypedGroup(resource.name)

                val currentProject = state.projectManager.currentProject
                val looks = loadLookCompatibilityInfos(state, currentProject.id.value)

                val capabilities = group.detectCapabilities().toSet()
                val memberTypeKeys = group.fixtures.filterIsInstance<Fixture>().map { it.typeKey }.toSet()

                call.respond(group.toDetailedDto(looks.compatibleIdsFor(capabilities)))
            } catch (e: IllegalStateException) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(e.message ?: "Group not found"))
            }
        }

        // Get group properties (aggregated property descriptors for all members)
        get<GroupPropertiesResource> { resource ->
            try {
                val group = state.show.fixtures.untypedGroup(resource.name)
                val properties = group.generateGroupPropertyDescriptors()
                call.respond(properties)
            } catch (e: IllegalStateException) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(e.message ?: "Group not found"))
            }
        }

        // Apply effect to a group
        post<GroupFxResource> { resource ->
            val request = call.receive<AddGroupFxRequest>()
            try {
                val group = state.show.fixtures.untypedGroup(resource.name)
                val effectId = applyGroupEffect(state, group, request)
                call.respond(AddGroupFxResponse(effectId))
            } catch (e: IllegalStateException) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(e.message ?: "Group not found"))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Failed to apply effect"))
            }
        }

        // Clear all effects for a group
        delete<GroupFxResource> { resource ->
            try {
                val group = state.show.fixtures.untypedGroup(resource.name)
                val count = group.clearFx(state.show.fxEngine)
                call.respond(ClearGroupFxResponse(count))
            } catch (e: IllegalStateException) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(e.message ?: "Group not found"))
            }
        }

        // Get active effects for a group
        get<GroupActiveFxResource> { resource ->
            try {
                // Validate group exists
                state.show.fixtures.untypedGroup(resource.name)

                val engine = state.show.fxEngine
                val effects = engine.getEffectsForGroup(resource.name)
                val dtos = effects.map { instance ->
                    val expanded = engine.isMultiElementExpanded(instance)
                    GroupEffectDto(
                        id = instance.id,
                        // The display name is kept as the fallback so a built-in still reports the
                        // spaced form this endpoint has always sent (the registry normalises it
                        // back on the way in); a user-defined effect's name resolves to nothing,
                        // so it has to report its registration id.
                        effectType = instance.registrationId ?: instance.effect.name,
                        propertyName = instance.target.propertyName,
                        beatDivision = instance.timing.beatDivision,
                        blendMode = instance.blendMode.name,
                        distribution = instance.distributionStrategy.javaClass.simpleName,
                        elementMode = if (expanded) instance.elementMode.name else null,
                        elementFilter = if (instance.elementFilter != ElementFilter.ALL)
                            instance.elementFilter.name else null,
                        stepTiming = instance.stepTiming,
                        isRunning = instance.isRunning,
                        phaseOffset = instance.phaseOffset,
                        currentPhase = instance.lastPhase,
                        parameters = instance.effect.parameters,
                        cueId = instance.cueId,
                        programmerOwned = FxEngine.isProgrammerFxPriority(instance.priority),
                        intensityMultiplier = instance.intensityMultiplier,
                        // Gated exactly as EffectDto and IndirectEffectDto are — this was the
                        // one effect report sweep item B4 missed, so a BEAT-timed group effect
                        // showed a live rate-master chip for a field it cannot read.
                        speedMasterUuid = instance.reportedSpeedMasterUuid,
                        rateSpeedMasterUuid = instance.reportedRateSpeedMasterUuid,
                    )
                }
                call.respond(dtos)
            } catch (e: IllegalStateException) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(e.message ?: "Group not found"))
            }
        }

        // Get distribution strategies
        get<DistributionStrategiesResource> {
            call.respond(DistributionStrategiesResponse(
                strategies = DistributionStrategy.availableStrategies
            ))
        }
    }
}

// Resource definitions
@Resource("/")
data object GroupsResource

@Resource("/{name}")
data class GroupResource(val name: String)

@Resource("/{name}/properties")
data class GroupPropertiesResource(val name: String)

@Resource("/{name}/fx")
data class GroupFxResource(val name: String)

@Resource("/{name}/fx/active")
data class GroupActiveFxResource(val name: String)

@Resource("/distribution-strategies")
data object DistributionStrategiesResource

// DTOs
@Serializable
data class GroupSummaryDto(
    val name: String,
    val memberCount: Int,
    val capabilities: List<String>,
    val symmetricMode: String,
    val defaultDistribution: String,
    val compatibleLookIds: List<Int> = emptyList()
)

@Serializable
data class GroupMemberDto(
    val fixtureKey: String,
    val fixtureName: String,
    val index: Int,
    val normalizedPosition: Double,
    val panOffset: Double,
    val tiltOffset: Double,
    val symmetricInvert: Boolean,
    val tags: List<String>
)

@Serializable
data class GroupDetailDto(
    val name: String,
    val memberCount: Int,
    val capabilities: List<String>,
    val symmetricMode: String,
    val defaultDistribution: String,
    val members: List<GroupMemberDto>,
    val compatibleLookIds: List<Int> = emptyList()
)

@Serializable
data class AddGroupFxRequest(
    val effectType: String,
    val propertyName: String,  // "dimmer", "colour", "position", "uv"
    val beatDivision: Double = BeatDivision.QUARTER,
    val blendMode: String = "OVERRIDE",
    val distribution: String = "LINEAR",
    val phaseOffset: Double = 0.0,
    val parameters: Map<String, String> = emptyMap(),
    val elementMode: String = "PER_FIXTURE",  // PER_FIXTURE or FLAT
    val elementFilter: String = "ALL",
    val stepTiming: Boolean? = null,
    /** Speed master to subscribe to, as the master's uuid (null → master 1). */
    val speedMasterUuid: String? = null,
    /** Wall-clock rate master (null → unscaled). Only meaningful for WALL_CLOCK effects. */
    val rateSpeedMasterUuid: String? = null,
    /**
     * Create this effect in the programmer's reserved priority band
     * ([FxEngine.PROGRAMMER_FX_PRIORITY_BASE]). See [AddEffectRequest.programmerOwned].
     */
    val programmerOwned: Boolean = false,
)

@Serializable
data class AddGroupFxResponse(
    val effectId: Long
)

@Serializable
data class GroupEffectDto(
    val id: Long,
    val effectType: String,
    val propertyName: String,
    val beatDivision: Double,
    val blendMode: String,
    val distribution: String,
    val elementMode: String? = null,
    val elementFilter: String? = null,
    val stepTiming: Boolean = false,
    val isRunning: Boolean,
    val phaseOffset: Double,
    val currentPhase: Double,
    val parameters: Map<String, String>,
    val cueId: Int? = null,
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
data class ClearGroupFxResponse(
    val removedCount: Int
)

@Serializable
data class DistributionStrategiesResponse(
    val strategies: List<String>
)

// Helper functions
private fun FixtureGroup<*>.toDto(compatibleLookIds: List<Int> = emptyList()): GroupSummaryDto {
    return GroupSummaryDto(
        name = name,
        memberCount = memberCount,  // Uses allMembers.size (includes subgroups)
        capabilities = detectCapabilities(),
        symmetricMode = metadata.symmetricMode.name,
        defaultDistribution = metadata.defaultDistributionName,
        compatibleLookIds = compatibleLookIds
    )
}

private fun FixtureGroup<*>.toDetailedDto(compatibleLookIds: List<Int> = emptyList()): GroupDetailDto {
    return GroupDetailDto(
        name = name,
        memberCount = memberCount,  // Uses allMembers.size (includes subgroups)
        capabilities = detectCapabilities(),
        symmetricMode = metadata.symmetricMode.name,
        defaultDistribution = metadata.defaultDistributionName,
        members = allMembers.map { member ->  // Use allMembers to include subgroups
            GroupMemberDto(
                fixtureKey = member.key,
                fixtureName = member.name,
                index = member.index,
                normalizedPosition = member.normalizedPosition,
                panOffset = member.metadata.panOffset,
                tiltOffset = member.metadata.tiltOffset,
                symmetricInvert = member.metadata.symmetricInvert,
                tags = member.metadata.tags.toList()
            )
        },
        compatibleLookIds = compatibleLookIds
    )
}

/**
 * Check whether a group supports a given property, either directly on the
 * fixtures or via multi-element fixture elements.
 *
 * The dispatch itself is [fixturesSupportProperty], shared with the effect arm of a template apply
 * — a head is a group of one, and two copies of the trait dispatch could disagree about the same
 * fixture.
 */
private fun groupSupportsProperty(group: FixtureGroup<*>, propertyName: String): Boolean =
    fixturesSupportProperty(group.fixtures, propertyName)

private fun applyGroupEffect(
    state: State,
    group: FixtureGroup<*>,
    request: AddGroupFxRequest
): Long {
    val engine = state.show.fxEngine
    val effect = state.show.fxRegistry.createEffectWithTemplates(
        state.show.templateRegistry,
        request.effectType,
        request.parameters,
    )
    val timing = FxTiming(request.beatDivision)
    val blendMode = EffectSpecCoercion.Strict.blendMode(request.blendMode)
    val distribution = EffectSpecCoercion.Strict.distribution(request.distribution)
    val elementMode = EffectSpecCoercion.Strict.elementMode(request.elementMode)

    // Validate property support (direct or via elements)
    if (!groupSupportsProperty(group, request.propertyName)) {
        throw IllegalStateException(
            "Not all fixtures in group support ${request.propertyName} " +
                "(directly or via elements)"
        )
    }

    val target = FxTargetFactory.forGroup(
        group.name,
        request.propertyName,
        effect.outputType,
        group.fixtures.firstOrNull() as? Fixture,
    )
    requireOutputTypeMatch(effect, target)

    val elFilter = EffectSpecCoercion.Strict.elementFilter(request.elementFilter)

    // Create SINGLE FxInstance for the entire group
    val instance = FxInstance(effect, target, timing, blendMode).apply {
        registrationId = state.show.fxRegistry.getRegistration(request.effectType)?.id
        phaseOffset = request.phaseOffset
        distributionStrategy = distribution
        this.elementMode = elementMode
        this.elementFilter = elFilter
        request.stepTiming?.let { this.stepTiming = it }
        speedMasterUuid = requireSpeedMasterUuid(request.speedMasterUuid)
        rateSpeedMasterUuid = requireSpeedMasterUuid(request.rateSpeedMasterUuid)
    }

    markProgrammerOwned(instance, request.programmerOwned)

    return engine.addEffect(instance)
}

