package uk.me.cormack.lighting7.fixture

import uk.me.cormack.lighting7.dmx.ControllerTransaction
import java.awt.Color
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties

sealed class Fixture(val key: String, val fixtureName: String, val position: Int) {
    abstract fun withTransaction(transaction: ControllerTransaction): Fixture

    data class Property(
        val classProperty: KProperty1<out Fixture, *>,
        val name: String,
        val description: String,
    )

    val typeKey: String = this::class.annotations.filterIsInstance<FixtureType>().first().typeKey
    val fixtureProperties: List<Property> = this::class.memberProperties.map { classProperty ->
        classProperty.annotations.filterIsInstance<FixtureProperty>().map { fixtureProperty ->
            Property(classProperty, classProperty.name, fixtureProperty.description)
        }
    }.flatten()

    open fun blackout() {
        if (this is FixtureWithDimmer) {
            this.dimmer.value = 0u
        }

        if (this is FixtureWithColour<*>) {
            this.rgbColour.value = Color.BLACK
        }
    }
}
