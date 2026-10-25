package uk.me.cormack.lighting7.fx

import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fixture.FixtureAttributeCatalogue
import uk.me.cormack.lighting7.fixture.GroupableFixture
import uk.me.cormack.lighting7.fixture.AttributeCategory
import uk.me.cormack.lighting7.fixture.dmx.DmxColour
import uk.me.cormack.lighting7.fixture.dmx.DmxFixtureSetting
import uk.me.cormack.lighting7.fixture.dmx.DmxSlider
import uk.me.cormack.lighting7.fixture.dmx.DmxWhiteMixColour
import uk.me.cormack.lighting7.fixture.group.FixtureElement
import uk.me.cormack.lighting7.fixture.group.MultiElementFixture
import uk.me.cormack.lighting7.fixture.attribute.Slider
import uk.me.cormack.lighting7.fixture.trait.WithAmber
import uk.me.cormack.lighting7.fixture.trait.WithPosition
import uk.me.cormack.lighting7.fixture.trait.WithUv
import uk.me.cormack.lighting7.fixture.trait.WithWhite
import uk.me.cormack.lighting7.midi.AttributeChannelResolver
import uk.me.cormack.lighting7.show.Fixtures
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicReference
import kotlin.reflect.KProperty1

/**
 * Resolves a typed [CueAssignmentResolver.AttributeValue] on a [GroupableFixture] to the concrete DMX
 * channels that back it. Unlike [AttributeChannelResolver] (which scales 7-bit MIDI input and
 * only supports sliders/RGB), this writer accepts full-range `UByte` values plus the composite
 * [CueAssignmentResolver.AttributeValue.Colour] / [CueAssignmentResolver.AttributeValue.Position] variants.
 *
 * Handles:
 * - [CueAssignmentResolver.AttributeValue.Slider] — single channel write at full 0..255 range.
 * - [CueAssignmentResolver.AttributeValue.Setting] — single channel write at the raw DMX level.
 * - [CueAssignmentResolver.AttributeValue.Colour] — R/G/B writes always; white / amber / UV
 *   writes emitted when the fixture implements the respective [WithWhite] / [WithAmber]
 *   / [WithUv] trait. Trait-less fixtures silently drop the extended channel (same
 *   contract for all three). On a [DmxWhiteMixColour] the colour's nearest warm/cold mix,
 *   its two whites' writes; W/A/UV are not part of it.
 * - [CueAssignmentResolver.AttributeValue.Position] — pan + tilt writes when the fixture implements
 *   [WithPosition].
 *
 * Unknown / unsupported property names and reflection failures return an empty list (logged
 * at debug). Callers treat empty as a silent no-op.
 *
 * Accepts both whole [Fixture]s (via their `@FixtureAttribute`-catalogued members) and
 * [FixtureElement]s (reflection on the element's own class — elements aren't
 * [Fixture]s and don't participate in the parent's [Fixture.fixtureAttributes] catalogue).
 */
object AttributeChannelWriter {
    private val logger = LoggerFactory.getLogger(AttributeChannelWriter::class.java)

    /**
     * Resolve a `(fixture, attribute, value)` triple to the channel writes that represent
     * it on the DMX patch. Returns an empty list when the property is absent or the
     * [AttributeValue][CueAssignmentResolver.AttributeValue] kind doesn't match the property's backing
     * type.
     */
    fun resolve(
        fixture: GroupableFixture,
        attribute: String,
        value: CueAssignmentResolver.AttributeValue,
    ): List<AttributeChannelResolver.ChannelWrite> = when (value) {
        is CueAssignmentResolver.AttributeValue.Slider -> resolveByteChannel(fixture, attribute, value.value)
        is CueAssignmentResolver.AttributeValue.Setting ->
            resolveByteChannel(fixture, attribute, value.channelValue)
        is CueAssignmentResolver.AttributeValue.Colour -> resolveColour(fixture, attribute, value.value)
        is CueAssignmentResolver.AttributeValue.Position -> resolvePosition(fixture, value.pan, value.tilt)
    }

    /**
     * Enumerate every channel that backs [attribute] on [fixture], without binding a
     * value. Used by [ProgrammerWriter.clearAttribute] and the cascade publish to know
     * which channels back a property. Returns the channel list with `value = 0` as a placeholder — callers
     * must ignore the value field on clear paths.
     */
    fun channelsFor(
        fixture: GroupableFixture,
        attribute: String,
    ): List<AttributeChannelResolver.ChannelWrite> {
        if (attribute.equals("position", ignoreCase = true)) {
            return resolvePosition(fixture, 0u, 0u)
        }
        val resolved = resolveAttribute(fixture, attribute) ?: return emptyList()
        return when (val raw = resolved.value) {
            is DmxSlider -> listOf(
                AttributeChannelResolver.ChannelWrite(raw.universe, raw.channelNo, 0u, resolved.category)
            )
            is DmxColour -> buildList {
                add(AttributeChannelResolver.ChannelWrite(raw.universe, raw.redSlider.channelNo, 0u, AttributeCategory.COLOUR))
                add(AttributeChannelResolver.ChannelWrite(raw.universe, raw.greenSlider.channelNo, 0u, AttributeCategory.COLOUR))
                add(AttributeChannelResolver.ChannelWrite(raw.universe, raw.blueSlider.channelNo, 0u, AttributeCategory.COLOUR))
                extendedChannelWrite((fixture as? WithWhite)?.white, 0u, AttributeCategory.WHITE)?.let { add(it) }
                extendedChannelWrite((fixture as? WithAmber)?.amber, 0u, AttributeCategory.AMBER)?.let { add(it) }
                extendedChannelWrite((fixture as? WithUv)?.uv, 0u, AttributeCategory.UV)?.let { add(it) }
            }
            is DmxWhiteMixColour -> listOf(
                AttributeChannelResolver.ChannelWrite(raw.warm.universe, raw.warm.channelNo, 0u, AttributeCategory.WHITE),
                AttributeChannelResolver.ChannelWrite(raw.cold.universe, raw.cold.channelNo, 0u, AttributeCategory.WHITE),
            )
            // resolved.category, not a hardcoded SETTING — a gobo or prism wheel is backed
            // by a DmxFixtureSetting but no longer carries the SETTING category, and the
            // clear path must key off the same category the write path used.
            is DmxFixtureSetting<*> -> listOf(
                AttributeChannelResolver.ChannelWrite(raw.universe, raw.channelNo, 0u, resolved.category)
            )
            else -> emptyList()
        }
    }

    /**
     * Every property key whose channels include each DMX address, over [fixtures] and every
     * element of the multi-head ones among them — [channelsFor] inverted, keyed by
     * `(universe, channel)`.
     *
     * An address is routinely driven through **more than one** key, and that is the point of
     * returning a list: a bundled amber is both its own `amber` slider and a component of
     * `rgbColour`; a mover's pan axis is both its `pan` slider and half of `position`. Which one
     * owns the address depends on which layer wrote it — `updateChannel` lifts to the slider,
     * a cue colour lands on `rgbColour`, a cue position on `position` — so a reader asking
     * "who owns this channel" has to ask about all of them. The channel-mapping frame carries
     * this so the DMX sheet can ask, rather than rebuilding the lookup from descriptors that
     * omit bundled emitters and name no element's white.
     *
     * Keys are in property-catalogue order per fixture, `position` last, deduplicated; a
     * fixture's own keys precede its elements'.
     */
    fun attributeKeysByChannel(
        fixtures: Iterable<Fixture>,
    ): Map<Pair<Int, Int>, List<CueAssignmentResolver.Key>> = allKeys(channelKeysByChannel(fixtures))

    /**
     * The **one** key a raw write to each DMX address belongs to — the desk's covering lookup,
     * which `updateChannel`, provenance, Record, Clear and Blind all read through
     * `CascadePublisher.resolveChannelCoveringKey`. Chosen from [attributeKeysByChannel]'s keys:
     *
     * - a **single-channel** property (a slider or setting whose own channel this is) wins, so a
     *   bundled amber is `amber` rather than `rgbColour`, and a pan axis `pan` rather than
     *   `position`;
     * - otherwise the aggregate that spans it: `rgbColour` for an R/G/B channel, `position` for
     *   an axis with no slider of its own.
     *
     * Ties go to list order — a whole fixture ahead of its elements, then catalogue order —
     * which is the order the walk this replaced visited them in.
     */
    fun coveringKeysByChannel(
        fixtures: Iterable<Fixture>,
    ): Map<Pair<Int, Int>, CueAssignmentResolver.Key> = coveringKeys(channelKeysByChannel(fixtures))

    /**
     * Both per-address answers — every key driving an address ([attributeKeysByChannel], which the
     * channel-mapping frame publishes) and the one a raw write belongs to ([coveringKeysByChannel],
     * which `CascadePublisher.resolveChannelCoveringKey` answers) — from **one** walk of the
     * register at [version].
     */
    class ChannelKeyIndex internal constructor(
        val version: Long,
        val attributeKeys: Map<Pair<Int, Int>, List<CueAssignmentResolver.Key>>,
        val coveringKeys: Map<Pair<Int, Int>, CueAssignmentResolver.Key>,
    )

    /** The last index built, and the register it walked — held weakly, so a project switch frees it. */
    private class CachedIndex(val fixtures: WeakReference<Fixtures>, val index: ChannelKeyIndex)

    private val indexCache = AtomicReference<CachedIndex?>(null)

    /**
     * The [ChannelKeyIndex] for [fixtures], **built once per [Fixtures.structureVersion]** and shared
     * by both of its readers. The walk is a reflective read of every property of every fixture and
     * head; the channel-mapping frame needs it once per patch edit, and provenance asks the covering
     * lookup once per sideband slot on every recompute — so each keeping a cache of its own would
     * still walk the register twice per edit.
     *
     * [snapshot], when given, is the register read the caller is about to join the result to (the
     * channel-mapping frame pairs these keys with that read's mappings): the index returned is then
     * the one for *that* read's version, never a newer or older one. Without it, the index is for the
     * live version. Two threads rebuilding at once both build a correct index; the last write wins,
     * and a reader always compares against the version it wants, so an older one is never served.
     */
    fun channelKeyIndex(
        fixtures: Fixtures,
        snapshot: Fixtures.ChannelMappingSnapshot? = null,
    ): ChannelKeyIndex {
        val wanted = snapshot?.version ?: fixtures.structureVersion
        indexCache.get()?.let { cached ->
            if (cached.fixtures.get() === fixtures && cached.index.version == wanted) return cached.index
        }
        val read = snapshot ?: fixtures.channelMappingSnapshot()
        val walked = channelKeysByChannel(read.fixtures)
        val index = ChannelKeyIndex(read.version, allKeys(walked), coveringKeys(walked))
        indexCache.set(CachedIndex(WeakReference(fixtures), index))
        return index
    }

    private fun allKeys(
        walked: Map<Pair<Int, Int>, List<ChannelKey>>,
    ): Map<Pair<Int, Int>, List<CueAssignmentResolver.Key>> =
        walked.mapValues { (_, keys) -> keys.map { it.key } }

    private fun coveringKeys(
        walked: Map<Pair<Int, Int>, List<ChannelKey>>,
    ): Map<Pair<Int, Int>, CueAssignmentResolver.Key> =
        walked.mapValues { (_, keys) -> (keys.firstOrNull { it.singleChannel } ?: keys.first()).key }

    /** One key driving an address, and whether it is a single-channel property of its own. */
    private data class ChannelKey(val key: CueAssignmentResolver.Key, val singleChannel: Boolean)

    private fun channelKeysByChannel(
        fixtures: Iterable<Fixture>,
    ): Map<Pair<Int, Int>, List<ChannelKey>> {
        val byChannel = LinkedHashMap<Pair<Int, Int>, LinkedHashMap<CueAssignmentResolver.Key, ChannelKey>>()
        fun visit(target: GroupableFixture) {
            val attributes = resolvedAttributes(target)
            val names = attributes.map { it.name } +
                if (target is WithPosition) listOf("position") else emptyList()
            val singleChannel = attributes
                .filter { it.value is DmxSlider || it.value is DmxFixtureSetting<*> }
                .mapTo(HashSet()) { it.name }
            for (name in names.distinct()) {
                val key = CueAssignmentResolver.Key.fixture(target.targetKey, name)
                val entry = ChannelKey(key, singleChannel = name in singleChannel)
                for (write in channelsFor(target, name)) {
                    byChannel.getOrPut(write.universe.universe to write.channel) { LinkedHashMap() }
                        .putIfAbsent(key, entry)
                }
            }
        }
        for (fixture in fixtures) {
            visit(fixture)
            if (fixture is MultiElementFixture<*>) fixture.elements.forEach { visit(it) }
        }
        return byChannel.mapValues { (_, keys) -> keys.values.toList() }
    }

    /**
     * A single-channel property carrying one [UByte], whichever descriptor shape backs it.
     *
     * [CueAssignmentResolver.AttributeValue.Slider] and [CueAssignmentResolver.AttributeValue.Setting] are
     * chosen from the property's *category*, but the category does not determine the
     * backing shape: `goboRotation` is a [DmxFixtureSetting] on the Equinox Fusion 100 and a
     * plain [DmxSlider] on the Martin MAC 250, Robe ColorSpot 575 and Varytec Easymove. Both
     * are one DMX channel taking one byte, so insisting on a particular shape only ever
     * throws the write away — which is why several fixtures' gobo, prism and macro channels
     * could not be driven from a cue at all.
     */
    private fun resolveByteChannel(
        fixture: GroupableFixture,
        attribute: String,
        value: UByte,
    ): List<AttributeChannelResolver.ChannelWrite> {
        val resolved = resolveAttribute(fixture, attribute) ?: run {
            logger.debug("Property '{}' not found on fixture '{}'", attribute, fixture.targetKey)
            return emptyList()
        }
        val (universe, channelNo) = when (val raw = resolved.value) {
            is DmxSlider -> raw.universe to raw.channelNo
            is DmxFixtureSetting<*> -> raw.universe to raw.channelNo
            else -> {
                logger.debug(
                    "Byte value targeted property '{}' with no single backing channel (type {}) on '{}'",
                    attribute, raw::class.simpleName, fixture.targetKey,
                )
                return emptyList()
            }
        }
        return listOf(AttributeChannelResolver.ChannelWrite(universe, channelNo, value, resolved.category))
    }

    private fun resolveColour(
        fixture: GroupableFixture,
        attribute: String,
        value: ExtendedColour,
    ): List<AttributeChannelResolver.ChannelWrite> {
        val resolved = resolveAttribute(fixture, attribute) ?: return emptyList()
        val raw = resolved.value
        if (raw is DmxWhiteMixColour) {
            val levels = raw.mix.levelsFor(value.color)
            return listOf(
                AttributeChannelResolver.ChannelWrite(raw.warm.universe, raw.warm.channelNo, levels.warm, AttributeCategory.WHITE),
                AttributeChannelResolver.ChannelWrite(raw.cold.universe, raw.cold.channelNo, levels.cold, AttributeCategory.WHITE),
            )
        }
        if (raw !is DmxColour) {
            logger.debug(
                "Colour value targeted non-colour property '{}' (type {}) on '{}'",
                attribute, raw::class.simpleName, fixture.targetKey,
            )
            return emptyList()
        }
        return buildList {
            add(AttributeChannelResolver.ChannelWrite(raw.universe, raw.redSlider.channelNo, value.color.red.toUByte(), AttributeCategory.COLOUR))
            add(AttributeChannelResolver.ChannelWrite(raw.universe, raw.greenSlider.channelNo, value.color.green.toUByte(), AttributeCategory.COLOUR))
            add(AttributeChannelResolver.ChannelWrite(raw.universe, raw.blueSlider.channelNo, value.color.blue.toUByte(), AttributeCategory.COLOUR))
            extendedChannelWrite((fixture as? WithWhite)?.white, value.white, AttributeCategory.WHITE)?.let { add(it) }
            extendedChannelWrite((fixture as? WithAmber)?.amber, value.amber, AttributeCategory.AMBER)?.let { add(it) }
            extendedChannelWrite((fixture as? WithUv)?.uv, value.uv, AttributeCategory.UV)?.let { add(it) }
        }
    }

    private fun resolvePosition(
        fixture: GroupableFixture,
        pan: UByte,
        tilt: UByte,
    ): List<AttributeChannelResolver.ChannelWrite> {
        val pos = fixture as? WithPosition ?: return emptyList()
        val panSlider = pos.pan as? DmxSlider
        val tiltSlider = pos.tilt as? DmxSlider
        if (panSlider == null || tiltSlider == null) {
            logger.debug("Position on '{}' not backed by DMX sliders", fixture.targetKey)
            return emptyList()
        }
        return listOf(
            AttributeChannelResolver.ChannelWrite(panSlider.universe, panSlider.channelNo, pan, AttributeCategory.PAN),
            AttributeChannelResolver.ChannelWrite(tiltSlider.universe, tiltSlider.channelNo, tilt, AttributeCategory.TILT),
        )
    }

    /**
     * Build a channel write for an optional extended-colour slider. Returns null when the
     * slider is absent (trait not implemented on the fixture) or not DMX-backed.
     */
    private fun extendedChannelWrite(
        slider: Slider?,
        value: UByte,
        category: AttributeCategory,
    ): AttributeChannelResolver.ChannelWrite? {
        val dmx = slider as? DmxSlider ?: return null
        return AttributeChannelResolver.ChannelWrite(dmx.universe, dmx.channelNo, value, category)
    }

    /** Reflection result — the backing value and its declared category. */
    internal data class ResolvedAttribute(val value: Any, val category: AttributeCategory)

    /**
     * Look up a property by name on [fixture], returning its current backing value and its
     * [AttributeCategory]. Both shapes resolve through [FixtureAttributeCatalogue] — [Fixture] via
     * [Fixture.fixtureAttribute], elements against their own class directly, since
     * [FixtureElement] does not extend [Fixture] and so has no such accessor. Returns null if
     * the property is absent or its backing value is null / reflection fails.
     *
     * Internal because it is the one place that resolves a property name on *either* shape of
     * [GroupableFixture] — [FxTarget] getters, [FxEngine.inferTargetForAttribute] and
     * [LocateValueResolver] reuse it rather than duplicating the element reflection.
     */
    internal fun resolveAttribute(fixture: GroupableFixture, attribute: String): ResolvedAttribute? {
        return when (fixture) {
            is Fixture -> {
                val declared = fixture.fixtureAttribute(attribute) ?: return null
                val raw = readAttribute(declared.classProperty, fixture, attribute, fixture.key) ?: return null
                ResolvedAttribute(raw, declared.category)
            }
            is FixtureElement<*> -> {
                val declared = FixtureAttributeCatalogue.of(fixture::class).byName[attribute] ?: return null
                val raw = readAttribute(declared.classProperty, fixture, attribute, fixture.elementKey)
                    ?: return null
                ResolvedAttribute(raw, declared.category)
            }
            else -> null
        }
    }

    /** A named, resolved property — [resolveAttribute]'s shape plus the property name. */
    internal data class NamedAttribute(val name: String, val category: AttributeCategory, val value: Any)

    /**
     * Enumerate every `@FixtureAttribute` on [fixture] with its resolved backing value, in one
     * pass — the enumeration counterpart of [resolveAttribute], for callers (locate) that walk
     * all properties rather than looking one up by name.
     */
    internal fun resolvedAttributes(fixture: GroupableFixture): List<NamedAttribute> = when (fixture) {
        is Fixture -> fixture.fixtureAttributes.mapNotNull { attribute ->
            val raw = readAttribute(attribute.classProperty, fixture, attribute.name, fixture.key)
                ?: return@mapNotNull null
            NamedAttribute(attribute.name, attribute.category, raw)
        }
        is FixtureElement<*> -> FixtureAttributeCatalogue.of(fixture::class).all.mapNotNull { attribute ->
            val raw = readAttribute(attribute.classProperty, fixture, attribute.name, fixture.elementKey)
                ?: return@mapNotNull null
            NamedAttribute(attribute.name, attribute.category, raw)
        }
        else -> emptyList()
    }

    private fun readAttribute(
        classProperty: KProperty1<*, *>,
        receiver: GroupableFixture,
        attribute: String,
        ownerKey: String,
    ): Any? = try {
        @Suppress("UNCHECKED_CAST")
        (classProperty as KProperty1<Any, *>).call(receiver)
    } catch (e: Exception) {
        logger.warn("Failed to read property '{}' on '{}': {}", attribute, ownerKey, e.message)
        null
    }
}
