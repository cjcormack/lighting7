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
import uk.me.cormack.lighting7.fixture.*
import uk.me.cormack.lighting7.fixture.group.FixtureGroup
import uk.me.cormack.lighting7.fixture.group.generateGroupPropertyDescriptors
import uk.me.cormack.lighting7.fx.*
import uk.me.cormack.lighting7.fx.effects.*
import uk.me.cormack.lighting7.fx.group.DistributionStrategy
import uk.me.cormack.lighting7.fx.group.applyColourFx
import uk.me.cormack.lighting7.fx.group.applyDimmerFx
import uk.me.cormack.lighting7.fx.group.applyPositionFx
import uk.me.cormack.lighting7.fx.group.applyUvFx
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

                val effects = state.show.fxEngine.getEffectsForGroup(resource.name)
                val dtos = effects.map { instance ->
                    GroupEffectDto(
                        id = instance.id,
                        effectType = instance.effect.name,
                        propertyName = instance.target.propertyName,
                        beatDivision = instance.timing.beatDivision,
                        blendMode = instance.blendMode.name,
                        distribution = instance.distributionStrategy.javaClass.simpleName,
                        isRunning = instance.isRunning,
                        phaseOffset = instance.phaseOffset,
                        currentPhase = instance.lastPhase
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
    val parameters: Map<String, String> = emptyMap()
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
    val isRunning: Boolean,
    val phaseOffset: Double,
    val currentPhase: Double
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
        memberCount = size,
        capabilities = detectCapabilities(),
        symmetricMode = metadata.symmetricMode.name,
        defaultDistribution = metadata.defaultDistributionName
    )
}

private fun FixtureGroup<*>.toDetailedDto(): GroupDetailDto {
    return GroupDetailDto(
        name = name,
        memberCount = size,
        capabilities = detectCapabilities(),
        symmetricMode = metadata.symmetricMode.name,
        defaultDistribution = metadata.defaultDistributionName,
        members = map { member ->
            GroupMemberDto(
                fixtureKey = member.fixture.key,
                fixtureName = member.fixture.fixtureName,
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

private fun FixtureGroup<*>.detectCapabilities(): List<String> {
    if (isEmpty()) return emptyList()

    val capabilities = mutableListOf<String>()
    val first = first().fixture

    if (first is FixtureWithDimmer && all { it.fixture is FixtureWithDimmer }) {
        capabilities.add("dimmer")
    }
    if (first is FixtureWithColour<*> && all { it.fixture is FixtureWithColour<*> }) {
        capabilities.add("colour")
    }
    if (first is FixtureWithPosition && all { it.fixture is FixtureWithPosition }) {
        capabilities.add("position")
    }
    if (first is FixtureWithUv && all { it.fixture is FixtureWithUv }) {
        capabilities.add("uv")
    }
    if (first is FixtureWithStrobe && all { it.fixture is FixtureWithStrobe }) {
        capabilities.add("strobe")
    }

    return capabilities
}

private fun applyGroupEffect(
    state: State,
    group: FixtureGroup<*>,
    request: AddGroupFxRequest
): Long {
    val effect = createEffectFromType(request.effectType, request.parameters)
    val timing = FxTiming(request.beatDivision)
    val blendMode = BlendMode.valueOf(request.blendMode)
    val distribution = DistributionStrategy.fromName(request.distribution)
    val engine = state.show.fxEngine

    // Create appropriate group target based on property type
    val target = when (request.propertyName.lowercase()) {
        "dimmer" -> {
            if (!group.fixtures.all { it is FixtureWithDimmer }) {
                throw IllegalStateException("Not all fixtures in group support dimmer")
            }
            SliderTarget.forGroup(group.name, "dimmer")
        }

        "colour", "color" -> {
            if (!group.fixtures.all { it is FixtureWithColour<*> }) {
                throw IllegalStateException("Not all fixtures in group support colour")
            }
            ColourTarget.forGroup(group.name)
        }

        "position" -> {
            if (!group.fixtures.all { it is FixtureWithPosition }) {
                throw IllegalStateException("Not all fixtures in group support position")
            }
            PositionTarget.forGroup(group.name)
        }

        "uv" -> {
            if (!group.fixtures.all { it is FixtureWithUv }) {
                throw IllegalStateException("Not all fixtures in group support UV")
            }
            SliderTarget.forGroup(group.name, "uvColour")
        }

        else -> throw IllegalArgumentException("Unknown property name: ${request.propertyName}")
    }

    // Create SINGLE FxInstance for the entire group
    val instance = FxInstance(effect, target, timing, blendMode).apply {
        phaseOffset = request.phaseOffset
        distributionStrategy = distribution
    }

    return engine.addEffect(instance)
}

private fun createEffectFromType(effectType: String, parameters: Map<String, String>): Effect {
    return when (effectType.lowercase()) {
        // Dimmer effects
        "sinewave", "sine" -> SineWave(
            min = parameters["min"]?.toUByteOrNull() ?: 0u,
            max = parameters["max"]?.toUByteOrNull() ?: 255u
        )
        "pulse" -> Pulse(
            min = parameters["min"]?.toUByteOrNull() ?: 0u,
            max = parameters["max"]?.toUByteOrNull() ?: 255u,
            attackRatio = parameters["attackRatio"]?.toDoubleOrNull() ?: 0.1,
            holdRatio = parameters["holdRatio"]?.toDoubleOrNull() ?: 0.3
        )
        "rampup" -> RampUp(
            min = parameters["min"]?.toUByteOrNull() ?: 0u,
            max = parameters["max"]?.toUByteOrNull() ?: 255u
        )
        "rampdown" -> RampDown(
            min = parameters["min"]?.toUByteOrNull() ?: 0u,
            max = parameters["max"]?.toUByteOrNull() ?: 255u
        )
        "triangle" -> Triangle(
            min = parameters["min"]?.toUByteOrNull() ?: 0u,
            max = parameters["max"]?.toUByteOrNull() ?: 255u
        )
        "squarewave", "square" -> SquareWave(
            min = parameters["min"]?.toUByteOrNull() ?: 0u,
            max = parameters["max"]?.toUByteOrNull() ?: 255u,
            dutyCycle = parameters["dutyCycle"]?.toDoubleOrNull() ?: 0.5
        )
        "strobe" -> Strobe(
            offValue = parameters["offValue"]?.toUByteOrNull() ?: 0u,
            onValue = parameters["onValue"]?.toUByteOrNull() ?: 255u,
            onRatio = parameters["onRatio"]?.toDoubleOrNull() ?: 0.1
        )
        "flicker" -> Flicker(
            min = parameters["min"]?.toUByteOrNull() ?: 100u,
            max = parameters["max"]?.toUByteOrNull() ?: 255u
        )
        "breathe" -> Breathe(
            min = parameters["min"]?.toUByteOrNull() ?: 0u,
            max = parameters["max"]?.toUByteOrNull() ?: 255u
        )

        // Colour effects
        "rainbowcycle", "rainbow" -> RainbowCycle(
            saturation = parameters["saturation"]?.toFloatOrNull() ?: 1.0f,
            brightness = parameters["brightness"]?.toFloatOrNull() ?: 1.0f
        )
        "colourstrobe", "colorstrobe" -> ColourStrobe(
            onColor = parameters["onColor"]?.toColorOrNull() ?: java.awt.Color.WHITE,
            offColor = parameters["offColor"]?.toColorOrNull() ?: java.awt.Color.BLACK,
            onRatio = parameters["onRatio"]?.toDoubleOrNull() ?: 0.1
        )
        "colourpulse", "colorpulse" -> ColourPulse(
            colorA = parameters["colorA"]?.toColorOrNull() ?: java.awt.Color.BLACK,
            colorB = parameters["colorB"]?.toColorOrNull() ?: java.awt.Color.WHITE
        )
        "colourfade", "colorfade" -> ColourFade(
            fromColor = parameters["fromColor"]?.toColorOrNull() ?: java.awt.Color.RED,
            toColor = parameters["toColor"]?.toColorOrNull() ?: java.awt.Color.BLUE,
            pingPong = parameters["pingPong"]?.toBooleanStrictOrNull() ?: true
        )
        "colourflicker", "colorflicker" -> ColourFlicker(
            baseColor = parameters["baseColor"]?.toColorOrNull() ?: java.awt.Color.ORANGE,
            variation = parameters["variation"]?.toIntOrNull() ?: 50
        )

        // Position effects
        "circle" -> Circle(
            panCenter = parameters["panCenter"]?.toUByteOrNull() ?: 128u,
            tiltCenter = parameters["tiltCenter"]?.toUByteOrNull() ?: 128u,
            panRadius = parameters["panRadius"]?.toUByteOrNull() ?: 64u,
            tiltRadius = parameters["tiltRadius"]?.toUByteOrNull() ?: 64u
        )
        "figure8" -> Figure8(
            panCenter = parameters["panCenter"]?.toUByteOrNull() ?: 128u,
            tiltCenter = parameters["tiltCenter"]?.toUByteOrNull() ?: 128u,
            panRadius = parameters["panRadius"]?.toUByteOrNull() ?: 64u,
            tiltRadius = parameters["tiltRadius"]?.toUByteOrNull() ?: 32u
        )
        "sweep" -> Sweep(
            startPan = parameters["startPan"]?.toUByteOrNull() ?: 64u,
            startTilt = parameters["startTilt"]?.toUByteOrNull() ?: 128u,
            endPan = parameters["endPan"]?.toUByteOrNull() ?: 192u,
            endTilt = parameters["endTilt"]?.toUByteOrNull() ?: 128u,
            pingPong = parameters["pingPong"]?.toBooleanStrictOrNull() ?: true
        )
        "pansweep" -> PanSweep(
            startPan = parameters["startPan"]?.toUByteOrNull() ?: 64u,
            endPan = parameters["endPan"]?.toUByteOrNull() ?: 192u,
            tilt = parameters["tilt"]?.toUByteOrNull() ?: 128u,
            pingPong = parameters["pingPong"]?.toBooleanStrictOrNull() ?: true
        )
        "tiltsweep" -> TiltSweep(
            startTilt = parameters["startTilt"]?.toUByteOrNull() ?: 64u,
            endTilt = parameters["endTilt"]?.toUByteOrNull() ?: 192u,
            pan = parameters["pan"]?.toUByteOrNull() ?: 128u,
            pingPong = parameters["pingPong"]?.toBooleanStrictOrNull() ?: true
        )
        "randomposition" -> RandomPosition(
            panCenter = parameters["panCenter"]?.toUByteOrNull() ?: 128u,
            tiltCenter = parameters["tiltCenter"]?.toUByteOrNull() ?: 128u,
            panRange = parameters["panRange"]?.toUByteOrNull() ?: 64u,
            tiltRange = parameters["tiltRange"]?.toUByteOrNull() ?: 64u
        )

        else -> throw IllegalArgumentException("Unknown effect type: $effectType")
    }
}

/**
 * Parse a color string to a Color object.
 * Supports hex colors (#RRGGBB or #RGB) and common named colors.
 */
private fun String.toColorOrNull(): java.awt.Color? {
    return try {
        when {
            startsWith("#") && length == 7 -> {
                java.awt.Color(
                    substring(1, 3).toInt(16),
                    substring(3, 5).toInt(16),
                    substring(5, 7).toInt(16)
                )
            }
            startsWith("#") && length == 4 -> {
                val r = substring(1, 2).toInt(16)
                val g = substring(2, 3).toInt(16)
                val b = substring(3, 4).toInt(16)
                java.awt.Color(r * 17, g * 17, b * 17)
            }
            else -> when (lowercase()) {
                "red" -> java.awt.Color.RED
                "green" -> java.awt.Color.GREEN
                "blue" -> java.awt.Color.BLUE
                "white" -> java.awt.Color.WHITE
                "black" -> java.awt.Color.BLACK
                "yellow" -> java.awt.Color.YELLOW
                "cyan" -> java.awt.Color.CYAN
                "magenta" -> java.awt.Color.MAGENTA
                "orange" -> java.awt.Color.ORANGE
                "pink" -> java.awt.Color.PINK
                else -> null
            }
        }
    } catch (e: Exception) {
        null
    }
}
