package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fixture.GroupableFixture
import uk.me.cormack.lighting7.fixture.PropertyCategory
import uk.me.cormack.lighting7.fixture.dmx.DmxColour
import uk.me.cormack.lighting7.fixture.dmx.DmxFixtureColourSettingValue
import uk.me.cormack.lighting7.fixture.dmx.DmxFixtureGoboSettingValue
import uk.me.cormack.lighting7.fixture.dmx.DmxFixturePrismSettingValue
import uk.me.cormack.lighting7.fixture.dmx.DmxFixtureSetting
import uk.me.cormack.lighting7.fixture.dmx.DmxFixtureSettingValue
import uk.me.cormack.lighting7.fixture.dmx.DmxSlider
import uk.me.cormack.lighting7.fixture.group.FixtureElement
import uk.me.cormack.lighting7.fixture.group.MultiElementFixture
import uk.me.cormack.lighting7.fixture.property.Strobe
import uk.me.cormack.lighting7.fixture.trait.WithPosition
import java.awt.Color

/**
 * Computes the property values a console "Locate" asserts on a fixture: centre the beam and
 * force it open white so the physical unit can be identified in the rig.
 *
 * Per category: pan/tilt to the middle of their range (fine channels to 0 — the 16-bit
 * midpoint is coarse-mid + fine-0), dimmer full, shutter open, colour white, gobo open,
 * prism out, iris/frost open, zoom/focus mid. "Colour white" adapts to the engine: RGB
 * mixers get full RGB *and* full white (the fan-out in [PropertyChannelWriter.resolveColour]
 * also zeroes amber/uv); colour wheels pick their open/white slot — unless an RGB engine
 * coexists on the same target, in which case the wheel is *disengaged* (its off slot) so the
 * RGB white wins. Standalone WHITE/AMBER/UV sliders go to full only when there is no RGB
 * engine to fan out from — white/amber-only blinders and UV cannons must still light.
 * Speed, macro, rotation and bare SETTING channels are left alone.
 *
 * Values are expressed as [Layer3Resolver.PropertyValue]s for the FX engine's Layer-4 write
 * path, so locate participates in the normal layer cascade (park still wins, clearing falls
 * back to cue/baseline). Pan/tilt use the synthetic `"position"` property — the same name cue
 * assignments use — so a cue's position correctly reasserts itself when locate is released.
 *
 * Structure handling: a [MultiElementFixture]'s elements are resolved too (master
 * dimmer/strobe live on the parent while pan/tilt/colour live on the heads), and locating a
 * single [FixtureElement] also raises the parent's masters — a centred head behind a closed
 * master shutter would defeat the point.
 *
 * Assignments whose backing channels collide (some fixtures model one channel as both dimmer
 * and shutter) are deduplicated with the shutter-open value winning: on those fixtures the
 * strobe band's "full on" level is the one that means maximum steady output.
 */
object LocateValueResolver {
    /** One property write for a locate: which target to write on, and what to assert. */
    data class LocateAssignment(
        val target: GroupableFixture,
        val propertyName: String,
        val value: Layer3Resolver.PropertyValue,
    )

    /** Locate white: full RGB *and* full white — covers RGB-only and white-engine fixtures. */
    private val LOCATE_WHITE = ExtendedColour(Color.WHITE, white = 255u)

    /** Parent channels a single-element locate must still raise for the head to emit light. */
    private val PARENT_MASTER_CATEGORIES = setOf(PropertyCategory.DIMMER, PropertyCategory.STROBE)

    fun resolve(fixture: GroupableFixture): List<LocateAssignment> = buildList {
        addAll(resolveSingle(fixture))
        when (fixture) {
            is MultiElementFixture<*> -> for (element in fixture.elements) {
                addAll(resolveSingle(element))
            }
            is FixtureElement<*> -> addAll(
                resolveSingle(fixture.parentFixture, onlyCategories = PARENT_MASTER_CATEGORIES)
            )
            else -> {}
        }
    }

    private fun resolveSingle(
        target: GroupableFixture,
        onlyCategories: Set<PropertyCategory>? = null,
    ): List<LocateAssignment> {
        val properties = PropertyChannelWriter.resolvedProperties(target)
        val hasRgbEngine = properties.any {
            it.category == PropertyCategory.COLOUR && it.value is DmxColour
        }

        // Candidates carry their backing so channel collisions can be resolved below.
        val candidates = mutableListOf<Pair<PropertyChannelWriter.NamedProperty, Layer3Resolver.PropertyValue>>()

        if (onlyCategories == null && target is WithPosition) {
            val pan = (target.pan as? DmxSlider)?.let(::midValue) ?: 128u.toUByte()
            val tilt = (target.tilt as? DmxSlider)?.let(::midValue) ?: 128u.toUByte()
            candidates += PropertyChannelWriter.NamedProperty("position", PropertyCategory.OTHER, Unit) to
                Layer3Resolver.PropertyValue.Position(pan, tilt)
        }

        for (property in properties) {
            if (onlyCategories != null && property.category !in onlyCategories) continue
            // WithPosition pan/tilt are covered by the synthetic "position" write above.
            if (target is WithPosition &&
                (property.category == PropertyCategory.PAN || property.category == PropertyCategory.TILT)
            ) {
                continue
            }
            val value = locateValueFor(property.category, property.value, hasRgbEngine) ?: continue
            candidates += property to value
        }

        return dedupeByChannel(candidates).map { (property, value) ->
            LocateAssignment(target, property.name, value)
        }
    }

    private fun locateValueFor(
        category: PropertyCategory,
        backing: Any,
        hasRgbEngine: Boolean,
    ): Layer3Resolver.PropertyValue? =
        when (category) {
            PropertyCategory.DIMMER ->
                (backing as? DmxSlider)?.let { Layer3Resolver.PropertyValue.Slider(it.max) }

            // Only reached on fixtures without WithPosition (e.g. a lone axis channel).
            PropertyCategory.PAN, PropertyCategory.TILT ->
                (backing as? DmxSlider)?.let { Layer3Resolver.PropertyValue.Slider(midValue(it)) }

            PropertyCategory.PAN_FINE, PropertyCategory.TILT_FINE ->
                (backing as? DmxSlider)?.let { Layer3Resolver.PropertyValue.Slider(0u) }

            // Any Strobe channel knows its shutter-open level; coerce because the raw value
            // bypasses the slider's own clamp, and a fullOnValue above a fenced-off max must
            // not drive into lamp/reset bands the setter would have refused.
            PropertyCategory.STROBE -> if (backing is DmxSlider && backing is Strobe) {
                Layer3Resolver.PropertyValue.Slider(backing.fullOnValue.coerceIn(backing.min, backing.max))
            } else null

            PropertyCategory.COLOUR -> when (backing) {
                is DmxColour -> Layer3Resolver.PropertyValue.Colour(LOCATE_WHITE)
                is DmxFixtureSetting<*> -> {
                    // With an RGB engine on the same target the wheel must be *disengaged*,
                    // not set to a white preset — its macro slots override the RGB channels.
                    val slot = if (hasRgbEngine) disengagedSetting(backing) else whiteSetting(backing)
                    slot?.let { Layer3Resolver.PropertyValue.Setting(it.level) }
                }
                else -> null
            }

            PropertyCategory.GOBO -> when (backing) {
                is DmxFixtureSetting<*> ->
                    openGoboSetting(backing)?.let { Layer3Resolver.PropertyValue.Setting(it.level) }
                is DmxSlider -> Layer3Resolver.PropertyValue.Slider(backing.min)
                else -> null
            }

            PropertyCategory.PRISM -> when (backing) {
                is DmxFixtureSetting<*> ->
                    prismOutSetting(backing)?.let { Layer3Resolver.PropertyValue.Setting(it.level) }
                is DmxSlider -> Layer3Resolver.PropertyValue.Slider(backing.min)
                else -> null
            }

            PropertyCategory.IRIS, PropertyCategory.FROST ->
                (backing as? DmxSlider)?.let { Layer3Resolver.PropertyValue.Slider(it.min) }

            PropertyCategory.ZOOM, PropertyCategory.FOCUS ->
                (backing as? DmxSlider)?.let { Layer3Resolver.PropertyValue.Slider(midValue(it)) }

            // Standalone white/amber/UV engines with no RGB property to fan out from — the
            // whole light output of white/amber blinders and UV cannons. With an RGB engine
            // present these are written (or zeroed) by the Colour fan-out instead.
            PropertyCategory.WHITE, PropertyCategory.AMBER, PropertyCategory.UV ->
                if (!hasRgbEngine) {
                    (backing as? DmxSlider)?.let { Layer3Resolver.PropertyValue.Slider(it.max) }
                } else null

            else -> null
        }

    /**
     * Drop assignments whose single backing channel is already claimed, with STROBE
     * outranking everything: on fixtures that share one channel between dimmer and shutter,
     * the shutter-open level is the authoritative "steady full output" value.
     */
    private fun dedupeByChannel(
        candidates: List<Pair<PropertyChannelWriter.NamedProperty, Layer3Resolver.PropertyValue>>,
    ): List<Pair<PropertyChannelWriter.NamedProperty, Layer3Resolver.PropertyValue>> {
        fun channelOf(property: PropertyChannelWriter.NamedProperty): Pair<Universe, Int>? =
            when (val backing = property.value) {
                is DmxSlider -> backing.universe to backing.channelNo
                is DmxFixtureSetting<*> -> backing.universe to backing.channelNo
                else -> null
            }

        val strobeChannels = candidates
            .filter { (property, _) -> property.category == PropertyCategory.STROBE }
            .mapNotNullTo(HashSet()) { (property, _) -> channelOf(property) }

        val claimed = HashSet<Pair<Universe, Int>>()
        return candidates.filter { (property, _) ->
            val channel = channelOf(property) ?: return@filter true
            if (property.category != PropertyCategory.STROBE && channel in strobeChannels) {
                return@filter false
            }
            claimed.add(channel)
        }
    }

    // Round up so a full 0..255 range centres on the DMX convention of 128 (16-bit 0x8000
    // with fine at 0), not 127.
    private fun midValue(slider: DmxSlider): UByte =
        ((slider.min.toInt() + slider.max.toInt() + 1) / 2).toUByte()

    /** The wheel slot that renders white: annotated `#FFFFFF` preview, else an OPEN/WHITE name. */
    private fun whiteSetting(setting: DmxFixtureSetting<*>): DmxFixtureSettingValue? =
        setting.sortedValues.firstOrNull {
            (it as? DmxFixtureColourSettingValue)?.colourPreview?.equals("#FFFFFF", ignoreCase = true) == true
        } ?: setting.sortedValues.firstOrNull {
            it.name.contains("OPEN", ignoreCase = true) || it.name.contains("WHITE", ignoreCase = true)
        }

    /**
     * The slot that takes a colour-macro wheel out of the signal path so a coexisting RGB
     * engine controls the output: a previewless level-0 slot, or an OFF/NONE-named one.
     */
    private fun disengagedSetting(setting: DmxFixtureSetting<*>): DmxFixtureSettingValue? =
        setting.sortedValues.firstOrNull {
            it.level == 0u.toUByte() && (it as? DmxFixtureColourSettingValue)?.colourPreview == null
        } ?: setting.sortedValues.firstOrNull {
            it.name.contains("OFF", ignoreCase = true) || it.name.contains("NONE", ignoreCase = true)
        }

    /**
     * The wheel slot with no gobo in the beam. Annotated open slots win (an OPEN-named one
     * first — `gobo == null` also covers scroll/shake bands); unannotated wheels fall back to
     * an OPEN-named slot.
     */
    private fun openGoboSetting(setting: DmxFixtureSetting<*>): DmxFixtureSettingValue? =
        setting.sortedValues.firstOrNull {
            it is DmxFixtureGoboSettingValue && it.gobo == null && it.name.contains("OPEN", ignoreCase = true)
        } ?: setting.sortedValues.firstOrNull {
            it is DmxFixtureGoboSettingValue && it.gobo == null
        } ?: setting.sortedValues.firstOrNull {
            it.name.contains("OPEN", ignoreCase = true)
        }

    /** The wheel slot with the prism out of the beam. */
    private fun prismOutSetting(setting: DmxFixtureSetting<*>): DmxFixtureSettingValue? =
        setting.sortedValues.firstOrNull {
            it is DmxFixturePrismSettingValue && it.prismFacets == null
        } ?: setting.sortedValues.firstOrNull {
            it.name.contains("OFF", ignoreCase = true) ||
                it.name.contains("OUT", ignoreCase = true) ||
                it.name.contains("OPEN", ignoreCase = true)
        }
}
