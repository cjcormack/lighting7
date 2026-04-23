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
     * [LayerResolver.fallbackFor] and represents the Layer 3 / Layer 4 / Layer 5 cascade
     * result — it replaces the previous hardcoded zero that clobbered direct writes.
     *
     * @param fixture The fixture or element to reset
     * @param fallback The value to reset to (usually from [LayerResolver])
     */
    abstract fun resetToFallback(fixture: GroupableFixture, fallback: FxOutput)

    /**
     * Compute the [FxOutput] value representing Layer 4 (sticky direct writes) falling
     * through to Layer 5 (fixture baseline) for this target + fixture. [LayerResolver]
     * consults this when no Layer 3 assignment covers the property.
     *
     * Each target subclass knows how to map its property to channel address(es) via the
     * fixture's patch, so channel-level Layer 4 lookups are localized here rather than in
     * [LayerResolver].
     */
    abstract fun fallbackFromDirectWrites(fixture: GroupableFixture, store: DirectWriteStore): FxOutput

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
    ) {
        val fixture = try {
            fixtures.untypedGroupableFixture(fixtureKey)
        } catch (_: Exception) { return }
        resetToFallback(fixture, fallback)
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

    override fun resetToFallback(fixture: GroupableFixture, fallback: FxOutput) {
        if (fallback !is FxOutput.Slider) return
        val slider = getSlider(fixture) ?: return
        slider.value = fallback.value
    }

    override fun fallbackFromDirectWrites(fixture: GroupableFixture, store: DirectWriteStore): FxOutput {
        // Layer 4 lookup is only meaningful for DMX sliders. Non-DMX sliders (Hue-backed,
        // etc.) fall through to baseline.
        val dmx = getSlider(fixture) as? DmxSlider ?: return FxOutput.Slider(0u)
        val sticky = store.get(dmx.universe.universe, dmx.channelNo) ?: 0u.toUByte()
        return FxOutput.Slider(sticky)
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
                // Look up arbitrary slider properties via fixture reflection
                val f = fixture as? Fixture ?: return null
                val prop = f.fixtureProperties.find { it.name == propertyName } ?: return null
                prop.classProperty.call(f) as? Slider
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

    override fun resetToFallback(fixture: GroupableFixture, fallback: FxOutput) {
        if (fallback !is FxOutput.Colour) return
        val colour = (fixture as? WithColour)?.rgbColour ?: return
        val ext = fallback.color
        colour.value = ext.color

        // Apply extended channels (W/A/UV) to bundled slider properties using the fallback values.
        if (fixture is Fixture) {
            setExtendedChannel(fixture, PropertyCategory.WHITE, ext.white)
            setExtendedChannel(fixture, PropertyCategory.AMBER, ext.amber)
            setExtendedChannel(fixture, PropertyCategory.UV, ext.uv)
        }
    }

    override fun fallbackFromDirectWrites(fixture: GroupableFixture, store: DirectWriteStore): FxOutput {
        val dmxColour = (fixture as? WithColour)?.rgbColour as? DmxColour
            ?: return FxOutput.Colour(ExtendedColour.BLACK)
        val u = dmxColour.universe.universe
        val r = store.get(u, dmxColour.redSlider.channelNo) ?: 0u.toUByte()
        val g = store.get(u, dmxColour.greenSlider.channelNo) ?: 0u.toUByte()
        val b = store.get(u, dmxColour.blueSlider.channelNo) ?: 0u.toUByte()

        var white: UByte = 0u
        var amber: UByte = 0u
        var uv: UByte = 0u
        if (fixture is Fixture) {
            white = extendedChannelFromStore(fixture, PropertyCategory.WHITE, store) ?: 0u
            amber = extendedChannelFromStore(fixture, PropertyCategory.AMBER, store) ?: 0u
            uv = extendedChannelFromStore(fixture, PropertyCategory.UV, store) ?: 0u
        }

        return FxOutput.Colour(
            ExtendedColour(Color(r.toInt(), g.toInt(), b.toInt()), white, amber, uv)
        )
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

    private fun setExtendedChannel(fixture: Fixture, category: PropertyCategory, value: UByte) {
        val prop = fixture.fixtureProperties.find { it.bundleWithColour && it.category == category } ?: return
        val slider = prop.classProperty.call(fixture) as? Slider ?: return
        slider.value = value
    }

    private fun extendedChannelFromStore(
        fixture: Fixture,
        category: PropertyCategory,
        store: DirectWriteStore,
    ): UByte? {
        val prop = fixture.fixtureProperties.find { it.bundleWithColour && it.category == category } ?: return null
        val dmx = (prop.classProperty.call(fixture) as? Slider) as? DmxSlider ?: return null
        return store.get(dmx.universe.universe, dmx.channelNo)
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

    override fun resetToFallback(fixture: GroupableFixture, fallback: FxOutput) {
        if (fallback !is FxOutput.Position) return
        val positionFixture = fixture as? WithPosition ?: return
        positionFixture.pan.value = fallback.pan
        positionFixture.tilt.value = fallback.tilt
    }

    override fun fallbackFromDirectWrites(fixture: GroupableFixture, store: DirectWriteStore): FxOutput {
        val positionFixture = fixture as? WithPosition ?: return FxOutput.Position(128u, 128u)
        val pan = (positionFixture.pan as? DmxSlider)
            ?.let { store.get(it.universe.universe, it.channelNo) } ?: 128u.toUByte()
        val tilt = (positionFixture.tilt as? DmxSlider)
            ?.let { store.get(it.universe.universe, it.channelNo) } ?: 128u.toUByte()
        return FxOutput.Position(pan, tilt)
    }

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

    override fun resetToFallback(fixture: GroupableFixture, fallback: FxOutput) {
        if (fallback !is FxOutput.Slider) return
        val setting = getSetting(fixture) ?: return
        val transaction = setting.transaction ?: return
        transaction.setValue(setting.universe, setting.channelNo, fallback.value)
    }

    override fun fallbackFromDirectWrites(fixture: GroupableFixture, store: DirectWriteStore): FxOutput {
        val setting = getSetting(fixture) ?: return FxOutput.Slider(0u)
        val sticky = store.get(setting.universe.universe, setting.channelNo) ?: 0u.toUByte()
        return FxOutput.Slider(sticky)
    }

    override fun fixtureHasProperty(fixture: GroupableFixture): Boolean {
        return getSetting(fixture) != null
    }

    override fun isPropertyFullyParked(fixture: GroupableFixture, parkManager: ParkManager): Boolean {
        val setting = getSetting(fixture) ?: return false
        return parkManager.isParked(setting.universe.universe, setting.channelNo)
    }

    private fun getSetting(fixture: GroupableFixture): DmxFixtureSetting<*>? {
        val f = fixture as? Fixture ?: return null
        val prop = f.fixtureProperties.find { it.name == propertyName } ?: return null
        return prop.classProperty.call(f) as? DmxFixtureSetting<*>
    }

    companion object {
        /** Create a setting target for a group */
        fun forGroup(groupName: String, propertyName: String) =
            SettingTarget(FxTargetRef.group(groupName), propertyName)
    }
}
