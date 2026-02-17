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
import uk.me.cormack.lighting7.fixture.DmxFixture
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fixture.dmx.DmxFixtureSetting
import uk.me.cormack.lighting7.fixture.group.*
import uk.me.cormack.lighting7.fixture.property.Slider
import uk.me.cormack.lighting7.fixture.trait.*
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
            val groups = state.show.fixtures.groups.map { it.toDto() }
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
                        effectType = instance.effect.name,
                        propertyName = instance.target.propertyName,
                        beatDivision = instance.timing.beatDivision,
                        blendMode = instance.blendMode.name,
                        distribution = instance.distributionStrategy.javaClass.simpleName,
                        elementMode = if (expanded) instance.elementMode.name else null,
                        isRunning = instance.isRunning,
                        phaseOffset = instance.phaseOffset,
                        currentPhase = instance.lastPhase,
                        parameters = instance.effect.parameters,
                        presetId = instance.presetId,
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
    val defaultDistribution: String
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
    val members: List<GroupMemberDto>
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
    val elementMode: String = "PER_FIXTURE"  // PER_FIXTURE or FLAT
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
    val isRunning: Boolean,
    val phaseOffset: Double,
    val currentPhase: Double,
    val parameters: Map<String, String>,
    val presetId: Int? = null,
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
private fun FixtureGroup<*>.toDto(): GroupSummaryDto {
    return GroupSummaryDto(
        name = name,
        memberCount = memberCount,  // Uses allMembers.size (includes subgroups)
        capabilities = detectCapabilities(),
        symmetricMode = metadata.symmetricMode.name,
        defaultDistribution = metadata.defaultDistributionName
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
        }
    )
}

internal fun FixtureGroup<*>.detectCapabilities(): List<String> {
    val allFixtures = fixtures  // Uses allMembers (includes subgroups)
    if (allFixtures.isEmpty()) return emptyList()

    val capabilities = mutableListOf<String>()

    if (allFixtures.all { it is WithDimmer }) {
        capabilities.add("dimmer")
    }
    if (allFixtures.all { it is WithColour }) {
        capabilities.add("colour")
    }
    if (allFixtures.all { it is WithPosition }) {
        capabilities.add("position")
    }
    if (allFixtures.all { it is WithUv }) {
        capabilities.add("uv")
    }
    if (allFixtures.all { it is WithStrobe }) {
        capabilities.add("strobe")
    }

    // Also detect capabilities available via element group properties on multi-head DmxFixtures
    val dmxFixtures = allFixtures.filterIsInstance<DmxFixture>()
    if (dmxFixtures.size == allFixtures.size && dmxFixtures.isNotEmpty()) {
        val allEgp = dmxFixtures.map { it.generateElementGroupPropertyDescriptors() }
        if (allEgp.all { it != null }) {
            val egpList = allEgp.filterNotNull()
            if ("dimmer" !in capabilities && egpList.all { egp -> egp.any { it is GroupSliderPropertyDescriptor && it.category == "dimmer" } }) {
                capabilities.add("dimmer")
            }
            if ("colour" !in capabilities && egpList.all { egp -> egp.any { it is GroupColourPropertyDescriptor } }) {
                capabilities.add("colour")
            }
            if ("position" !in capabilities && egpList.all { egp -> egp.any { it is GroupPositionPropertyDescriptor } }) {
                capabilities.add("position")
            }
        }
    }

    return capabilities
}

/**
 * Check whether a group supports a given property, either directly on the
 * fixtures or via multi-element fixture elements.
 */
private fun groupSupportsProperty(group: FixtureGroup<*>, propertyName: String): Boolean {
    val allFixtures = group.fixtures
    if (allFixtures.isEmpty()) return false

    val normalised = propertyName.lowercase()

    // Check direct support first
    val directSupport = when (normalised) {
        "dimmer" -> allFixtures.all { it is WithDimmer }
        "colour", "color", "rgbcolour" -> allFixtures.all { it is WithColour }
        "position" -> allFixtures.all { it is WithPosition }
        "uv" -> allFixtures.all { it is WithUv }
        else -> {
            // Check for slider or setting property by name
            allFixtures.all { fixture ->
                fixture is Fixture && fixture.fixtureProperties.any { prop ->
                    prop.name == propertyName && run {
                        val value = prop.classProperty.call(fixture)
                        value is Slider || value is DmxFixtureSetting<*>
                    }
                }
            }
        }
    }
    if (directSupport) return true

    // Check multi-element fixtures — all must be MultiElementFixture with
    // elements that have the property
    val multiElementFixtures = allFixtures.filterIsInstance<MultiElementFixture<*>>()
    if (multiElementFixtures.size != allFixtures.size) return false
    if (multiElementFixtures.isEmpty()) return false

    return multiElementFixtures.all { mef ->
        val firstElement = mef.elements.firstOrNull() ?: return@all false
        when (normalised) {
            "dimmer" -> firstElement is WithDimmer
            "colour", "color", "rgbcolour" -> firstElement is WithColour
            "position" -> firstElement is WithPosition
            "uv" -> firstElement is WithUv
            else -> false
        }
    }
}

private fun applyGroupEffect(
    state: State,
    group: FixtureGroup<*>,
    request: AddGroupFxRequest
): Long {
    val effect = createEffectFromTypeAndParams(request.effectType, request.parameters)
    val timing = FxTiming(request.beatDivision)
    val blendMode = BlendMode.valueOf(request.blendMode)
    val distribution = DistributionStrategy.fromName(request.distribution)
    val elementMode = ElementMode.valueOf(request.elementMode)
    val engine = state.show.fxEngine

    // Validate property support (direct or via elements)
    if (!groupSupportsProperty(group, request.propertyName)) {
        throw IllegalStateException(
            "Not all fixtures in group support ${request.propertyName} " +
                "(directly or via elements)"
        )
    }

    // Create appropriate group target based on property type
    val target = when (request.propertyName.lowercase()) {
        "dimmer" -> SliderTarget.forGroup(group.name, "dimmer")
        "colour", "color", "rgbcolour" -> ColourTarget.forGroup(group.name)
        "position" -> PositionTarget.forGroup(group.name)
        "uv" -> SliderTarget.forGroup(group.name, "uv")
        else -> {
            // Check if the property is a slider or a setting on the first fixture
            val firstFixture = group.fixtures.firstOrNull() as? Fixture
            val prop = firstFixture?.fixtureProperties?.find { it.name == request.propertyName }
            val propValue = prop?.classProperty?.call(firstFixture)
            if (propValue is Slider) {
                SliderTarget.forGroup(group.name, request.propertyName)
            } else {
                SettingTarget.forGroup(group.name, request.propertyName)
            }
        }
    }

    // Create SINGLE FxInstance for the entire group
    val instance = FxInstance(effect, target, timing, blendMode).apply {
        phaseOffset = request.phaseOffset
        distributionStrategy = distribution
        this.elementMode = elementMode
    }

    return engine.addEffect(instance)
}

