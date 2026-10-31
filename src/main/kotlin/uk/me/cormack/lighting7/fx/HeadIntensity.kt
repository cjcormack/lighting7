package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.dmx.DmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.AttributeCategory
import uk.me.cormack.lighting7.fixture.DmxFixture
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fixture.FixtureAttributeCatalogue
import uk.me.cormack.lighting7.fixture.dmx.DmxColour
import uk.me.cormack.lighting7.fixture.dmx.DmxFixtureSetting
import uk.me.cormack.lighting7.fixture.dmx.DmxSlider
import uk.me.cormack.lighting7.fixture.dmx.DmxWhiteMixColour
import uk.me.cormack.lighting7.fixture.group.FixtureElement
import uk.me.cormack.lighting7.fixture.group.MultiElementFixture
import uk.me.cormack.lighting7.show.Fixtures

/**
 * Which channels light one head (move-in-dark plan D4, P3) — a fixture, or one of a
 * [MultiElementFixture]'s elements — and whether they read dark.
 *
 * [classify] is the one reading of "what lights a head": the grand master scales exactly
 * [ownLevels] of every head (`midi/GlobalScalerState`), and Move in dark reads a head dark only when
 * [levels] — its own and its parent's — all read 0. Own levels are every DIMMER and UV channel, and on
 * a **dimmerless** head (neither it nor its parent has a dimmer) its colour mix: a `DmxColour`'s red,
 * green and blue, a `DmxWhiteMixColour`'s two whites, and its WHITE and AMBER sliders. On such a head
 * the colour *is* its intensity, so [colourIsIntensity] keeps Colour out of its moves (D5).
 *
 * STROBE and RING are [cuts]: the grand master cuts them at 0 rather than scaling them. A strobe
 * lights nothing on its own (one open on a lit dimmer is lit because the dimmer is), so it takes no
 * part in dark; an LED ring's effect channel lights the ring, so it is one of [levels].
 */
data class HeadIntensity(
    val headKey: String,
    /** The parent fixture's key for an element, else null. */
    val parentKey: String?,
    val dmxUniverse: Universe,
    /** This head's own scaled channels — exactly what the grand master scales on it. */
    val ownLevels: List<Int>,
    /**
     * Every channel that lights it: [ownLevels], its LED ring's effect channel, and for an element its
     * parent's DIMMER channels; for a parent with no level of its own, its elements' ([elementKeys]).
     */
    val levels: List<Int>,
    /** STROBE and RING channels: cut by the grand master at 0, never a level. */
    val cuts: List<Int>,
    val colourIsIntensity: Boolean,
    /** The head's own DIMMER and STROBE attribute names, which an Intensity FX would drive. */
    val intensityAttributes: Set<String>,
    /** Its parent's DIMMER and STROBE attribute names, for an element. */
    val parentIntensityAttributes: Set<String>,
    /**
     * For a parent with no level of its own, its elements' keys: it is lit by whatever lights one of
     * them, and [levels] are theirs. Empty for every other head.
     */
    val elementKeys: List<String> = emptyList(),
) {
    val universe: Int get() = dmxUniverse.universe

    /**
     * Whether every channel in [levels] reads 0 as composed — cues, FX, the programmer and park, which
     * [DmxController.getValue] overlays — before the grand master, a blackout and the venue hold, which
     * it does not. A head with no level channel at all is never dark: nothing says it is out.
     */
    fun isDark(controller: DmxController?): Boolean {
        if (levels.isEmpty() || controller == null) return false
        for (channel in levels) if (controller.getValue(channel).toInt() != 0) return false
        return true
    }

    /** Whether an Intensity FX in [covered] (`fixtureKey to attribute`, running FX) drives this head. */
    fun hasIntensityFx(covered: Set<Pair<String, String>>, heads: Map<String, HeadIntensity> = emptyMap()): Boolean {
        if (covered.isEmpty()) return false
        if (intensityAttributes.any { (headKey to it) in covered }) return true
        if (elementKeys.any { key -> heads[key]?.hasIntensityFx(covered) == true }) return true
        val parent = parentKey ?: return false
        return parentIntensityAttributes.any { (parent to it) in covered }
    }

    companion object {
        /** Every head of [fixtures], fixtures and elements, by key. DMX fixtures only. */
        fun classify(fixtures: Fixtures): Map<String, HeadIntensity> = classify(fixtures.fixtures)

        fun classify(rig: List<Fixture>): Map<String, HeadIntensity> {
            val heads = LinkedHashMap<String, HeadIntensity>()
            for (fixture in rig) {
                if (fixture !is DmxFixture) continue
                val universe = fixture.universe
                val own = fixture.fixtureAttributes.map { attribute ->
                    Read(attribute.name, attribute.category, runCatching { attribute.classProperty.call(fixture) }.getOrNull())
                }
                val fixtureDimmed = own.any { it.category == AttributeCategory.DIMMER }
                val parent = head(fixture.key, null, universe, own, dimmerless = !fixtureDimmed, parentDimmers = emptyList(), parentAttributes = emptySet())
                heads[fixture.key] = parent
                if (fixture is MultiElementFixture<*>) {
                    val parentDimmers = own.filter { it.category == AttributeCategory.DIMMER }.flatMap { channelsOf(it.value) }
                    val elements = fixture.elements.map { element ->
                        val elementOwn = elementAttributes(element)
                        val elementDimmed = elementOwn.any { it.category == AttributeCategory.DIMMER }
                        head(
                            element.elementKey, fixture.key, universe, elementOwn,
                            dimmerless = !fixtureDimmed && !elementDimmed,
                            parentDimmers = parentDimmers,
                            parentAttributes = parent.intensityAttributes,
                        ).also { heads[element.elementKey] = it }
                    }
                    // A parent lit only through its elements (a bar whose cells carry every level) is
                    // dark when they all are, so what it carries itself — a tilt — can move too.
                    if (parent.levels.isEmpty() && elements.isNotEmpty()) {
                        heads[fixture.key] = parent.copy(
                            levels = elements.flatMap { it.levels }.distinct(),
                            elementKeys = elements.map { it.headKey },
                        )
                    }
                }
            }
            return heads
        }

        private class Read(val name: String, val category: AttributeCategory, val value: Any?)

        private fun elementAttributes(element: FixtureElement<*>): List<Read> =
            FixtureAttributeCatalogue.of(element::class).all.map { attribute ->
                @Suppress("UNCHECKED_CAST")
                val read = attribute.classProperty as kotlin.reflect.KProperty1<Any, *>
                Read(attribute.name, attribute.category, runCatching { read.call(element) }.getOrNull())
            }

        private fun head(
            key: String,
            parentKey: String?,
            universe: Universe,
            attributes: List<Read>,
            dimmerless: Boolean,
            parentDimmers: List<Int>,
            parentAttributes: Set<String>,
        ): HeadIntensity {
            val levels = LinkedHashSet<Int>()
            val cuts = LinkedHashSet<Int>()
            val intensity = HashSet<String>()
            for (attribute in attributes) {
                val value = attribute.value ?: continue
                when (attribute.category) {
                    AttributeCategory.DIMMER, AttributeCategory.UV -> levels += channelsOf(value)
                    AttributeCategory.STROBE, AttributeCategory.RING -> cuts += channelsOf(value)
                    AttributeCategory.COLOUR ->
                        if (dimmerless && (value is DmxColour || value is DmxWhiteMixColour)) levels += channelsOf(value)
                    AttributeCategory.WHITE, AttributeCategory.AMBER ->
                        if (dimmerless && value is DmxSlider) levels += channelsOf(value)
                    else -> Unit
                }
                if (attribute.category == AttributeCategory.DIMMER || attribute.category == AttributeCategory.STROBE) {
                    intensity += attribute.name
                }
            }
            val own = levels.toList()
            // An LED ring's effect channel lights the ring, 0 being off: lit, though never scaled.
            val rings = attributes.filter { it.category == AttributeCategory.RING }.flatMap { channelsOf(it.value) }
            return HeadIntensity(
                headKey = key,
                parentKey = parentKey,
                dmxUniverse = universe,
                ownLevels = own,
                levels = (levels + rings + parentDimmers).toList(),
                cuts = cuts.toList(),
                colourIsIntensity = dimmerless,
                intensityAttributes = intensity,
                parentIntensityAttributes = parentAttributes,
            )
        }

        private fun channelsOf(value: Any?): List<Int> = when (value) {
            is DmxSlider -> listOf(value.channelNo)
            is DmxFixtureSetting<*> -> listOf(value.channelNo)
            is DmxColour -> listOf(value.redSlider.channelNo, value.greenSlider.channelNo, value.blueSlider.channelNo)
            is DmxWhiteMixColour -> listOf(value.warm.channelNo, value.cold.channelNo)
            else -> emptyList()
        }
    }
}
