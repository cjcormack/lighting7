package uk.me.cormack.lighting7.plugins

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.dmx.DmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.routes.readOutputColour
import uk.me.cormack.lighting7.fixture.GroupableFixture
import uk.me.cormack.lighting7.fixture.dmx.DmxColour
import uk.me.cormack.lighting7.fixture.dmx.DmxFixtureSetting
import uk.me.cormack.lighting7.fixture.dmx.DmxSlider
import uk.me.cormack.lighting7.fixture.property.Slider
import uk.me.cormack.lighting7.fixture.trait.WithAmber
import uk.me.cormack.lighting7.fixture.trait.WithUv
import uk.me.cormack.lighting7.fixture.trait.WithWhite
import uk.me.cormack.lighting7.fx.ExtendedColour
import uk.me.cormack.lighting7.fx.CueAssignmentResolver
import uk.me.cormack.lighting7.fx.ProgrammerOwner
import uk.me.cormack.lighting7.fx.PropertyChannelWriter
import uk.me.cormack.lighting7.show.Fixtures
import uk.me.cormack.lighting7.state.State
import java.awt.Color
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicReference

// ─── Inbound ────────────────────────────────────────────────────────────

@Serializable
sealed class ChannelInMessage : InMessage()

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

/** One `(target, property)` key that drives a DMX address — see [ChannelMappingEntry.properties]. */
@Serializable
data class ChannelPropertyKeyDto(
    val targetKey: String,
    val propertyName: String,
)

@Serializable
data class ChannelMappingEntry(
    val fixtureKey: String,
    val fixtureName: String,
    val description: String,
    /**
     * Every property key whose channels include this address, from
     * [PropertyChannelWriter.propertyKeysByChannel] — usually one, two where a bundled emitter
     * or a pan/tilt axis is also part of `rgbColour` / `position`, and element keys on a
     * multi-head fixture. The DMX sheet reads ownership through these, so it asks the desk's
     * own property→channel lookup instead of re-deriving it from descriptors.
     *
     * `@EncodeDefault(ALWAYS)` because the WS `Json` drops defaults: an address no property
     * covers must arrive as `[]`, not as the absent field an older desk sends.
     */
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val properties: List<ChannelPropertyKeyDto> = emptyList(),
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
        is ChannelStateInMessage -> scope.send(buildChannelStateMessage(state))
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
 *   sibling components into the programmer.
 * - A channel on a **head** of a multi-head fixture lifts the same way, to that head's own
 *   property under its element key — the covering lookup
 *   ([uk.me.cormack.lighting7.fx.CascadePublisher.resolveChannelCoveringKey]) reaches elements.
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

    val key = engine.cascade.resolveChannelCoveringKey(universe, channel)
    if (key == null) {
        // No backing property — raw sideband write; nothing sits below it in the cascade.
        engine.programmer.writeChannel(
            ProgrammerOwner.WEB, universe, channel, level, coveringKey = null, fadeMs = fade,
        )
        return
    }
    if (key.propertyName.equals("position", ignoreCase = true)) {
        // Pan/tilt axes stay channel-shaped: lifting one axis to a position entry would
        // freeze the other axis into the programmer too.
        engine.programmer.writeChannel(
            ProgrammerOwner.WEB, universe, channel, level, coveringKey = key, fadeMs = fade,
        )
        return
    }

    // `untypedGroupableFixture`: the covering key is an element key when the channel is on a
    // head of a multi-head fixture, and a head lifts exactly as a whole fixture does.
    val fixture = try {
        show.fixtures.untypedGroupableFixture(key.targetKey)
    } catch (_: Exception) {
        engine.programmer.writeChannel(
            ProgrammerOwner.WEB, universe, channel, level, coveringKey = null, fadeMs = fade,
        )
        return
    }

    when (val raw = PropertyChannelWriter.resolveProperty(fixture, key.propertyName)?.value) {
        is DmxColour -> {
            val current = currentExtendedColour(state, fixture, key.propertyName, raw, universe)
            val replaced = replaceColourComponent(fixture, raw, current, channel, level)
            if (replaced == null) {
                // A channel of the colour that is none of its components — nothing to lift into.
                engine.programmer.writeChannel(
                    ProgrammerOwner.WEB, universe, channel, level, coveringKey = key, fadeMs = fade,
                )
                return
            }
            engine.programmer.writeProperty(
                ProgrammerOwner.WEB, fixture, key.propertyName,
                CueAssignmentResolver.PropertyValue.Colour(replaced), fadeMs = fade,
            )
        }
        is DmxFixtureSetting<*> -> engine.programmer.writeProperty(
            ProgrammerOwner.WEB, fixture, key.propertyName,
            CueAssignmentResolver.PropertyValue.Setting(level), fadeMs = fade,
        )
        is DmxSlider -> engine.programmer.writeProperty(
            ProgrammerOwner.WEB, fixture, key.propertyName,
            CueAssignmentResolver.PropertyValue.Slider(level), fadeMs = fade,
        )
        else -> engine.programmer.writeChannel(
            ProgrammerOwner.WEB, universe, channel, level, coveringKey = key, fadeMs = fade,
        )
    }
}

/**
 * [current] with the component [channel] carries set to [level], or null when [channel] is not
 * one of [dmxColour]'s components.
 *
 * Matched by channel for every component, bundled emitters included: the covering lookup names
 * `rgbColour` for a bundled white / amber / UV only when the fixture declares no slider of its
 * own for it (a declared one wins), and falling through to "must be blue" would write the level
 * onto the wrong emitter.
 */
private fun replaceColourComponent(
    fixture: GroupableFixture,
    dmxColour: DmxColour,
    current: ExtendedColour,
    channel: Int,
    level: UByte,
): ExtendedColour? {
    val c = current.color
    fun bundled(slider: Slider?): Boolean = (slider as? DmxSlider)?.channelNo == channel
    return when {
        channel == dmxColour.redSlider.channelNo ->
            current.copy(color = Color(level.toInt(), c.green, c.blue))
        channel == dmxColour.greenSlider.channelNo ->
            current.copy(color = Color(c.red, level.toInt(), c.blue))
        channel == dmxColour.blueSlider.channelNo ->
            current.copy(color = Color(c.red, c.green, level.toInt()))
        bundled((fixture as? WithWhite)?.white) -> current.copy(white = level)
        bundled((fixture as? WithAmber)?.amber) -> current.copy(amber = level)
        bundled((fixture as? WithUv)?.uv) -> current.copy(uv = level)
        else -> null
    }
}

/**
 * The fixture's current output colour (RGB + bundled W/A/UV) read from the controller
 * buffer — the base a single-component `updateChannel` write replaces into. [fixture] is a
 * whole fixture or one head of a multi-head one.
 */
private fun currentExtendedColour(
    state: State,
    fixture: GroupableFixture,
    propertyName: String,
    dmxColour: DmxColour,
    universe: Int,
): ExtendedColour {
    // Prefer an existing programmer colour entry so successive component drags compose,
    // then fall through to the shared wire+sideband read. That read is shared with Record's
    // sideband lifting ([readOutputColour]) so the two can't disagree about which channels
    // count as part of the colour; the entry preference is this path's own rule, because
    // Record has already applied its own precedence before it gets there.
    (state.show.programmerStore.get(fixture.targetKey, propertyName)?.value?.resolved
        as? CueAssignmentResolver.PropertyValue.Colour)
        ?.let { return it.value }

    return readOutputColour(state, fixture, dmxColour, universe)
}

// ─── Helpers ────────────────────────────────────────────────────────────

/**
 * The whole DMX output buffer, parked values overlaid so clients see what the fixture is
 * actually emitting rather than the underlying buffered value.
 */
internal fun buildChannelStateMessage(state: State): ChannelStateOutMessage {
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
    return ChannelStateOutMessage(currentValues)
}

internal fun buildUniverseList(state: State): List<Int> =
    state.show.fixtures.controllers.map(DmxController::universe).map(Universe::universe).sortedBy { it }

internal fun buildChannelMappingMessage(state: State): ChannelMappingStateOutMessage =
    buildChannelMappingMessage(state.show.fixtures)

/**
 * The last frame built, and the register and [Fixtures.structureVersion] it was built from. The
 * register is held weakly so a project switch does not keep the old show's fixtures alive.
 */
private class CachedChannelMappings(
    val fixtures: WeakReference<Fixtures>,
    val version: Long,
    val message: ChannelMappingStateOutMessage,
)

private val channelMappingCache = AtomicReference<CachedChannelMappings?>(null)

/**
 * The channel-mapping frame, **built once per register version** and shared.
 *
 * Every connected socket asks for this on connect and again on each `fixturesChanged`. The
 * per-address keys come from [PropertyChannelWriter.channelKeyIndex], itself cached per version and
 * shared with the desk's covering lookup, so the reflective walk behind them runs once per patch
 * edit; this cache saves re-assembling the frame per socket. The frame depends only on the
 * register, so it is cached against
 * [Fixtures.structureVersion] and the [Fixtures] instance (a project switch builds a new one).
 * A version bumped with nothing structural changed (`patchListChanged`) just rebuilds once.
 *
 * The fixtures and the mappings come from one [Fixtures.channelMappingSnapshot], so an address's
 * `properties` and its fixture name cannot come from two different registers.
 */
internal fun buildChannelMappingMessage(fixtures: Fixtures): ChannelMappingStateOutMessage {
    channelMappingCache.get()?.let { cached ->
        if (cached.fixtures.get() === fixtures && cached.version == fixtures.structureVersion) return cached.message
    }
    val snapshot = fixtures.channelMappingSnapshot()
    // The index for *this* snapshot's version, so every address's keys come from the same register
    // as its mapping — and the walk behind it is shared with the desk's covering lookup.
    val keysByChannel = PropertyChannelWriter.channelKeyIndex(fixtures, snapshot).propertyKeys
    val mappings = snapshot.mappings
        .mapValues { (universe, channels) ->
            channels.mapValues { (channel, mapping) ->
                ChannelMappingEntry(
                    fixtureKey = mapping.fixtureKey,
                    fixtureName = mapping.fixtureName,
                    description = mapping.description,
                    properties = keysByChannel[universe to channel].orEmpty()
                        .map { ChannelPropertyKeyDto(it.targetKey, it.propertyName) },
                )
            }
        }
    val message = ChannelMappingStateOutMessage(mappings)
    channelMappingCache.set(CachedChannelMappings(WeakReference(fixtures), snapshot.version, message))
    return message
}
