package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.dmx.ParkManager
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fixture.GroupableFixture
import uk.me.cormack.lighting7.fixture.PropertyCategory
import uk.me.cormack.lighting7.fixture.dmx.DmxColour
import uk.me.cormack.lighting7.fixture.dmx.DmxFixtureSetting
import uk.me.cormack.lighting7.fixture.dmx.DmxSlider
import uk.me.cormack.lighting7.fixture.property.Slider
import uk.me.cormack.lighting7.fixture.trait.WithAmber
import uk.me.cormack.lighting7.fixture.trait.WithColour
import uk.me.cormack.lighting7.fixture.trait.WithDimmer
import uk.me.cormack.lighting7.fixture.trait.WithPosition
import uk.me.cormack.lighting7.fixture.trait.WithUv
import uk.me.cormack.lighting7.fixture.trait.WithWhite
import uk.me.cormack.lighting7.show.Fixtures
import java.awt.Color

/**
 * Reference to a fixture or group target.
 *
 * This sealed class enables FxTarget to reference either a single fixture
 * or an entire group, allowing effects to be applied uniformly to both.
 */
sealed class FxTargetRef {
    /** Key identifying the target (fixture key or group name) */
    abstract val targetKey: String

    /** Whether this references a group */
    abstract val isGroup: Boolean

    /** Reference to a single fixture by its key */
    data class FixtureRef(override val targetKey: String) : FxTargetRef() {
        override val isGroup: Boolean = false
    }

    /** Reference to a fixture group by its name */
    data class GroupRef(override val targetKey: String) : FxTargetRef() {
        override val isGroup: Boolean = true
    }

    companion object {
        /** Create a reference to a fixture */
        fun fixture(key: String) = FixtureRef(key)

        /** Create a reference to a group */
        fun group(name: String) = GroupRef(name)
    }
}

/**
 * Represents a property on a fixture or group that can be targeted by an effect.
 *
 * FxTarget abstracts how effect outputs are applied to fixture properties,
 * handling type conversion and blend mode application. Targets can reference
 * either a single fixture or a fixture group.
 */
sealed class FxTarget {
    /** Reference to the fixture or group this target applies to */
    abstract val targetRef: FxTargetRef

    /** Name of the property being targeted */
    abstract val propertyName: String

    /** Target key (fixture key or group name) */
    val targetKey: String get() = targetRef.targetKey

    /** Whether this target references a group */
    val isGroupTarget: Boolean get() = targetRef.isGroup

    /**
     * Apply an effect output value to a single fixture or element.
     *
     * @param fixture The fixture or element to apply to
     * @param output The effect output value to apply
     * @param blendMode How to blend with existing value
     */
    abstract fun applyValueToFixture(
        fixture: GroupableFixture,
        output: FxOutput,
        blendMode: BlendMode
    )

    /**
     * Get the current value from a fixture or element (before FX).
     *
     * @param fixture The fixture or element to read from
     * @return The current value as an FxOutput
     */
    abstract fun getCurrentValueFromFixture(fixture: GroupableFixture): FxOutput

    /**
     * Check whether a fixture or element has the property this target controls.
     *
     * Used by FxEngine to determine whether an effect needs to be expanded
     * to multi-element fixture elements.
     *
     * @param fixture The fixture or element to check
     * @return true if the fixture supports this target's property
     */
    abstract fun fixtureHasProperty(fixture: GroupableFixture): Boolean

    /**
     * Apply an effect output value to this target using the fixture registry.
     * For fixture targets, applies to the single fixture.
     * For group targets, this method should be called per-member by the engine.
     *
     * @param fixtures The fixture registry with transaction
     * @param fixtureKey The specific fixture key to apply to
     * @param output The effect output value to apply
     * @param blendMode How to blend with existing value
     */
    fun applyValue(
        fixtures: Fixtures.FixturesWithTransaction,
        fixtureKey: String,
        output: FxOutput,
        blendMode: BlendMode
    ) {
        val fixture = fixtures.untypedGroupableFixture(fixtureKey)
        applyValueToFixture(fixture, output, blendMode)
    }

    /**
     * Reset the targeted property on a fixture to a pre-resolved fallback value.
     *
     * Called each tick before effect output is applied so accumulative blend modes compose
     * over the correct baseline, and also when an effect is removed and no other active
     * effect still covers the same property. The [fallback] is computed by the caller via
     * [LayerResolver.fallbackFor] and represents the programmer → cue layer → baseline
     * cascade result — it replaces the previous hardcoded zero that clobbered manual writes.
     *
     * @param fixture The fixture or element to reset
     * @param fallback The value to reset to (usually from [LayerResolver])
     * @param fadeMs Optional ramp duration for the write. 0 (the default) snaps — the tick
     *   loop always snaps; only explicit clear/blind/set publishes pass a fade, and only
     *   for keys no running effect covers (a covered key's ramp would be killed by the
     *   next tick's snap write through the conflated channel changer).
     */
    abstract fun resetToFallback(fixture: GroupableFixture, fallback: FxOutput, fadeMs: Long = 0)

    /**
     * Compose the programmer layer's contribution for this target + fixture over [below]
     * (the already-resolved cue-layer-or-baseline value). Returns [below] unchanged when
     * the programmer holds nothing here.
     *
     * Within the programmer, recency ([ProgrammerStore.Slot.seq]) arbitrates across the
     * granularities that can cover a component: the property-level entry, related property
     * entries (a Colour entry supplies its bundled W/A/UV sliders and vice versa), and the
     * raw-channel sideband. Components the programmer does not cover come from [below] —
     * so a raw write on one colour channel overlays a cue's colour rather than blacking
     * out the rest. Each target subclass knows how to map its property to channel
     * address(es) via the fixture's patch, so channel-level lookups are localized here
     * rather than in [LayerResolver].
     */
    abstract fun composeProgrammerOver(
        fixture: GroupableFixture,
        store: ProgrammerStore,
        below: FxOutput,
    ): FxOutput

    /**
     * The fixture baseline (Layer 5) value for this target: 0 for sliders and settings,
     * black for colour, 128/128 centred for pan/tilt. The bottom of the cascade — returned
     * when no layer above contributes.
     */
    abstract fun baselineFallback(fixture: GroupableFixture): FxOutput

    /**
     * True when every DMX channel backing this target on [fixture] is parked. Used by
     * [FxEngine]'s Layer 1 short-circuit. Returns false for non-DMX-backed properties so the
     * normal reset path runs. Must not allocate on the hot path.
     */
    abstract fun isPropertyFullyParked(fixture: GroupableFixture, parkManager: ParkManager): Boolean

    /**
     * Resolve and write the fallback for this target to the specified fixture.
     *
     * @param fixtures The fixture registry with transaction
     * @param fixtureKey The specific fixture key to reset
     * @param fallback The value to reset to
     */
    fun resetToFallback(
        fixtures: Fixtures.FixturesWithTransaction,
        fixtureKey: String,
        fallback: FxOutput,
        fadeMs: Long = 0,
    ) {
        val fixture = try {
            fixtures.untypedGroupableFixture(fixtureKey)
        } catch (_: Exception) { return }
        resetToFallback(fixture, fallback, fadeMs)
    }
}

/**
 * Target a slider property (dimmer, UV, etc.)
 */
data class SliderTarget(
    override val targetRef: FxTargetRef,
    override val propertyName: String
) : FxTarget() {

    /** Convenience constructor for targeting a single fixture */
    constructor(fixtureKey: String, propertyName: String) :
        this(FxTargetRef.fixture(fixtureKey), propertyName)

    override fun applyValueToFixture(
        fixture: GroupableFixture,
        output: FxOutput,
        blendMode: BlendMode
    ) {
        if (output !is FxOutput.Slider) return

        val slider = getSlider(fixture) ?: return

        val baseValue = slider.value ?: 0u
        val newValue = applyBlendMode(baseValue, output.value, blendMode)
        slider.value = newValue
    }

    override fun getCurrentValueFromFixture(fixture: GroupableFixture): FxOutput {
        val slider = getSlider(fixture)
        return FxOutput.Slider(slider?.value ?: 0u)
    }

    override fun fixtureHasProperty(fixture: GroupableFixture): Boolean {
        return getSlider(fixture) != null
    }

    override fun resetToFallback(fixture: GroupableFixture, fallback: FxOutput, fadeMs: Long) {
        if (fallback !is FxOutput.Slider) return
        val slider = getSlider(fixture) ?: return
        if (fadeMs > 0) slider.fadeToValue(fallback.value, fadeMs) else slider.value = fallback.value
    }

    override fun composeProgrammerOver(
        fixture: GroupableFixture,
        store: ProgrammerStore,
        below: FxOutput,
    ): FxOutput {
        // Up to three programmer sources can cover a slider: its own property entry, a
        // Colour entry on the fixture's colour property (for bundled W/A/UV sliders), and
        // the raw-channel sideband. Recency ([ProgrammerStore.Slot.seq]) arbitrates.
        var bestSeq = Long.MIN_VALUE
        var best: UByte? = null

        store.get(fixture.targetKey, propertyName)?.let { slot ->
            when (val v = slot.value.resolved) {
                is Layer3Resolver.PropertyValue.Slider -> { best = v.value; bestSeq = slot.seq }
                is Layer3Resolver.PropertyValue.Setting -> { best = v.channelValue; bestSeq = slot.seq }
                else -> {}
            }
        }
        bundledComponentFromColourEntry(fixture, store)?.let { (seq, component) ->
            if (seq > bestSeq) { best = component; bestSeq = seq }
        }
        // Sideband lookup is only meaningful for DMX sliders (Hue-backed etc. have no channel).
        (getSlider(fixture) as? DmxSlider)?.let { dmx ->
            store.getChannelSlot(dmx.universe.universe, dmx.channelNo)?.let { slot ->
                val v = (slot.value.resolved as? Layer3Resolver.PropertyValue.Slider)?.value
                if (v != null && slot.seq > bestSeq) { best = v; bestSeq = slot.seq }
            }
        }
        return best?.let { FxOutput.Slider(it) } ?: below
    }

    override fun baselineFallback(fixture: GroupableFixture): FxOutput = FxOutput.Slider(0u)

    /**
     * When this target is a `bundleWithColour` W/A/UV slider and the programmer holds a
     * Colour entry on the fixture's colour property, extract the matching component with
     * the entry's write sequence — the property-level twin of the channel-level
     * composition ColourTarget performs in the other direction.
     */
    private fun bundledComponentFromColourEntry(
        fixture: GroupableFixture,
        store: ProgrammerStore,
    ): Pair<Long, UByte>? {
        if (fixture !is Fixture) return null
        val prop = fixture.fixtureProperty(propertyName) ?: return null
        if (!prop.bundleWithColour) return null
        val colourProp = fixture.fixtureProperties.find { it.category == PropertyCategory.COLOUR }
            ?: return null
        val slot = store.get(fixture.targetKey, colourProp.name) ?: return null
        val colour = (slot.value.resolved as? Layer3Resolver.PropertyValue.Colour)?.value ?: return null
        val component = when (prop.category) {
            PropertyCategory.WHITE -> colour.white
            PropertyCategory.AMBER -> colour.amber
            PropertyCategory.UV -> colour.uv
            else -> return null
        }
        return slot.seq to component
    }

    override fun isPropertyFullyParked(fixture: GroupableFixture, parkManager: ParkManager): Boolean {
        val dmx = getSlider(fixture) as? DmxSlider ?: return false
        return parkManager.isParked(dmx.universe.universe, dmx.channelNo)
    }

    private fun getSlider(fixture: GroupableFixture): Slider? {
        return when (propertyName) {
            "dimmer" -> (fixture as? WithDimmer)?.dimmer
            "uv" -> (fixture as? WithUv)?.uv
            "white" -> (fixture as? WithWhite)?.white
            "amber" -> (fixture as? WithAmber)?.amber
            else -> {
                // Look up arbitrary slider properties by name — fixtures and elements alike
                PropertyChannelWriter.resolveProperty(fixture, propertyName)?.value as? Slider
            }
        }
    }

    private fun applyBlendMode(base: UByte, effect: UByte, mode: BlendMode): UByte {
        return when (mode) {
            BlendMode.OVERRIDE -> effect
            BlendMode.ADDITIVE -> (base.toInt() + effect.toInt()).coerceIn(0, 255).toUByte()
            BlendMode.MULTIPLY -> ((base.toInt() * effect.toInt()) / 255).toUByte()
            BlendMode.MAX -> maxOf(base, effect)
            BlendMode.MIN -> minOf(base, effect)
        }
    }

    companion object {
        /** Create a slider target for a group */
        fun forGroup(groupName: String, propertyName: String) =
            SliderTarget(FxTargetRef.group(groupName), propertyName)
    }
}

/**
 * Target RGB colour property, with support for extended channels (W/A/UV).
 *
 * When the effect outputs an [ExtendedColour] with non-zero W/A/UV values,
 * those are applied to the fixture's bundled slider properties (if present).
 */
data class ColourTarget(
    override val targetRef: FxTargetRef,
    override val propertyName: String = "rgbColour"
) : FxTarget() {

    /** Convenience constructor for targeting a single fixture */
    constructor(fixtureKey: String) : this(FxTargetRef.fixture(fixtureKey))

    override fun applyValueToFixture(
        fixture: GroupableFixture,
        output: FxOutput,
        blendMode: BlendMode
    ) {
        if (output !is FxOutput.Colour) return

        // Apply RGB channels
        val colour = (fixture as? WithColour)?.rgbColour ?: return
        val baseColour = colour.value ?: Color.BLACK
        val newColour = applyRgbBlendMode(baseColour, output.color.color, blendMode)
        colour.value = newColour

        // Apply extended channels (W/A/UV) to bundled slider properties
        if (fixture is Fixture) {
            val ext = output.color
            applyExtendedChannel(fixture, PropertyCategory.WHITE, ext.white, blendMode)
            applyExtendedChannel(fixture, PropertyCategory.AMBER, ext.amber, blendMode)
            applyExtendedChannel(fixture, PropertyCategory.UV, ext.uv, blendMode)
        }
    }

    override fun getCurrentValueFromFixture(fixture: GroupableFixture): FxOutput {
        val colour = (fixture as? WithColour)?.rgbColour
        return FxOutput.Colour(colour?.value ?: Color.BLACK)
    }

    override fun resetToFallback(fixture: GroupableFixture, fallback: FxOutput, fadeMs: Long) {
        if (fallback !is FxOutput.Colour) return
        val colour = (fixture as? WithColour)?.rgbColour ?: return
        val ext = fallback.color
        if (fadeMs > 0) colour.fadeToColour(ext.color, fadeMs) else colour.value = ext.color

        // Apply extended channels (W/A/UV) to bundled slider properties using the fallback values.
        if (fixture is Fixture) {
            setExtendedChannel(fixture, PropertyCategory.WHITE, ext.white, fadeMs)
            setExtendedChannel(fixture, PropertyCategory.AMBER, ext.amber, fadeMs)
            setExtendedChannel(fixture, PropertyCategory.UV, ext.uv, fadeMs)
        }
    }

    override fun composeProgrammerOver(
        fixture: GroupableFixture,
        store: ProgrammerStore,
        below: FxOutput,
    ): FxOutput {
        // Compose per component, arbitrating each by write recency across the granularities
        // that can cover it: the Colour property entry, the bundled W/A/UV sliders' own
        // property entries, and the raw-channel sideband. This is the same channel set the
        // old channel-level store composed; components the programmer does not cover come
        // from [below] (e.g. a raw write on just the red channel overlays a cue's colour).
        val colourSlot = store.get(fixture.targetKey, propertyName)
        val colourValue = (colourSlot?.value?.resolved as? Layer3Resolver.PropertyValue.Colour)?.value
        val colourSeq = if (colourValue != null) colourSlot!!.seq else Long.MIN_VALUE

        val dmxColour = (fixture as? WithColour)?.rgbColour as? DmxColour
        if (colourValue == null && dmxColour == null) return below

        fun component(entryComponent: UByte?, channelNo: Int?): UByte? {
            val side = if (dmxColour != null && channelNo != null) {
                store.getChannelSlot(dmxColour.universe.universe, channelNo)
            } else null
            val sideValue = (side?.value?.resolved as? Layer3Resolver.PropertyValue.Slider)?.value
            return when {
                sideValue != null && side!!.seq > colourSeq -> sideValue
                entryComponent != null -> entryComponent
                else -> sideValue
            }
        }

        val r = component(colourValue?.color?.red?.toUByte(), dmxColour?.redSlider?.channelNo)
        val g = component(colourValue?.color?.green?.toUByte(), dmxColour?.greenSlider?.channelNo)
        val b = component(colourValue?.color?.blue?.toUByte(), dmxColour?.blueSlider?.channelNo)

        var white: UByte? = null
        var amber: UByte? = null
        var uv: UByte? = null
        if (fixture is Fixture) {
            white = extendedComponent(fixture, PropertyCategory.WHITE, colourValue?.white, colourSeq, store)
            amber = extendedComponent(fixture, PropertyCategory.AMBER, colourValue?.amber, colourSeq, store)
            uv = extendedComponent(fixture, PropertyCategory.UV, colourValue?.uv, colourSeq, store)
        } else {
            white = colourValue?.white
            amber = colourValue?.amber
            uv = colourValue?.uv
        }

        if (r == null && g == null && b == null && white == null && amber == null && uv == null) {
            return below
        }
        val belowColour = (below as? FxOutput.Colour)?.color ?: ExtendedColour.BLACK
        return FxOutput.Colour(
            ExtendedColour(
                Color(
                    (r ?: belowColour.color.red.toUByte()).toInt(),
                    (g ?: belowColour.color.green.toUByte()).toInt(),
                    (b ?: belowColour.color.blue.toUByte()).toInt(),
                ),
                white ?: belowColour.white,
                amber ?: belowColour.amber,
                uv ?: belowColour.uv,
            )
        )
    }

    override fun baselineFallback(fixture: GroupableFixture): FxOutput =
        FxOutput.Colour(ExtendedColour.BLACK)

    /**
     * Resolve one bundled W/A/UV component by recency across: the Colour entry's component
     * ([entryComponent] at [colourSeq]), the bundled slider's own property entry, and the
     * slider's sideband channel. Null when nothing in the programmer covers it.
     */
    private fun extendedComponent(
        fixture: Fixture,
        category: PropertyCategory,
        entryComponent: UByte?,
        colourSeq: Long,
        store: ProgrammerStore,
    ): UByte? {
        val prop = fixture.fixtureProperties.find { it.bundleWithColour && it.category == category }
            ?: return entryComponent
        var bestSeq = if (entryComponent != null) colourSeq else Long.MIN_VALUE
        var best = entryComponent

        store.get(fixture.key, prop.name)?.let { slot ->
            val v = (slot.value.resolved as? Layer3Resolver.PropertyValue.Slider)?.value
            if (v != null && slot.seq > bestSeq) { best = v; bestSeq = slot.seq }
        }
        ((prop.classProperty.call(fixture) as? Slider) as? DmxSlider)?.let { dmx ->
            store.getChannelSlot(dmx.universe.universe, dmx.channelNo)?.let { slot ->
                val v = (slot.value.resolved as? Layer3Resolver.PropertyValue.Slider)?.value
                if (v != null && slot.seq > bestSeq) { best = v; bestSeq = slot.seq }
            }
        }
        return best
    }

    override fun fixtureHasProperty(fixture: GroupableFixture): Boolean {
        return fixture is WithColour
    }

    override fun isPropertyFullyParked(fixture: GroupableFixture, parkManager: ParkManager): Boolean {
        val dmxColour = (fixture as? WithColour)?.rgbColour as? DmxColour ?: return false
        val u = dmxColour.universe.universe
        if (!parkManager.isParked(u, dmxColour.redSlider.channelNo)) return false
        if (!parkManager.isParked(u, dmxColour.greenSlider.channelNo)) return false
        if (!parkManager.isParked(u, dmxColour.blueSlider.channelNo)) return false
        if (fixture is Fixture) {
            if (!bundledChannelParked(fixture, PropertyCategory.WHITE, parkManager)) return false
            if (!bundledChannelParked(fixture, PropertyCategory.AMBER, parkManager)) return false
            if (!bundledChannelParked(fixture, PropertyCategory.UV, parkManager)) return false
        }
        return true
    }

    /** True if the fixture has no bundled channel in this category, or the backing DMX channel is parked. */
    private fun bundledChannelParked(
        fixture: Fixture,
        category: PropertyCategory,
        parkManager: ParkManager,
    ): Boolean {
        val prop = fixture.fixtureProperties.find { it.bundleWithColour && it.category == category }
            ?: return true
        val dmx = (prop.classProperty.call(fixture) as? Slider) as? DmxSlider ?: return true
        return parkManager.isParked(dmx.universe.universe, dmx.channelNo)
    }

    private fun applyExtendedChannel(fixture: Fixture, category: PropertyCategory, value: UByte, blendMode: BlendMode) {
        val prop = fixture.fixtureProperties.find { it.bundleWithColour && it.category == category } ?: return
        val slider = prop.classProperty.call(fixture) as? Slider ?: return
        val base = slider.value ?: 0u
        slider.value = applySliderBlendMode(base, value, blendMode)
    }

    private fun setExtendedChannel(fixture: Fixture, category: PropertyCategory, value: UByte, fadeMs: Long = 0) {
        val prop = fixture.fixtureProperties.find { it.bundleWithColour && it.category == category } ?: return
        val slider = prop.classProperty.call(fixture) as? Slider ?: return
        if (fadeMs > 0) slider.fadeToValue(value, fadeMs) else slider.value = value
    }

    private fun applyRgbBlendMode(base: Color, effect: Color, mode: BlendMode): Color {
        return when (mode) {
            BlendMode.OVERRIDE -> effect
            BlendMode.ADDITIVE -> Color(
                (base.red + effect.red).coerceIn(0, 255),
                (base.green + effect.green).coerceIn(0, 255),
                (base.blue + effect.blue).coerceIn(0, 255)
            )
            BlendMode.MULTIPLY -> Color(
                (base.red * effect.red) / 255,
                (base.green * effect.green) / 255,
                (base.blue * effect.blue) / 255
            )
            BlendMode.MAX -> Color(
                maxOf(base.red, effect.red),
                maxOf(base.green, effect.green),
                maxOf(base.blue, effect.blue)
            )
            BlendMode.MIN -> Color(
                minOf(base.red, effect.red),
                minOf(base.green, effect.green),
                minOf(base.blue, effect.blue)
            )
        }
    }

    private fun applySliderBlendMode(base: UByte, effect: UByte, mode: BlendMode): UByte {
        return when (mode) {
            BlendMode.OVERRIDE -> effect
            BlendMode.ADDITIVE -> (base.toInt() + effect.toInt()).coerceIn(0, 255).toUByte()
            BlendMode.MULTIPLY -> ((base.toInt() * effect.toInt()) / 255).toUByte()
            BlendMode.MAX -> maxOf(base, effect)
            BlendMode.MIN -> minOf(base, effect)
        }
    }

    companion object {
        /** Create a colour target for a group */
        fun forGroup(groupName: String) = ColourTarget(FxTargetRef.group(groupName))
    }
}

/**
 * Target pan/tilt position properties.
 */
data class PositionTarget(
    override val targetRef: FxTargetRef,
    override val propertyName: String = "position"
) : FxTarget() {

    /** Convenience constructor for targeting a single fixture */
    constructor(fixtureKey: String) : this(FxTargetRef.fixture(fixtureKey))

    override fun applyValueToFixture(
        fixture: GroupableFixture,
        output: FxOutput,
        blendMode: BlendMode
    ) {
        if (output !is FxOutput.Position) return

        val positionFixture = fixture as? WithPosition ?: return

        val basePan = positionFixture.pan.value ?: 128u
        val baseTilt = positionFixture.tilt.value ?: 128u

        val newPan = applyBlendMode(basePan, output.pan, blendMode)
        val newTilt = applyBlendMode(baseTilt, output.tilt, blendMode)

        positionFixture.pan.value = newPan
        positionFixture.tilt.value = newTilt
    }

    override fun getCurrentValueFromFixture(fixture: GroupableFixture): FxOutput {
        val positionFixture = fixture as? WithPosition
        return FxOutput.Position(
            positionFixture?.pan?.value ?: 128u,
            positionFixture?.tilt?.value ?: 128u
        )
    }

    override fun resetToFallback(fixture: GroupableFixture, fallback: FxOutput, fadeMs: Long) {
        if (fallback !is FxOutput.Position) return
        val positionFixture = fixture as? WithPosition ?: return
        if (fadeMs > 0) {
            positionFixture.pan.fadeToValue(fallback.pan, fadeMs)
            positionFixture.tilt.fadeToValue(fallback.tilt, fadeMs)
        } else {
            positionFixture.pan.value = fallback.pan
            positionFixture.tilt.value = fallback.tilt
        }
    }

    override fun composeProgrammerOver(
        fixture: GroupableFixture,
        store: ProgrammerStore,
        below: FxOutput,
    ): FxOutput {
        // Per-axis recency arbitration between a Position property entry and the axis's
        // sideband channel slot (raw pan/tilt writes stay channel-shaped in the sideband).
        val posSlot = store.get(fixture.targetKey, propertyName)
        val posValue = posSlot?.value?.resolved as? Layer3Resolver.PropertyValue.Position
        val posSeq = if (posValue != null) posSlot!!.seq else Long.MIN_VALUE

        val positionFixture = fixture as? WithPosition
        if (posValue == null && positionFixture == null) return below

        fun axis(entryValue: UByte?, slider: Slider?): UByte? {
            val dmx = slider as? DmxSlider
            val side = dmx?.let { store.getChannelSlot(it.universe.universe, it.channelNo) }
            val sideValue = (side?.value?.resolved as? Layer3Resolver.PropertyValue.Slider)?.value
            return when {
                sideValue != null && side!!.seq > posSeq -> sideValue
                entryValue != null -> entryValue
                else -> sideValue
            }
        }

        val pan = axis(posValue?.pan, positionFixture?.pan)
        val tilt = axis(posValue?.tilt, positionFixture?.tilt)
        if (pan == null && tilt == null) return below
        // Components the programmer does not cover come from the layer below.
        val belowPos = below as? FxOutput.Position
        return FxOutput.Position(
            pan ?: belowPos?.pan ?: 128u,
            tilt ?: belowPos?.tilt ?: 128u,
        )
    }

    override fun baselineFallback(fixture: GroupableFixture): FxOutput = FxOutput.Position(128u, 128u)

    override fun fixtureHasProperty(fixture: GroupableFixture): Boolean {
        return fixture is WithPosition
    }

    override fun isPropertyFullyParked(fixture: GroupableFixture, parkManager: ParkManager): Boolean {
        val positionFixture = fixture as? WithPosition ?: return false
        val pan = positionFixture.pan as? DmxSlider ?: return false
        val tilt = positionFixture.tilt as? DmxSlider ?: return false
        return parkManager.isParked(pan.universe.universe, pan.channelNo) &&
            parkManager.isParked(tilt.universe.universe, tilt.channelNo)
    }

    private fun applyBlendMode(base: UByte, effect: UByte, mode: BlendMode): UByte {
        return when (mode) {
            BlendMode.OVERRIDE -> effect
            BlendMode.ADDITIVE -> (base.toInt() + effect.toInt() - 128).coerceIn(0, 255).toUByte()
            BlendMode.MULTIPLY -> ((base.toInt() * effect.toInt()) / 255).toUByte()
            BlendMode.MAX -> maxOf(base, effect)
            BlendMode.MIN -> minOf(base, effect)
        }
    }

    companion object {
        /** Create a position target for a group */
        fun forGroup(groupName: String) = PositionTarget(FxTargetRef.group(groupName))
    }
}

/**
 * Target a fixture setting property (e.g. operating mode, colour preset).
 *
 * Settings map to a single DMX channel with named options. This target
 * finds the DmxFixtureSetting by property name and sets its DMX channel
 * directly. Only OVERRIDE blend mode is meaningful for settings.
 */
data class SettingTarget(
    override val targetRef: FxTargetRef,
    override val propertyName: String
) : FxTarget() {

    /** Convenience constructor for targeting a single fixture */
    constructor(fixtureKey: String, propertyName: String) :
        this(FxTargetRef.fixture(fixtureKey), propertyName)

    override fun applyValueToFixture(
        fixture: GroupableFixture,
        output: FxOutput,
        blendMode: BlendMode
    ) {
        if (output !is FxOutput.Slider) return

        val setting = getSetting(fixture) ?: return
        val transaction = setting.transaction ?: return

        // Settings are discrete — always override regardless of blend mode
        transaction.setValue(setting.universe, setting.channelNo, output.value)
    }

    override fun getCurrentValueFromFixture(fixture: GroupableFixture): FxOutput {
        val setting = getSetting(fixture) ?: return FxOutput.Slider(0u)
        val transaction = setting.transaction ?: return FxOutput.Slider(0u)
        val currentLevel = transaction.getValue(setting.universe, setting.channelNo)
        return FxOutput.Slider(currentLevel)
    }

    override fun resetToFallback(fixture: GroupableFixture, fallback: FxOutput, fadeMs: Long) {
        // Settings are discrete — snap regardless of fadeMs (a wheel mid-ramp is garbage).
        if (fallback !is FxOutput.Slider) return
        val setting = getSetting(fixture) ?: return
        val transaction = setting.transaction ?: return
        transaction.setValue(setting.universe, setting.channelNo, fallback.value)
    }

    override fun composeProgrammerOver(
        fixture: GroupableFixture,
        store: ProgrammerStore,
        below: FxOutput,
    ): FxOutput {
        var bestSeq = Long.MIN_VALUE
        var best: UByte? = null
        store.get(fixture.targetKey, propertyName)?.let { slot ->
            when (val v = slot.value.resolved) {
                is Layer3Resolver.PropertyValue.Setting -> { best = v.channelValue; bestSeq = slot.seq }
                is Layer3Resolver.PropertyValue.Slider -> { best = v.value; bestSeq = slot.seq }
                else -> {}
            }
        }
        getSetting(fixture)?.let { setting ->
            store.getChannelSlot(setting.universe.universe, setting.channelNo)?.let { slot ->
                val v = (slot.value.resolved as? Layer3Resolver.PropertyValue.Slider)?.value
                if (v != null && slot.seq > bestSeq) { best = v; bestSeq = slot.seq }
            }
        }
        return best?.let { FxOutput.Slider(it) } ?: below
    }

    override fun baselineFallback(fixture: GroupableFixture): FxOutput = FxOutput.Slider(0u)

    override fun fixtureHasProperty(fixture: GroupableFixture): Boolean {
        return getSetting(fixture) != null
    }

    override fun isPropertyFullyParked(fixture: GroupableFixture, parkManager: ParkManager): Boolean {
        val setting = getSetting(fixture) ?: return false
        return parkManager.isParked(setting.universe.universe, setting.channelNo)
    }

    private fun getSetting(fixture: GroupableFixture): DmxFixtureSetting<*>? =
        PropertyChannelWriter.resolveProperty(fixture, propertyName)?.value as? DmxFixtureSetting<*>

    companion object {
        /** Create a setting target for a group */
        fun forGroup(groupName: String, propertyName: String) =
            SettingTarget(FxTargetRef.group(groupName), propertyName)
    }
}
