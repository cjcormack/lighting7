package uk.me.cormack.lighting7.fixture

import uk.me.cormack.lighting7.dmx.DmxController
import uk.me.cormack.lighting7.fixture.dmx.DmxFixtureMultiSlider
import uk.me.cormack.lighting7.fixture.dmx.DmxFixtureSetting
import uk.me.cormack.lighting7.fixture.dmx.DmxFixtureSlider

abstract class DmxFixture(
    val controller: DmxController,
    val firstChannel: Int,
    val channelCount: Int,
    key: String,
    fixtureName: String,
    position: Int
): Fixture(key, fixtureName, position) {
    fun channelDescriptions(): Map<Int, String> {
        val channelDescriptions =  fixtureProperties.map {
            val name = if (it.description == "") {
                it.name
            } else {
                it.description
            }

            when (val property = checkNotNull(it.classProperty.call(this))) {
                is DmxFixtureSlider -> listOf(property.channelNo to name)
                is DmxFixtureSetting<*> -> listOf(property.channelNo to name)
                is DmxFixtureMultiSlider -> property.sliders.map { slider ->
                    slider.value.channelNo to slider.key
                }

                else -> throw Error("Unsupported property type ${property::class}")
            }
        }.flatten().toMap()

        return firstChannel.rangeUntil(firstChannel + channelCount).associateWith {
            channelDescriptions[it].orEmpty()
        }
    }
}
