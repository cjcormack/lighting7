package uk.me.cormack.lighting7.testsupport

import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fixture.PropertyCategory
import uk.me.cormack.lighting7.fixture.dmx.DmxColour
import uk.me.cormack.lighting7.fixture.dmx.DmxSlider
import uk.me.cormack.lighting7.fixture.trait.WithPosition
import uk.me.cormack.lighting7.fx.CueAssignmentResolver
import uk.me.cormack.lighting7.fx.ProgrammerStore
import uk.me.cormack.lighting7.fx.PropertyChannelWriter
import uk.me.cormack.lighting7.state.State

/**
 * Channel-shaped view over the programmer for assertions.
 *
 * The old direct-write store was channel-keyed, so tests asserted `store.get(universe,
 * channel)`. The programmer is property-keyed with a channel sideband; this helper answers
 * the same question — "what value does the programmer contribute at this channel, if any" —
 * by resolving the channel's covering property entry, extracting this channel's component
 * from the typed value, and arbitrating against any sideband slot by write recency
 * ([ProgrammerStore.Slot.seq]), mirroring [uk.me.cormack.lighting7.fx.FxTarget]'s
 * `fallbackFromProgrammer` implementations.
 */
fun programmerChannel(state: State, universe: Int, channel: Int): UByte? {
    val store = state.show.programmerStore
    val sideSlot = store.getChannelSlot(universe, channel)
    val sideValue = (sideSlot?.value?.resolved as? CueAssignmentResolver.PropertyValue.Slider)?.value
    val sideSeq = sideSlot?.seq ?: Long.MIN_VALUE

    val property = resolvePropertyComponent(state, store, universe, channel)
    return when {
        property != null && sideValue != null ->
            if (property.first > sideSeq) property.second else sideValue
        property != null -> property.second
        else -> sideValue
    }
}

/** (seq, componentValue) of the covering property entry's contribution at this channel. */
private fun resolvePropertyComponent(
    state: State,
    store: ProgrammerStore,
    universe: Int,
    channel: Int,
): Pair<Long, UByte>? {
    val key = state.show.fxEngine.cascade.resolveChannelCoveringKey(universe, channel) ?: return null
    val fixture = try {
        state.show.fixtures.untypedFixture(key.targetKey)
    } catch (_: Exception) {
        return null
    }

    if (key.propertyName.equals("position", ignoreCase = true)) {
        val slot = store.get(key.targetKey, key.propertyName) ?: return null
        val value = slot.value.resolved as? CueAssignmentResolver.PropertyValue.Position ?: return null
        val pos = fixture as? WithPosition ?: return null
        return when (channel) {
            (pos.pan as? DmxSlider)?.channelNo -> slot.seq to value.pan
            (pos.tilt as? DmxSlider)?.channelNo -> slot.seq to value.tilt
            else -> null
        }
    }

    val slot = store.get(key.targetKey, key.propertyName)
    when (val value = slot?.value?.resolved) {
        is CueAssignmentResolver.PropertyValue.Slider -> return slot.seq to value.value
        is CueAssignmentResolver.PropertyValue.Setting -> return slot.seq to value.channelValue
        is CueAssignmentResolver.PropertyValue.Colour -> {
            val dmxColour = PropertyChannelWriter
                .resolveProperty(fixture, key.propertyName)?.value as? DmxColour ?: return null
            return when (channel) {
                dmxColour.redSlider.channelNo -> slot.seq to value.value.color.red.toUByte()
                dmxColour.greenSlider.channelNo -> slot.seq to value.value.color.green.toUByte()
                dmxColour.blueSlider.channelNo -> slot.seq to value.value.color.blue.toUByte()
                else -> null
            }
        }
        else -> {
            // The channel may be a bundled W/A/UV slider supplied by a colour entry.
            return bundledComponentFromColourEntry(store, fixture, channel)
        }
    }
}

private fun bundledComponentFromColourEntry(
    store: ProgrammerStore,
    fixture: Fixture,
    channel: Int,
): Pair<Long, UByte>? {
    val colourProp = fixture.fixtureProperties.find { it.category == PropertyCategory.COLOUR }
        ?: return null
    val slot = store.get(fixture.key, colourProp.name) ?: return null
    val colour = (slot.value.resolved as? CueAssignmentResolver.PropertyValue.Colour)?.value ?: return null
    for (prop in fixture.fixtureProperties) {
        if (!prop.bundleWithColour) continue
        val dmx = prop.classProperty.call(fixture) as? DmxSlider ?: continue
        if (dmx.channelNo != channel) continue
        return when (prop.category) {
            PropertyCategory.WHITE -> slot.seq to colour.white
            PropertyCategory.AMBER -> slot.seq to colour.amber
            PropertyCategory.UV -> slot.seq to colour.uv
            else -> null
        }
    }
    return null
}

/** The winning programmer value for (fixtureKey, propertyName), unwrapped. */
fun programmerValue(state: State, fixtureKey: String, propertyName: String): CueAssignmentResolver.PropertyValue? =
    state.show.programmerStore.get(fixtureKey, propertyName)?.value?.resolved
