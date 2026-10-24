package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fixture.FixtureAttributeCatalogue
import uk.me.cormack.lighting7.fixture.GroupableFixture
import uk.me.cormack.lighting7.fixture.AttributeCategory
import uk.me.cormack.lighting7.fixture.StrobeKind
import uk.me.cormack.lighting7.fixture.dmx.DmxColour
import uk.me.cormack.lighting7.fixture.dmx.DmxFixtureColourSettingValue
import uk.me.cormack.lighting7.fixture.dmx.DmxFixtureGoboSettingValue
import uk.me.cormack.lighting7.fixture.dmx.DmxFixturePrismSettingValue
import uk.me.cormack.lighting7.fixture.dmx.DmxFixtureSetting
import uk.me.cormack.lighting7.fixture.dmx.DmxFixtureSettingValue
import uk.me.cormack.lighting7.fixture.dmx.DmxFixtureStrobeSettingValue
import uk.me.cormack.lighting7.fixture.dmx.DmxFixtureZoomSettingValue
import uk.me.cormack.lighting7.fixture.dmx.DmxSlider
import uk.me.cormack.lighting7.fixture.group.FixtureElement
import uk.me.cormack.lighting7.fixture.media.FittedMedia
import uk.me.cormack.lighting7.fixture.media.OPEN_WHITE
import uk.me.cormack.lighting7.fixture.media.colourOf
import uk.me.cormack.lighting7.fixture.group.MultiElementFixture
import uk.me.cormack.lighting7.fixture.locate.UnitLocate
import uk.me.cormack.lighting7.fixture.attribute.Strobe
import uk.me.cormack.lighting7.fixture.trait.WithPosition
import uk.me.cormack.lighting7.show.focusRange
import uk.me.cormack.lighting7.show.middleDmx
import uk.me.cormack.lighting7.show.travelToDmx
import java.awt.Color
import kotlin.math.roundToInt

/**
 * Computes the property values a console "Locate" asserts on a fixture: centre the beam and
 * force it open white so the physical unit can be identified in the rig.
 *
 * Per category: pan/tilt to the unit's **stored locate** where its patch holds one (venue-visit plan
 * D6, D17 — `fixture/locate/StoredLocate.kt`, coarse and fine as stored, fine 0 where none is stored),
 * else to the middle of their range (fine channels to 0 — the 16-bit midpoint is coarse-mid +
 * fine-0), pan to its front instead where the type declares one (`forwardDeg`); every other property keeps its category default, stored locate or not: dimmer full, shutter open, colour white, gobo open,
 * prism out, iris/frost open, zoom mid, focus at the middle *distance* of a declared range
 * (mid-DMX where none is declared), and every framing shutter out and square to the gate.
 * "Colour white" adapts to the engine: RGB mixers get full RGB *and* full white (the fan-out in
 * [AttributeChannelWriter.resolveColour] also zeroes amber/uv); colour wheels pick their open/white
 * slot — unless an RGB engine
 * coexists on the same target, in which case the wheel is *disengaged* (its off slot) so the
 * RGB white wins. Standalone WHITE/AMBER/UV sliders go to full only when there is no RGB
 * engine to fan out from — white/amber-only blinders and UV cannons must still light.
 * Speed, macro, rotation and bare SETTING channels are left alone.
 *
 * Values are expressed as [CueAssignmentResolver.AttributeValue]s for the FX engine's Layer-4 write
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
        val attribute: String,
        val value: CueAssignmentResolver.AttributeValue,
    )

    /** Locate white: full RGB *and* full white — covers RGB-only and white-engine fixtures. */
    private val LOCATE_WHITE = ExtendedColour(Color.WHITE, white = 255u)

    /** Parent channels a single-element locate must still raise for the head to emit light. */
    private val PARENT_MASTER_CATEGORIES = setOf(AttributeCategory.DIMMER, AttributeCategory.STROBE)

    /**
     * [media] is the fixture's fitted media (`Fixtures.fittedMediaFor`): Locate's white frame is the
     * one that is open **on this unit**, so a venue string with a gel in the stock open leader still
     * locates to white. [locate] is the stored locate of the patch [fixture] belongs to — its parent's
     * for a head (`Fixtures.storedLocateFor`) — each unit taking its own entry: the fixture's (head
     * null) and each head's by index. Both defaulted only for the tests, which hold no patch; the
     * route passes them.
     */
    fun resolve(
        fixture: GroupableFixture,
        media: FittedMedia? = null,
        locate: List<UnitLocate> = emptyList(),
    ): List<LocateAssignment> = buildList {
        fun storedFor(head: Int?) = locate.firstOrNull { it.head == head }
        addAll(resolveSingle(fixture, media = media, stored = storedFor((fixture as? FixtureElement<*>)?.elementIndex)))
        when (fixture) {
            is MultiElementFixture<*> -> for (element in fixture.elements) {
                addAll(resolveSingle(element, stored = storedFor(element.elementIndex)))
            }
            is FixtureElement<*> -> addAll(
                resolveSingle(fixture.parentFixture, onlyCategories = PARENT_MASTER_CATEGORIES)
            )
            else -> {}
        }
    }

    /**
     * A unit's pan and tilt, where it pans and tilts on DMX channels: the coarse sliders, and the fine
     * ones by property name where it has them. What a stored locate is captured from and written to.
     */
    data class LocateAxes(
        val pan: DmxSlider,
        val tilt: DmxSlider,
        val panFine: Pair<String, DmxSlider>?,
        val tiltFine: Pair<String, DmxSlider>?,
    )

    /** [target]'s [LocateAxes], or null where it does not pan and tilt on DMX channels. */
    fun axesOf(target: GroupableFixture): LocateAxes? {
        if (target !is WithPosition) return null
        val pan = target.pan as? DmxSlider ?: return null
        val tilt = target.tilt as? DmxSlider ?: return null
        val attributes = AttributeChannelWriter.resolvedAttributes(target)
        fun fine(category: AttributeCategory) = attributes.firstOrNull { it.category == category }
            ?.let { attribute -> (attribute.value as? DmxSlider)?.let { attribute.name to it } }
        return LocateAxes(pan, tilt, fine(AttributeCategory.PAN_FINE), fine(AttributeCategory.TILT_FINE))
    }

    private fun resolveSingle(
        target: GroupableFixture,
        onlyCategories: Set<AttributeCategory>? = null,
        media: FittedMedia? = null,
        stored: UnitLocate? = null,
    ): List<LocateAssignment> {
        val attributes = AttributeChannelWriter.resolvedAttributes(target)
        val hasRgbEngine = attributes.any {
            it.category == AttributeCategory.COLOUR && it.value is DmxColour
        }

        // Candidates carry their backing so channel collisions can be resolved below.
        val candidates = mutableListOf<Pair<AttributeChannelWriter.NamedAttribute, CueAssignmentResolver.AttributeValue>>()

        // A stored locate is the unit's own only where it pans and tilts on DMX channels — the units
        // `StoredLocate.unitsOf` offers — and is kept inside each slider's range.
        val unitLocate = if (onlyCategories == null) stored ?: frontLocate(target) else null
        val storedAxes = unitLocate?.let { s -> axesOf(target)?.let { s to it } }
        if (onlyCategories == null && target is WithPosition) {
            val pan = storedAxes?.let { (s, axes) -> clampTo(s.pan, axes.pan) }
                ?: (target.pan as? DmxSlider)?.let(::midValue) ?: 128u.toUByte()
            val tilt = storedAxes?.let { (s, axes) -> clampTo(s.tilt, axes.tilt) }
                ?: (target.tilt as? DmxSlider)?.let(::midValue) ?: 128u.toUByte()
            candidates += AttributeChannelWriter.NamedAttribute("position", AttributeCategory.OTHER, Unit) to
                CueAssignmentResolver.AttributeValue.Position(pan, tilt)
        }

        val declared = declaredAttributes(target)
        for (attribute in attributes) {
            if (onlyCategories != null && attribute.category !in onlyCategories) continue
            // WithPosition pan/tilt are covered by the synthetic "position" write above.
            if (target is WithPosition &&
                (attribute.category == AttributeCategory.PAN || attribute.category == AttributeCategory.TILT)
            ) {
                continue
            }
            val value = storedAxes?.let { (s, axes) -> storedFineFor(attribute, s, axes) }
                ?: locateValueFor(attribute.name, attribute.category, attribute.value, hasRgbEngine, declared[attribute.name], media)
                ?: continue
            candidates += attribute to value
        }

        return dedupeByChannel(candidates).map { (attribute, value) ->
            LocateAssignment(target, attribute.name, value)
        }
    }

    /**
     * The annotations [target]'s properties were declared with, by name — a focus's range lives
     * there. The class catalogue's own index, cached per class, for a fixture and a cell alike.
     */
    private fun declaredAttributes(target: GroupableFixture): Map<String, Fixture.Attribute> = when (target) {
        is Fixture, is FixtureElement<*> -> FixtureAttributeCatalogue.of(target::class).byName
        else -> emptyMap()
    }

    private fun locateValueFor(
        attribute: String,
        category: AttributeCategory,
        backing: Any,
        hasRgbEngine: Boolean,
        declared: Fixture.Attribute?,
        media: FittedMedia?,
    ): CueAssignmentResolver.AttributeValue? =
        when (category) {
            AttributeCategory.DIMMER ->
                (backing as? DmxSlider)?.let { CueAssignmentResolver.AttributeValue.Slider(it.max) }

            // Only reached on fixtures without WithPosition (e.g. a lone axis channel).
            AttributeCategory.PAN, AttributeCategory.TILT ->
                (backing as? DmxSlider)?.let { CueAssignmentResolver.AttributeValue.Slider(midValue(it)) }

            AttributeCategory.PAN_FINE, AttributeCategory.TILT_FINE ->
                (backing as? DmxSlider)?.let { CueAssignmentResolver.AttributeValue.Slider(0u) }

            // Any Strobe channel knows its shutter-open level; coerce because the raw value
            // bypasses the slider's own clamp, and a fullOnValue above a fenced-off max must
            // not drive into lamp/reset bands the setter would have refused.
            AttributeCategory.STROBE -> when (backing) {
                is DmxSlider -> if (backing is Strobe) {
                    CueAssignmentResolver.AttributeValue.Slider(backing.fullOnValue.coerceIn(backing.min, backing.max))
                } else null
                // A setting-backed shutter (fixture optics plan D12): its first OPEN position.
                is DmxFixtureSetting<*> -> backing.sortedValues
                    .firstOrNull { (it as? DmxFixtureStrobeSettingValue)?.strobeKind == StrobeKind.OPEN }
                    ?.let { CueAssignmentResolver.AttributeValue.Setting(it.level) }
                else -> null
            }

            AttributeCategory.COLOUR -> when (backing) {
                is DmxColour -> CueAssignmentResolver.AttributeValue.Colour(LOCATE_WHITE)
                is DmxFixtureSetting<*> -> {
                    // With an RGB engine on the same target the wheel must be *disengaged*,
                    // not set to a white preset — its macro slots override the RGB channels.
                    val slot = if (hasRgbEngine) disengagedSetting(backing) else whiteSetting(backing, attribute, media)
                    slot?.let { CueAssignmentResolver.AttributeValue.Setting(it.level) }
                }
                else -> null
            }

            AttributeCategory.GOBO -> when (backing) {
                is DmxFixtureSetting<*> ->
                    openGoboSetting(backing)?.let { CueAssignmentResolver.AttributeValue.Setting(it.level) }
                is DmxSlider -> CueAssignmentResolver.AttributeValue.Slider(backing.min)
                else -> null
            }

            AttributeCategory.PRISM -> when (backing) {
                is DmxFixtureSetting<*> ->
                    prismOutSetting(backing)?.let { CueAssignmentResolver.AttributeValue.Setting(it.level) }
                is DmxSlider -> CueAssignmentResolver.AttributeValue.Slider(backing.min)
                else -> null
            }

            AttributeCategory.IRIS, AttributeCategory.FROST ->
                (backing as? DmxSlider)?.let { CueAssignmentResolver.AttributeValue.Slider(it.min) }

            AttributeCategory.ZOOM -> when (backing) {
                is DmxSlider -> CueAssignmentResolver.AttributeValue.Slider(midValue(backing))
                // A stepped zoom (fixture-optics plan D2): the step nearest its middle angle, the
                // first in level order on a tie — the Robe's 18°, not its focus-corrected twin.
                is DmxFixtureSetting<*> -> middleZoomStep(backing)?.let { CueAssignmentResolver.AttributeValue.Setting(it.level) }
                else -> null
            }

            // A declared range is linear in 1 / distance, so mid-DMX is near its near end (3.8 m on a
            // Revolution's 2–40 m): park at the range's middle distance instead. Undeclared, mid-DMX.
            AttributeCategory.FOCUS -> (backing as? DmxSlider)?.let { slider ->
                val range = declared?.focusRange(slider)
                CueAssignmentResolver.AttributeValue.Slider(range?.middleDmx()?.toUByte() ?: midValue(slider))
            }

            // A framing shutter out: DMX min, its depth 0 — DMX max where the slider is inverted.
            AttributeCategory.SHUTTER -> (backing as? DmxSlider)?.let { slider ->
                CueAssignmentResolver.AttributeValue.Slider(if (declared?.inverted == true) slider.max else slider.min)
            }

            // A blade square to the gate: the DMX for 0°, solved through its declared angles (128 on
            // the Revolution's ±45°). Mid-DMX where it declares none.
            AttributeCategory.SHUTTER_ROTATION -> (backing as? DmxSlider)?.let { slider ->
                CueAssignmentResolver.AttributeValue.Slider(declared?.let { squareDmx(it, slider) } ?: midValue(slider))
            }

            // Standalone white/amber/UV engines with no RGB property to fan out from — the
            // whole light output of white/amber blinders and UV cannons. With an RGB engine
            // present these are written (or zeroed) by the Colour fan-out instead.
            AttributeCategory.WHITE, AttributeCategory.AMBER, AttributeCategory.UV ->
                if (!hasRgbEngine) {
                    (backing as? DmxSlider)?.let { CueAssignmentResolver.AttributeValue.Slider(it.max) }
                } else null

            else -> null
        }

    /**
     * Drop assignments whose single backing channel is already claimed, with STROBE
     * outranking everything: on fixtures that share one channel between dimmer and shutter,
     * the shutter-open level is the authoritative "steady full output" value.
     */
    private fun dedupeByChannel(
        candidates: List<Pair<AttributeChannelWriter.NamedAttribute, CueAssignmentResolver.AttributeValue>>,
    ): List<Pair<AttributeChannelWriter.NamedAttribute, CueAssignmentResolver.AttributeValue>> {
        fun channelOf(attribute: AttributeChannelWriter.NamedAttribute): Pair<Universe, Int>? =
            when (val backing = attribute.value) {
                is DmxSlider -> backing.universe to backing.channelNo
                is DmxFixtureSetting<*> -> backing.universe to backing.channelNo
                else -> null
            }

        val strobeChannels = candidates
            .filter { (attribute, _) -> attribute.category == AttributeCategory.STROBE }
            .mapNotNullTo(HashSet()) { (attribute, _) -> channelOf(attribute) }

        val claimed = HashSet<Pair<Universe, Int>>()
        return candidates.filter { (attribute, _) ->
            val channel = channelOf(attribute) ?: return@filter true
            if (attribute.category != AttributeCategory.STROBE && channel in strobeChannels) {
                return@filter false
            }
            claimed.add(channel)
        }
    }

    /** A stored locate's fine byte for [attribute] where it is the unit's fine pan or tilt: 0 where none is stored. */
    private fun storedFineFor(
        attribute: AttributeChannelWriter.NamedAttribute,
        stored: UnitLocate,
        axes: LocateAxes,
    ): CueAssignmentResolver.AttributeValue? {
        val (fine, value) = when (attribute.name) {
            axes.panFine?.first -> axes.panFine.second to stored.panFine
            axes.tiltFine?.first -> axes.tiltFine.second to stored.tiltFine
            else -> return null
        }
        return CueAssignmentResolver.AttributeValue.Slider(clampTo(value ?: 0, fine))
    }

    /**
     * Where a unit whose pan declares its front (`@FixtureAttribute.forwardDeg`) locates when it has no
     * stored locate: pan at the front, fine included, so tilt swings up and downstage as on any other
     * mover; tilt in the middle. Null for every other unit, which centres both axes.
     */
    private fun frontLocate(target: GroupableFixture): UnitLocate? {
        val pan = declaredAttributes(target).values.firstOrNull { it.category == AttributeCategory.PAN } ?: return null
        val forward = pan.forwardDeg ?: return null
        if (pan.degMin == null || pan.degMax == null || pan.degMin == pan.degMax) return null
        val axes = axesOf(target) ?: return null
        val front = travelToDmx(forward, pan, axes.pan, axes.panFine?.second)
        return UnitLocate(pan = front.coarse.toInt(), tilt = midValue(axes.tilt).toInt(), panFine = front.fine?.toInt())
    }

    private fun clampTo(value: Int, slider: DmxSlider): UByte =
        value.coerceIn(slider.min.toInt(), slider.max.toInt()).toUByte()

    // Round up so a full 0..255 range centres on the DMX convention of 128 (16-bit 0x8000
    // with fine at 0), not 127.
    private fun midValue(slider: DmxSlider): UByte =
        ((slider.min.toInt() + slider.max.toInt() + 1) / 2).toUByte()

    /**
     * The DMX a SHUTTER_ROTATION slider turns its blade square at: 0° over its declared
     * `degMin..degMax`, honouring `inverted`, rounded to the slider's step and kept inside its range
     * (so a range that never reaches 0° parks at its nearer end). Null where no range is declared.
     */
    private fun squareDmx(declared: Fixture.Attribute, slider: DmxSlider): UByte? {
        val degMin = declared.degMin ?: return null
        val degMax = declared.degMax ?: return null
        if (degMin == degMax) return null
        val p = ((0.0 - degMin) / (degMax - degMin)).coerceIn(0.0, 1.0)
        val t = if (declared.inverted) 1.0 - p else p
        val min = slider.min.toInt()
        val max = slider.max.toInt()
        return (min + t * (max - min)).roundToInt().coerceIn(min, max).toUByte()
    }

    /**
     * The wheel slot that renders white **on this unit**: a `#FFFFFF` preview — its fitted content
     * where it has some, else the stock ([colourOf]) — else an OPEN/WHITE name the unit has not
     * fitted a gel into.
     */
    private fun whiteSetting(setting: DmxFixtureSetting<*>, attribute: String, media: FittedMedia?): DmxFixtureSettingValue? =
        setting.sortedValues.firstOrNull {
            media.colourOf(attribute, it)?.equals(OPEN_WHITE, ignoreCase = true) == true
        } ?: setting.sortedValues.firstOrNull {
            (it.name.contains("OPEN", ignoreCase = true) || it.name.contains("WHITE", ignoreCase = true)) &&
                media?.slot(attribute, it.name)?.gel == null
        }

    /** A stepped zoom's step nearest the middle of its angles; null where no option declares one. */
    private fun middleZoomStep(setting: DmxFixtureSetting<*>): DmxFixtureSettingValue? {
        val steps = setting.sortedValues.filterIsInstance<DmxFixtureZoomSettingValue>()
        if (steps.isEmpty()) return null
        val middle = (steps.minOf { it.zoomDeg } + steps.maxOf { it.zoomDeg }) / 2
        return steps.minBy { kotlin.math.abs(it.zoomDeg - middle) }
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
