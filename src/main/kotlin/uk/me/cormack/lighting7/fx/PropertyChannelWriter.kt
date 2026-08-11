package uk.me.cormack.lighting7.fx

import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fixture.FixtureProperty
import uk.me.cormack.lighting7.fixture.GroupableFixture
import uk.me.cormack.lighting7.fixture.PropertyCategory
import uk.me.cormack.lighting7.fixture.dmx.DmxColour
import uk.me.cormack.lighting7.fixture.dmx.DmxFixtureSetting
import uk.me.cormack.lighting7.fixture.dmx.DmxSlider
import uk.me.cormack.lighting7.fixture.group.FixtureElement
import uk.me.cormack.lighting7.fixture.property.Slider
import uk.me.cormack.lighting7.fixture.trait.WithAmber
import uk.me.cormack.lighting7.fixture.trait.WithPosition
import uk.me.cormack.lighting7.fixture.trait.WithUv
import uk.me.cormack.lighting7.fixture.trait.WithWhite
import uk.me.cormack.lighting7.midi.PropertyChannelResolver
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties

/**
 * Resolves a typed [Layer3Resolver.PropertyValue] on a [GroupableFixture] to the concrete DMX
 * channels that back it. Unlike [PropertyChannelResolver] (which scales 7-bit MIDI input and
 * only supports sliders/RGB), this writer accepts full-range `UByte` values plus the composite
 * [Layer3Resolver.PropertyValue.Colour] / [Layer3Resolver.PropertyValue.Position] variants.
 *
 * Handles:
 * - [Layer3Resolver.PropertyValue.Slider] — single channel write at full 0..255 range.
 * - [Layer3Resolver.PropertyValue.Setting] — single channel write at the raw DMX level.
 * - [Layer3Resolver.PropertyValue.Colour] — R/G/B writes always; white / amber / UV
 *   writes emitted when the fixture implements the respective [WithWhite] / [WithAmber]
 *   / [WithUv] trait. Trait-less fixtures silently drop the extended channel (same
 *   contract for all three).
 * - [Layer3Resolver.PropertyValue.Position] — pan + tilt writes when the fixture implements
 *   [WithPosition].
 *
 * Unknown / unsupported property names and reflection failures return an empty list (logged
 * at debug). Callers treat empty as a silent no-op.
 *
 * Accepts both whole [Fixture]s (via their `@FixtureProperty`-catalogued members) and
 * [FixtureElement]s (reflection on the element's own class — elements aren't
 * [Fixture]s and don't participate in the parent's [Fixture.fixtureProperties] catalogue).
 */
object PropertyChannelWriter {
    private val logger = LoggerFactory.getLogger(PropertyChannelWriter::class.java)

    /**
     * Resolve a `(fixture, propertyName, value)` triple to the channel writes that represent
     * it on the DMX patch. Returns an empty list when the property is absent or the
     * [PropertyValue][Layer3Resolver.PropertyValue] kind doesn't match the property's backing
     * type.
     */
    fun resolve(
        fixture: GroupableFixture,
        propertyName: String,
        value: Layer3Resolver.PropertyValue,
    ): List<PropertyChannelResolver.ChannelWrite> = when (value) {
        is Layer3Resolver.PropertyValue.Slider -> resolveByteChannel(fixture, propertyName, value.value)
        is Layer3Resolver.PropertyValue.Setting ->
            resolveByteChannel(fixture, propertyName, value.channelValue)
        is Layer3Resolver.PropertyValue.Colour -> resolveColour(fixture, propertyName, value.value)
        is Layer3Resolver.PropertyValue.Position -> resolvePosition(fixture, value.pan, value.tilt)
    }

    /**
     * Enumerate every channel that backs [propertyName] on [fixture], without binding a
     * value. Used by [FxEngine.clearProgrammerProperty] and the cascade publish to know
     * which channels back a property. Returns the channel list with `value = 0` as a placeholder — callers
     * must ignore the value field on clear paths.
     */
    fun channelsFor(
        fixture: GroupableFixture,
        propertyName: String,
    ): List<PropertyChannelResolver.ChannelWrite> {
        if (propertyName.equals("position", ignoreCase = true)) {
            return resolvePosition(fixture, 0u, 0u)
        }
        val resolved = resolveProperty(fixture, propertyName) ?: return emptyList()
        return when (val raw = resolved.value) {
            is DmxSlider -> listOf(
                PropertyChannelResolver.ChannelWrite(raw.universe, raw.channelNo, 0u, resolved.category)
            )
            is DmxColour -> buildList {
                add(PropertyChannelResolver.ChannelWrite(raw.universe, raw.redSlider.channelNo, 0u, PropertyCategory.COLOUR))
                add(PropertyChannelResolver.ChannelWrite(raw.universe, raw.greenSlider.channelNo, 0u, PropertyCategory.COLOUR))
                add(PropertyChannelResolver.ChannelWrite(raw.universe, raw.blueSlider.channelNo, 0u, PropertyCategory.COLOUR))
                extendedChannelWrite((fixture as? WithWhite)?.white, 0u, PropertyCategory.WHITE)?.let { add(it) }
                extendedChannelWrite((fixture as? WithAmber)?.amber, 0u, PropertyCategory.AMBER)?.let { add(it) }
                extendedChannelWrite((fixture as? WithUv)?.uv, 0u, PropertyCategory.UV)?.let { add(it) }
            }
            // resolved.category, not a hardcoded SETTING — a gobo or prism wheel is backed
            // by a DmxFixtureSetting but no longer carries the SETTING category, and the
            // clear path must key off the same category the write path used.
            is DmxFixtureSetting<*> -> listOf(
                PropertyChannelResolver.ChannelWrite(raw.universe, raw.channelNo, 0u, resolved.category)
            )
            else -> emptyList()
        }
    }

    /**
     * A single-channel property carrying one [UByte], whichever descriptor shape backs it.
     *
     * [Layer3Resolver.PropertyValue.Slider] and [Layer3Resolver.PropertyValue.Setting] are
     * chosen from the property's *category*, but the category does not determine the
     * backing shape: `goboRotation` is a [DmxFixtureSetting] on the Equinox Fusion 100 and a
     * plain [DmxSlider] on the Martin MAC 250, Robe ColorSpot 575 and Varytec Easymove. Both
     * are one DMX channel taking one byte, so insisting on a particular shape only ever
     * throws the write away — which is why several fixtures' gobo, prism and macro channels
     * could not be driven from a cue at all.
     */
    private fun resolveByteChannel(
        fixture: GroupableFixture,
        propertyName: String,
        value: UByte,
    ): List<PropertyChannelResolver.ChannelWrite> {
        val resolved = resolveProperty(fixture, propertyName) ?: run {
            logger.debug("Property '{}' not found on fixture '{}'", propertyName, fixture.targetKey)
            return emptyList()
        }
        val (universe, channelNo) = when (val raw = resolved.value) {
            is DmxSlider -> raw.universe to raw.channelNo
            is DmxFixtureSetting<*> -> raw.universe to raw.channelNo
            else -> {
                logger.debug(
                    "Byte value targeted property '{}' with no single backing channel (type {}) on '{}'",
                    propertyName, raw::class.simpleName, fixture.targetKey,
                )
                return emptyList()
            }
        }
        return listOf(PropertyChannelResolver.ChannelWrite(universe, channelNo, value, resolved.category))
    }

    private fun resolveColour(
        fixture: GroupableFixture,
        propertyName: String,
        value: ExtendedColour,
    ): List<PropertyChannelResolver.ChannelWrite> {
        val resolved = resolveProperty(fixture, propertyName) ?: return emptyList()
        val raw = resolved.value
        if (raw !is DmxColour) {
            logger.debug(
                "Colour value targeted non-colour property '{}' (type {}) on '{}'",
                propertyName, raw::class.simpleName, fixture.targetKey,
            )
            return emptyList()
        }
        return buildList {
            add(PropertyChannelResolver.ChannelWrite(raw.universe, raw.redSlider.channelNo, value.color.red.toUByte(), PropertyCategory.COLOUR))
            add(PropertyChannelResolver.ChannelWrite(raw.universe, raw.greenSlider.channelNo, value.color.green.toUByte(), PropertyCategory.COLOUR))
            add(PropertyChannelResolver.ChannelWrite(raw.universe, raw.blueSlider.channelNo, value.color.blue.toUByte(), PropertyCategory.COLOUR))
            extendedChannelWrite((fixture as? WithWhite)?.white, value.white, PropertyCategory.WHITE)?.let { add(it) }
            extendedChannelWrite((fixture as? WithAmber)?.amber, value.amber, PropertyCategory.AMBER)?.let { add(it) }
            extendedChannelWrite((fixture as? WithUv)?.uv, value.uv, PropertyCategory.UV)?.let { add(it) }
        }
    }

    private fun resolvePosition(
        fixture: GroupableFixture,
        pan: UByte,
        tilt: UByte,
    ): List<PropertyChannelResolver.ChannelWrite> {
        val pos = fixture as? WithPosition ?: return emptyList()
        val panSlider = pos.pan as? DmxSlider
        val tiltSlider = pos.tilt as? DmxSlider
        if (panSlider == null || tiltSlider == null) {
            logger.debug("Position on '{}' not backed by DMX sliders", fixture.targetKey)
            return emptyList()
        }
        return listOf(
            PropertyChannelResolver.ChannelWrite(panSlider.universe, panSlider.channelNo, pan, PropertyCategory.PAN),
            PropertyChannelResolver.ChannelWrite(tiltSlider.universe, tiltSlider.channelNo, tilt, PropertyCategory.TILT),
        )
    }

    /**
     * Build a channel write for an optional extended-colour slider. Returns null when the
     * slider is absent (trait not implemented on the fixture) or not DMX-backed.
     */
    private fun extendedChannelWrite(
        slider: Slider?,
        value: UByte,
        category: PropertyCategory,
    ): PropertyChannelResolver.ChannelWrite? {
        val dmx = slider as? DmxSlider ?: return null
        return PropertyChannelResolver.ChannelWrite(dmx.universe, dmx.channelNo, value, category)
    }

    /** Reflection result — the backing value and its declared category. */
    internal data class ResolvedProperty(val value: Any, val category: PropertyCategory)

    /** One catalogued element property: the reflection accessor plus its declared category. */
    private data class ElementProperty(val classProperty: KProperty1<Any, *>, val category: PropertyCategory)

    /**
     * Per-element-class catalogue of `@FixtureProperty` members, built once per class.
     * Elements have no equivalent of the precomputed [Fixture.fixtureProperties], and the
     * FX tick resolves element properties every frame — a `memberProperties` scan per call
     * would allocate on the hot path ([FxTarget.isPropertyFullyParked] forbids that).
     */
    private val elementCatalogues =
        java.util.concurrent.ConcurrentHashMap<kotlin.reflect.KClass<*>, Map<String, ElementProperty>>()

    private fun elementCatalogue(element: FixtureElement<*>): Map<String, ElementProperty> =
        elementCatalogues.getOrPut(element::class) {
            element::class.memberProperties.mapNotNull { prop ->
                val ann = prop.annotations.filterIsInstance<FixtureProperty>().firstOrNull()
                    ?: return@mapNotNull null
                @Suppress("UNCHECKED_CAST")
                prop.name to ElementProperty(prop as KProperty1<Any, *>, ann.category)
            }.toMap()
        }

    /**
     * Look up a property by name on [fixture], returning its current backing value and its
     * [PropertyCategory]. Handles both [Fixture] (via the pre-built [Fixture.fixtureProperties]
     * catalogue) and [FixtureElement] (via the per-class [elementCatalogue]). Returns null if
     * the property is absent or its backing value is null / reflection fails.
     *
     * Internal because it is the one place that resolves a property name on *either* shape of
     * [GroupableFixture] — [FxTarget] getters, [FxEngine.inferTargetForProperty] and
     * [LocateValueResolver] reuse it rather than duplicating the element reflection.
     */
    internal fun resolveProperty(fixture: GroupableFixture, propertyName: String): ResolvedProperty? {
        return when (fixture) {
            is Fixture -> {
                val property = fixture.fixtureProperty(propertyName) ?: return null
                val raw = readProperty(property.classProperty, fixture, propertyName, fixture.key) ?: return null
                ResolvedProperty(raw, property.category)
            }
            is FixtureElement<*> -> {
                val entry = elementCatalogue(fixture)[propertyName] ?: return null
                val raw = readProperty(entry.classProperty, fixture, propertyName, fixture.elementKey) ?: return null
                ResolvedProperty(raw, entry.category)
            }
            else -> null
        }
    }

    /** A named, resolved property — [resolveProperty]'s shape plus the property name. */
    internal data class NamedProperty(val name: String, val category: PropertyCategory, val value: Any)

    /**
     * Enumerate every `@FixtureProperty` on [fixture] with its resolved backing value, in one
     * pass — the enumeration counterpart of [resolveProperty], for callers (locate) that walk
     * all properties rather than looking one up by name.
     */
    internal fun resolvedProperties(fixture: GroupableFixture): List<NamedProperty> = when (fixture) {
        is Fixture -> fixture.fixtureProperties.mapNotNull { property ->
            val raw = readProperty(property.classProperty, fixture, property.name, fixture.key)
                ?: return@mapNotNull null
            NamedProperty(property.name, property.category, raw)
        }
        is FixtureElement<*> -> elementCatalogue(fixture).mapNotNull { (name, entry) ->
            val raw = readProperty(entry.classProperty, fixture, name, fixture.elementKey)
                ?: return@mapNotNull null
            NamedProperty(name, entry.category, raw)
        }
        else -> emptyList()
    }

    private fun readProperty(
        classProperty: KProperty1<*, *>,
        receiver: GroupableFixture,
        propertyName: String,
        ownerKey: String,
    ): Any? = try {
        @Suppress("UNCHECKED_CAST")
        (classProperty as KProperty1<Any, *>).call(receiver)
    } catch (e: Exception) {
        logger.warn("Failed to read property '{}' on '{}': {}", propertyName, ownerKey, e.message)
        null
    }
}
