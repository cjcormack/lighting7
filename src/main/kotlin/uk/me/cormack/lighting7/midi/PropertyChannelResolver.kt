package uk.me.cormack.lighting7.midi

import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fixture.PropertyCategory
import uk.me.cormack.lighting7.fixture.dmx.DmxColour
import uk.me.cormack.lighting7.fixture.dmx.DmxFixtureSetting
import uk.me.cormack.lighting7.fixture.dmx.DmxSlider

/**
 * Resolves a named property on a [Fixture] to the concrete DMX channels that back it.
 *
 * Used by [SurfaceInputRouter] to translate `(fixtureKey, propertyName, 7-bit value)` into
 * direct channel writes. This mirrors the reflective property walk in
 * [uk.me.cormack.lighting7.fixture.DmxFixture.channelDescriptions] / `generatePropertyDescriptors`
 * but emits a simpler per-write record.
 *
 * v1 scope (matches docs/control-surface-plan.md Phase 3):
 *   - [DmxSlider] → one channel write, value scaled 0..127 → property's `min..max` range.
 *   - [DmxColour] → three channel writes (red / green / blue), same value applied to each,
 *     scaled 0..127 → 0..255. Intended as a "brightness on a colour" fallback; for per-axis
 *     control bind to the `redSlider` / `greenSlider` / `blueSlider` sub-properties instead.
 *   - [DmxFixtureSetting] → no writes, returns empty list. Faders on enum / setting properties
 *     are disallowed per Open Question 7 in the plan.
 *   - Any other property type → empty list, logged at debug.
 *
 * The resolver never touches [uk.me.cormack.lighting7.dmx.ControllerTransaction], so it's
 * safe to call from arbitrary threads (e.g. the MIDI input coroutine) without a show-wide
 * transaction. Writes go through the DMX controller directly, matching the `updateChannel`
 * WebSocket path.
 */
object PropertyChannelResolver {
    private val logger = LoggerFactory.getLogger(PropertyChannelResolver::class.java)

    /** One channel write produced by [resolveFixtureProperty]. */
    data class ChannelWrite(
        val universe: Universe,
        val channel: Int,
        /** The DMX value to write, already scaled to this channel's native 0..255 range. */
        val value: UByte,
        /** The property category this channel belongs to — used by the global scaler. */
        val category: PropertyCategory,
    )

    /**
     * Inputs a 7-bit MIDI value (`0u..127u`) and returns the channel writes that represent
     * the property at that position. Returns an empty list if the property is unknown or
     * unsupported (enum settings, unsupported property types).
     *
     * 7-bit → DMX scaling uses `(v * 255) / 127` for endpoints exactly at 0 and 255.
     * Sub-range properties (sliders with custom `min`/`max`) get a proportional remap within
     * their declared range.
     */
    fun resolveFixtureProperty(
        fixture: Fixture,
        propertyName: String,
        midiValue7Bit: UByte,
    ): List<ChannelWrite> {
        val property = fixture.fixtureProperty(propertyName)
        if (property == null) {
            logger.debug("Property '{}' not found on fixture '{}'", propertyName, fixture.key)
            return emptyList()
        }
        val raw = try {
            property.classProperty.call(fixture)
        } catch (e: Exception) {
            logger.warn("Failed to read property '{}' on '{}': {}", propertyName, fixture.key, e.message)
            return emptyList()
        } ?: return emptyList()

        return when (raw) {
            is DmxSlider -> {
                val dmxValue = scaleWithinRange(midiValue7Bit, raw.min, raw.max)
                listOf(
                    ChannelWrite(
                        universe = raw.universe,
                        channel = raw.channelNo,
                        value = dmxValue,
                        category = property.category,
                    )
                )
            }
            is DmxColour -> {
                val dmxValue = scale7BitToDmx(midiValue7Bit)
                listOf(
                    ChannelWrite(raw.universe, raw.redSlider.channelNo, dmxValue, PropertyCategory.COLOUR),
                    ChannelWrite(raw.universe, raw.greenSlider.channelNo, dmxValue, PropertyCategory.COLOUR),
                    ChannelWrite(raw.universe, raw.blueSlider.channelNo, dmxValue, PropertyCategory.COLOUR),
                )
            }
            is DmxFixtureSetting<*> -> {
                logger.debug(
                    "Refusing continuous write to setting property '{}' on '{}' — bind a button instead",
                    propertyName, fixture.key,
                )
                emptyList()
            }
            else -> {
                logger.debug(
                    "Unsupported property type {} for '{}' on '{}'",
                    raw::class.simpleName, propertyName, fixture.key,
                )
                emptyList()
            }
        }
    }

    /** Scale a 7-bit MIDI value to the full 8-bit DMX range. 0 → 0, 127 → 255. */
    fun scale7BitToDmx(midi: UByte): UByte {
        val m = midi.toInt().coerceIn(0, 127)
        return ((m * 255 + 63) / 127).toUByte()
    }

    /**
     * Scale a 7-bit MIDI value to a clamped `[min..max]` DMX sub-range. The caller passes the
     * slider's native `min` and `max`; we interpolate linearly between them.
     */
    fun scaleWithinRange(midi: UByte, min: UByte, max: UByte): UByte {
        if (min == max) return min
        val m = midi.toInt().coerceIn(0, 127)
        val span = max.toInt() - min.toInt()
        val dmx = min.toInt() + (m * span + 63) / 127
        return dmx.coerceIn(0, 255).toUByte()
    }
}
