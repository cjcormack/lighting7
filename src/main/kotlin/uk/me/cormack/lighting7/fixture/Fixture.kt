package uk.me.cormack.lighting7.fixture

import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.fixture.trait.WithColour
import uk.me.cormack.lighting7.fixture.trait.WithDimmer
import java.awt.Color
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties

sealed class Fixture(val key: String, val fixtureName: String) : FixtureTarget {

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
        val bundleWithColour: Boolean,
    )

    private val fixtureTypeAnnotation: FixtureType = this::class.annotations.filterIsInstance<FixtureType>().first()
    val typeKey: String = fixtureTypeAnnotation.typeKey
    val manufacturer: String = fixtureTypeAnnotation.manufacturer
    val model: String = fixtureTypeAnnotation.model
    val fixtureProperties: List<Property> = this::class.memberProperties.map { classProperty ->
        classProperty.annotations.filterIsInstance<FixtureProperty>().map { fixtureProperty ->
            Property(
                classProperty,
                classProperty.name,
                fixtureProperty.description,
                fixtureProperty.category,
                fixtureProperty.bundleWithColour
            )
        }
    }.flatten()

    open fun blackout() {
        if (this is WithDimmer) {
            this.dimmer.value = 0u
        }

        if (this is WithColour) {
            this.rgbColour.value = Color.BLACK
        }
    }
}
