package uk.me.cormack.lighting7.fixture

import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.fixture.dmx.DmxFixtureSetting
import uk.me.cormack.lighting7.fixture.trait.WithColour
import uk.me.cormack.lighting7.fixture.trait.WithDimmer
import java.awt.Color
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.full.isSubclassOf

sealed class Fixture(val key: String, val fixtureName: String) : GroupableFixture {

    // FixtureTarget implementation
    override val targetKey: String get() = key
    override val displayName: String get() = fixtureName
    override val isGroup: Boolean get() = false
    override val memberCount: Int get() = 1
    abstract override fun withTransaction(transaction: ControllerTransaction): Fixture

    data class Property(
        val classProperty: KProperty1<out Fixture, *>,
        val name: String,
        val description: String,
        val category: PropertyCategory,
        val composition: CompositionRule,
        val bundleWithColour: Boolean,
        val compactDisplay: CompactDisplayRole = CompactDisplayRole.NONE,
        val axis: PanTiltAxis = PanTiltAxis.NONE,
        val degMin: Double? = null,
        val degMax: Double? = null,
        val inverted: Boolean = false,
        val focusNearM: Double? = null,
        val focusFarM: Double? = null,
        val fineOf: String? = null,
        val rpmMax: Double? = null,
        val indexDegMax: Double? = null,
        val blade: Blade? = null,
        val depthMax: Double? = null,
        /**
         * Backed by a [DmxFixtureSetting] — a slot channel, whatever its category. A COLOUR
         * property that is a colour wheel or a gel scroller is one: its stored and programmer
         * values are slot levels, not colours, and
         * [CueAssignmentResolver.parseAssignmentValue][uk.me.cormack.lighting7.fx.CueAssignmentResolver.Companion.parseAssignmentValue]
         * must read them so.
         */
        val settingBacked: Boolean = false,
    ) {
        companion object {
            /**
             * Build a [Property] from a [@FixtureProperty] annotation. The single caller is
             * [FixturePropertyCatalogue], which is the one place that scans a class — fixture
             * or element — for annotated members. NaN sentinels for the optional Double-valued
             * annotation fields, the empty-string sentinel for [fineOf] and [Blade.NONE] for
             * [blade], are converted to null here in one place.
             */
            fun fromAnnotation(
                classProperty: KProperty1<out Fixture, *>,
                ann: FixtureProperty,
            ): Property = Property(
                classProperty,
                classProperty.name,
                ann.description,
                ann.category,
                ann.resolveComposition(),
                ann.bundleWithColour,
                ann.compactDisplay,
                ann.axis,
                ann.degMin.takeUnless { it.isNaN() },
                ann.degMax.takeUnless { it.isNaN() },
                ann.inverted,
                ann.focusNearM.takeUnless { it.isNaN() },
                ann.focusFarM.takeUnless { it.isNaN() },
                ann.fineOf.ifEmpty { null },
                ann.rpmMax.takeUnless { it.isNaN() },
                ann.indexDegMax.takeUnless { it.isNaN() },
                ann.blade.takeUnless { it == Blade.NONE },
                ann.depthMax.takeUnless { it.isNaN() },
                (classProperty.returnType.classifier as? KClass<*>)?.isSubclassOf(DmxFixtureSetting::class) == true,
            )
        }
    }

    /**
     * This class's `@FixtureProperty` / `@FixtureType` metadata, shared by every instance of it.
     *
     * Resolved through [FixturePropertyCatalogue] rather than scanned here, because the FX tick
     * rebuilds a fixture per transaction and this initializer therefore runs 50×/s per patched
     * fixture — see that object's KDoc.
     */
    private val catalogue = FixturePropertyCatalogue.of(this::class)

    private val fixtureTypeAnnotation: FixtureType = checkNotNull(catalogue.fixtureType) {
        "Fixture class ${this::class.qualifiedName} has no @FixtureType annotation"
    }
    val typeKey: String = fixtureTypeAnnotation.typeKey
    val manufacturer: String = fixtureTypeAnnotation.manufacturer
    val model: String = fixtureTypeAnnotation.model
    val fixtureProperties: List<Property> get() = catalogue.all

    /** Look up a declared property by its reflection name; null if no such annotated property. */
    fun fixtureProperty(name: String): Property? = catalogue.byName[name]

    /**
     * The `bundleWithColour` slider for [category] (WHITE / AMBER / UV), or null if this fixture
     * has none. Indexed rather than scanned: [ColourTarget][uk.me.cormack.lighting7.fx.ColourTarget]
     * asks for all three on every colour write, reset and park check.
     */
    internal fun bundledProperty(category: PropertyCategory): Property? =
        catalogue.bundledByCategory[category]

    /** This fixture's [PropertyCategory.COLOUR] property, if it declares one. */
    internal val colourProperty: Property? get() = catalogue.colour

    open fun blackout() {
        if (this is WithDimmer) {
            this.dimmer.value = 0u
        }

        if (this is WithColour) {
            this.rgbColour.value = Color.BLACK
        }
    }
}
