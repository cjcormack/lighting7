package uk.me.cormack.lighting7.plugins

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.dmx.DmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.routes.readOutputColour
import uk.me.cormack.lighting7.fixture.dmx.DmxColour
import uk.me.cormack.lighting7.fixture.dmx.DmxFixtureSetting
import uk.me.cormack.lighting7.fixture.dmx.DmxSlider
import uk.me.cormack.lighting7.fx.ExtendedColour
import uk.me.cormack.lighting7.fx.Layer3Resolver
import uk.me.cormack.lighting7.fx.ProgrammerOwner
import uk.me.cormack.lighting7.fx.PropertyChannelWriter
import uk.me.cormack.lighting7.state.State
import java.awt.Color

// ─── Inbound ────────────────────────────────────────────────────────────

@Serializable
sealed class ChannelInMessage : InMessage()

@Serializable
@SerialName("ping")
data object PingInMessage : ChannelInMessage()

@Serializable
@SerialName("channelState")
data object ChannelStateInMessage : ChannelInMessage()

@Serializable
@SerialName("updateChannel")
data class UpdateChannelInMessage(
    val universe: Int,
    val id: Int,
    val level: UByte,
    val fadeTime: Long,
) : ChannelInMessage()

@Serializable
@SerialName("universesState")
data object UniversesStateInMessage : ChannelInMessage()

@Serializable
@SerialName("channelMappingState")
data object ChannelMappingStateInMessage : ChannelInMessage()

// ─── Outbound ───────────────────────────────────────────────────────────

@Serializable
sealed class ChannelOutMessage : OutMessage()

@Serializable
data class ChannelState(
    val universe: Int,
    val id: Int,
    val currentLevel: UByte,
)

@Serializable
@SerialName("channelState")
data class ChannelStateOutMessage(
    val channels: List<ChannelState>,
) : ChannelOutMessage()

@Serializable
@SerialName("universesState")
data class UniversesStateOutMessage(
    val universes: List<Int>,
) : ChannelOutMessage()

@Serializable
data class ChannelMappingEntry(
    val fixtureKey: String,
    val fixtureName: String,
    val description: String,
)

@Serializable
@SerialName("channelMappingState")
data class ChannelMappingStateOutMessage(
    val mappings: Map<Int, Map<Int, ChannelMappingEntry>>,
) : ChannelOutMessage()

// ─── Handler ────────────────────────────────────────────────────────────

suspend fun handleChannel(scope: SocketScope, message: ChannelInMessage) {
    val state = scope.state
    when (message) {
        is PingInMessage -> Unit
        is ChannelStateInMessage -> {
            // Overlay parked values onto currentValues so clients see what the fixture is
            // actually emitting, not the underlying buffered value.
            val parkManager = state.show.parkManager
            val currentValues = state.show.fixtures.controllers.flatMap { controller ->
                val universe = controller.universe.universe
                controller.currentValues.map { (channelNo, value) ->
                    ChannelState(
                        universe,
                        channelNo,
                        parkManager.getParkedValue(universe, channelNo) ?: value,
                    )
                }
            }
            scope.send(ChannelStateOutMessage(currentValues))
        }
        is UpdateChannelInMessage -> handleUpdateChannel(state, message)
        is UniversesStateInMessage -> {
            scope.send(UniversesStateOutMessage(buildUniverseList(state)))
        }
        is ChannelMappingStateInMessage -> {
            scope.send(buildChannelMappingMessage(state))
        }
    }
}

/**
 * `updateChannel` compatibility shim — the raw channel write from the Channels debug view
 * and legacy fixture sliders, routed through the programmer instead of the old
 * write-the-wire-then-record bypass.
 *
 * - Slider- or setting-backed channels lift to a property-level programmer entry.
 * - Colour sub-channels lift to the whole `rgbColour` property: the written component
 *   replaces its axis in the fixture's current output colour, deliberately freezing the
 *   sibling components into the programmer (the property-level analogue of what
 *   `cueEdit.setChannel` guides users toward).
 * - Position axes and channels with no backing property stay channel-shaped in the
 *   programmer's sideband — still above the layers below, still released by Clear.
 */
internal fun handleUpdateChannel(state: State, message: UpdateChannelInMessage) {
    val show = state.show
    val engine = show.fxEngine
    val universe = message.universe
    val channel = message.id
    val level = message.level
    val fade = message.fadeTime

    val key = engine.resolveChannelCoveringKey(universe, channel)
    if (key == null) {
        // No backing property — raw sideband write; nothing sits below it in the cascade.
        engine.writeProgrammerChannel(
            ProgrammerOwner.WEB, universe, channel, level, coveringKey = null, fadeMs = fade,
        )
        return
    }
    if (key.propertyName.equals("position", ignoreCase = true)) {
        // Pan/tilt axes stay channel-shaped: lifting one axis to a position entry would
        // freeze the other axis into the programmer too.
        engine.writeProgrammerChannel(
            ProgrammerOwner.WEB, universe, channel, level, coveringKey = key, fadeMs = fade,
        )
        return
    }

    val fixture = try {
        show.fixtures.untypedFixture(key.targetKey)
    } catch (_: Exception) {
        engine.writeProgrammerChannel(
            ProgrammerOwner.WEB, universe, channel, level, coveringKey = null, fadeMs = fade,
        )
        return
    }

    when (val raw = PropertyChannelWriter.resolveProperty(fixture, key.propertyName)?.value) {
        is DmxColour -> {
            val current = currentExtendedColour(state, fixture.key, key.propertyName, raw, universe)
            val replaced = when (channel) {
                raw.redSlider.channelNo -> ExtendedColour(
                    Color(level.toInt(), current.color.green, current.color.blue),
                    current.white, current.amber, current.uv,
                )
                raw.greenSlider.channelNo -> ExtendedColour(
                    Color(current.color.red, level.toInt(), current.color.blue),
                    current.white, current.amber, current.uv,
                )
                else -> ExtendedColour(
                    Color(current.color.red, current.color.green, level.toInt()),
                    current.white, current.amber, current.uv,
                )
            }
            engine.writeProgrammerProperty(
                ProgrammerOwner.WEB, fixture, key.propertyName,
                Layer3Resolver.PropertyValue.Colour(replaced), fadeMs = fade,
            )
        }
        is DmxFixtureSetting<*> -> engine.writeProgrammerProperty(
            ProgrammerOwner.WEB, fixture, key.propertyName,
            Layer3Resolver.PropertyValue.Setting(level), fadeMs = fade,
        )
        is DmxSlider -> engine.writeProgrammerProperty(
            ProgrammerOwner.WEB, fixture, key.propertyName,
            Layer3Resolver.PropertyValue.Slider(level), fadeMs = fade,
        )
        else -> engine.writeProgrammerChannel(
            ProgrammerOwner.WEB, universe, channel, level, coveringKey = key, fadeMs = fade,
        )
    }
}

/**
 * The fixture's current output colour (RGB + bundled W/A/UV) read from the controller
 * buffer — the base a single-component `updateChannel` write replaces into.
 */
private fun currentExtendedColour(
    state: State,
    fixtureKey: String,
    propertyName: String,
    dmxColour: DmxColour,
    universe: Int,
): ExtendedColour {
    // Prefer an existing programmer colour entry so successive component drags compose,
    // then fall through to the shared wire+sideband read. That read is shared with Record's
    // sideband lifting ([readOutputColour]) so the two can't disagree about which channels
    // count as part of the colour; the entry preference is this path's own rule, because
    // Record has already applied its own precedence before it gets there.
    (state.show.programmerStore.get(fixtureKey, propertyName)?.value?.resolved
        as? Layer3Resolver.PropertyValue.Colour)
        ?.let { return it.value }

    val fixture = try {
        state.show.fixtures.untypedFixture(fixtureKey)
    } catch (_: Exception) {
        null
    }
    return readOutputColour(state, fixture, dmxColour, universe)
}

// ─── Helpers ────────────────────────────────────────────────────────────

internal fun buildUniverseList(state: State): List<Int> =
    state.show.fixtures.controllers.map(DmxController::universe).map(Universe::universe).sortedBy { it }

internal fun buildChannelMappingMessage(state: State): ChannelMappingStateOutMessage {
    val mappings = state.show.fixtures.getChannelMappings()
        .mapValues { (_, channels) ->
            channels.mapValues { (_, mapping) ->
                ChannelMappingEntry(
                    fixtureKey = mapping.fixtureKey,
                    fixtureName = mapping.fixtureName,
                    description = mapping.description,
                )
            }
        }
    return ChannelMappingStateOutMessage(mappings)
}
