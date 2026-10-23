package uk.me.cormack.lighting7.routes

import uk.me.cormack.lighting7.fixture.TriggerIndex
import uk.me.cormack.lighting7.fixture.TriggerNotStorableException
import uk.me.cormack.lighting7.fixture.CommandIndex
import uk.me.cormack.lighting7.fixture.CommandNotStorableException

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
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.me.cormack.lighting7.models.RigScope
import uk.me.cormack.lighting7.state.State

/**
 * REST API routes for fixture group management and effects.
 */
internal fun Route.routeApiRestGroups(state: State) {
    route("/groups") {
        // List all groups
        get<GroupsResource> {
            val currentProject = state.projectManager.currentProject
            val colours = groupColoursByName(state, currentProject.id.value)

            val groups = state.show.fixtures.groups.map { group -> group.toGroupSummaryDto(colours[group.name]) }
            call.respond(groups)
        }

        // Get a specific group
        get<GroupResource> { resource ->
            try {
                val group = state.show.fixtures.untypedGroup(resource.name)
                call.respond(group.toDetailedDto())
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
                        // The display name is the last fallback, for an effect a script constructed
                        // itself; a display name need not resolve, so an id comes first.
                        effectType = instance.registrationId ?: instance.effect.registrationId ?: instance.effect.name,
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
                        seconds = instance.onSeconds,
                        size = instance.dynamics.size,
                        direction = instance.dynamics.direction.name,
                        nShot = instance.dynamics.nShot,
                        spread = instance.dynamics.spread,
                        wings = instance.dynamics.wings,
                        blocks = instance.dynamics.blocks,
                        groups = instance.dynamics.groups,
                        spent = instance.spent,
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
    /** Always empty: a Look lands whole (absolute-looks plan P7). Kept on the wire until the client stops reading it. */
    val compatibleLookIds: List<Int> = emptyList(),
    /**
     * Every member's key in the group's order (`sort_order`, venue-visit plan S7) — the order its
     * effects and a spread over it run in, which the Groups list draws its member rows in.
     */
    val memberKeys: List<String> = emptyList(),
    /**
     * The group's colour (tables-colours plan D7) as the show's rig draws it — a venue group's recolour by
     * the show when it has one; null is none. Read from the stored group, since the loader builds nothing
     * from it.
     */
    val colour: String? = null,
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
    /** Always empty: a Look lands whole (absolute-looks plan P7). Kept on the wire until the client stops reading it. */
    val compatibleLookIds: List<Int> = emptyList(),
    /**
     * Each strategy's step per member, by `index` (`DistributionStrategy.stepsBySize`): the order
     * the group sheet shows effects and a spread running in under that strategy (venue-visit plan S7)
     * — over the members; an effect expanded to multi-head members' cells distributes over the cells.
     */
    val distributionSteps: Map<String, List<Int>> = emptyMap(),
)

@Serializable
data class AddGroupFxRequest(
    val effectType: String,
    val propertyName: String,  // "dimmer", "colour", "position", "uv"
    /** The FX's own default cycle when omitted — see [AddEffectRequest.beatDivision]. */
    val beatDivision: Double? = null,
    val blendMode: String = "OVERRIDE",
    val distribution: String = "NORMAL",
    val phaseOffset: Double = 0.0,
    val parameters: Map<String, String> = emptyMap(),
    val elementMode: String = "PER_FIXTURE",  // PER_FIXTURE or FLAT
    val elementFilter: String = "ALL",
    val stepTiming: Boolean? = null,
    /** Speed master to subscribe to, as the master's uuid (null → master 1). */
    val speedMasterUuid: String? = null,
    /** Wall-clock rate master (null → unscaled). Only meaningful for WALL_CLOCK effects. */
    val rateSpeedMasterUuid: String? = null,
    /** On *Seconds* (D7) — see [AddEffectRequest.seconds]. */
    val seconds: Boolean = false,
    /** Size (effects plan D2), 0–1 — see [AddEffectRequest.size]. */
    val size: Double = 1.0,
    /** Direction (D5) — see [AddEffectRequest.direction]. */
    val direction: String? = null,
    /** Runs (D9) — see [AddEffectRequest.nShot]. */
    val nShot: Int? = null,
    /** The spread's shape (D4) — see [AddEffectRequest.spread]. */
    val spread: Double = 1.0,
    val wings: Int = 1,
    val blocks: Int = 1,
    val groups: Int = 0,
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
    /** The envelope — see [uk.me.cormack.lighting7.fx.EffectDto.intensityMultiplier]. */
    val intensityMultiplier: Double = 1.0,
    /** Speed master this effect subscribes to (null → master 1). */
    val speedMasterUuid: String? = null,
    /** Wall-clock rate master (null → unscaled). Only meaningful for WALL_CLOCK effects. */
    val rateSpeedMasterUuid: String? = null,
    /** D7's reading — see [uk.me.cormack.lighting7.fx.EffectDto.seconds]. */
    val seconds: Boolean = false,
    /** See [uk.me.cormack.lighting7.fx.EffectDto.size] and its three siblings. */
    val size: Double = 1.0,
    val direction: String = "FORWARD",
    val nShot: Int? = null,
    val spent: Boolean = false,
    /** See [uk.me.cormack.lighting7.fx.EffectDto.spread] and its three siblings. */
    val spread: Double = 1.0,
    val wings: Int = 1,
    val blocks: Int = 1,
    val groups: Int = 0,
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
/** Every group's colour in [projectId]'s rig, by name, as the live group list carries it. */
private fun groupColoursByName(state: State, projectId: Int): Map<String, String> = transaction(state.database) {
    val rig = RigScope.of(projectId)
    rig.groups().mapNotNull { g -> rig.colourOf(g)?.let { g.name to it } }.toMap()
}

/** The group's summary — what the group list answers and what a busk rig tile embeds. */
internal fun FixtureGroup<*>.toGroupSummaryDto(colour: String? = null): GroupSummaryDto {
    return GroupSummaryDto(
        colour = colour,
        name = name,
        memberCount = memberCount,  // Uses allMembers.size (includes subgroups)
        capabilities = detectCapabilities(),
        symmetricMode = metadata.symmetricMode.name,
        defaultDistribution = metadata.defaultDistributionName,
        memberKeys = allMembers.map { it.key },
    )
}

private fun FixtureGroup<*>.toDetailedDto(): GroupDetailDto {
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
        distributionSteps = DistributionStrategy.stepsBySize(memberCount),
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
    state.show.fxRegistry.fxWriteProblem(request.effectType, request.parameters)
        ?.let { throw IllegalArgumentException(it) }
    val effect = state.show.fxRegistry.createEffectWithTemplates(
        state.show.templateRegistry,
        request.effectType,
        request.parameters,
    )
    val registration = state.show.fxRegistry.getRegistration(request.effectType)
    val timing = FxTiming(request.beatDivision ?: registration?.defaultBeatDivision ?: BeatDivision.ONE_BAR)
    val blendMode = EffectSpecCoercion.Strict.blendMode(request.blendMode)
    val distribution = EffectSpecCoercion.Strict.distribution(request.distribution)
    val elementMode = EffectSpecCoercion.Strict.elementMode(request.elementMode)

    // No effect may drive a member's one-shot trigger (stage-view plan session 9, D15).
    TriggerIndex.refusalLive(state.show.fixtures, "group", group.name, request.propertyName, "effect")
        ?.let { throw TriggerNotStorableException(it) }
    // Nor a member's fixture command (fixture optics plan session 7).
    CommandIndex.refusalLive(state.show.fixtures, "group", group.name, request.propertyName, "effect")
        ?.let { throw CommandNotStorableException(it) }

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
    secondsTimingConflict(request.seconds, request.speedMasterUuid, request.rateSpeedMasterUuid)
        ?.let { throw IllegalArgumentException(it) }
    val instance = FxInstance(effect, target, timing, blendMode).apply {
        registrationId = registration?.id
        phaseOffset = request.phaseOffset
        distributionStrategy = distribution
        this.elementMode = elementMode
        this.elementFilter = elFilter
        applyRequestedDynamics(
            request.size, request.direction, request.nShot,
            SpreadShape(request.spread, request.wings, request.blocks, request.groups),
        )
        request.stepTiming?.let { this.stepTiming = it }
        speedMasterUuid = requireSpeedMasterUuid(request.speedMasterUuid)
        rateSpeedMasterUuid = requireSpeedMasterUuid(request.rateSpeedMasterUuid)
        // The type's loop, and D7's choice — this route never set the loop before S15, so a
        // wall-clock effect on a group ran on the beat loop.
        applyTimingChoice(registration?.timingSource ?: TimingSource.BEAT, request.seconds)
    }

    markProgrammerOwned(instance, request.programmerOwned)

    return engine.addEffect(instance)
}

