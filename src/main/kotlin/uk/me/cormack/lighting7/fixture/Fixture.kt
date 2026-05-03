package uk.me.cormack.lighting7.fixture

import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.fixture.trait.WithColour
import uk.me.cormack.lighting7.fixture.trait.WithDimmer
import java.awt.Color
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties

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
    ) {
        companion object {
            /**
             * Build a [Property] from a [@FixtureProperty] annotation. Used by
             * both the fixture-level reflection scan ([Fixture.fixtureProperties])
             * and the per-element reflection scans in [DmxFixture]. NaN sentinels
             * for the optional Double-valued annotation fields are converted to
             * null here in one place.
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
            )
        }
    }

    private val fixtureTypeAnnotation: FixtureType = this::class.annotations.filterIsInstance<FixtureType>().first()
    val typeKey: String = fixtureTypeAnnotation.typeKey
    val manufacturer: String = fixtureTypeAnnotation.manufacturer
    val model: String = fixtureTypeAnnotation.model
    val fixtureProperties: List<Property> = this::class.memberProperties.flatMap { classProperty ->
        classProperty.annotations.filterIsInstance<FixtureProperty>().map { fixtureProperty ->
            Property.fromAnnotation(classProperty, fixtureProperty)
        }
    }

    /** Look up a declared property by its reflection name; null if no such annotated property. */
    fun fixtureProperty(name: String): Property? =
        fixtureProperties.firstOrNull { it.name == name }

    open fun blackout() {
        if (this is WithDimmer) {
            this.dimmer.value = 0u
        }

        if (this is WithColour) {
            this.rgbColour.value = Color.BLACK
        }
    }
}
