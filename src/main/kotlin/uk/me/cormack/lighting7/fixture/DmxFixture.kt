package uk.me.cormack.lighting7.fixture

import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.dmx.DmxColour
import uk.me.cormack.lighting7.fixture.dmx.DmxFixtureColourSettingValue
import uk.me.cormack.lighting7.fixture.dmx.DmxFixtureSetting
import uk.me.cormack.lighting7.fixture.dmx.DmxSlider
import uk.me.cormack.lighting7.fixture.group.FixtureElement
import uk.me.cormack.lighting7.fixture.group.MultiElementFixture
import uk.me.cormack.lighting7.fixture.trait.WithPosition
import uk.me.cormack.lighting7.routes.*
import kotlin.reflect.full.memberProperties

abstract class DmxFixture(
    val universe: Universe,
    val firstChannel: Int,
    val channelCount: Int,
    key: String,
    fixtureName: String,
): Fixture(key, fixtureName) {
    fun channelDescriptions(): Map<Int, String> {
        val channelDescriptions = mutableMapOf<Int, String>()

        // Add fixture-level properties
        fixtureProperties.forEach {
            val name = if (it.description == "") {
                it.name
            } else {
                it.description
            }

            when (val property = checkNotNull(it.classProperty.call(this))) {
                is DmxSlider -> channelDescriptions[property.channelNo] = name
                is DmxColour -> {
                    channelDescriptions[property.redSlider.channelNo] = "$name Red"
                    channelDescriptions[property.greenSlider.channelNo] = "$name Green"
                    channelDescriptions[property.blueSlider.channelNo] = "$name Blue"
                }
                is DmxFixtureSetting<*> -> channelDescriptions[property.channelNo] = name

                else -> throw Error("Unsupported property type ${property::class}")
            }
        }

        // Add element properties for multi-element fixtures
        if (this is MultiElementFixture<*>) {
            for (element in elements) {
                val elementPrefix = "Head ${element.elementIndex + 1}"

                val elementProperties = element::class.memberProperties.flatMap { classProperty ->
                    classProperty.annotations.filterIsInstance<FixtureProperty>().map { fixtureProperty ->
                        Fixture.Property(
                            classProperty as kotlin.reflect.KProperty1<out Fixture, *>,
                            classProperty.name,
                            fixtureProperty.description,
                            fixtureProperty.category,
                            fixtureProperty.bundleWithColour
                        )
                    }
                }

                for (prop in elementProperties) {
                    @Suppress("UNCHECKED_CAST")
                    val propTyped = prop.classProperty as kotlin.reflect.KProperty1<Any, *>
                    val value = propTyped.call(element) ?: continue
                    val propName = if (prop.description.isEmpty()) {
                        prop.name.replaceFirstChar { it.uppercase() }
                    } else {
                        // Strip "Head " prefix to avoid redundant "Head 1 Head ..." descriptions
                        prop.description.removePrefix("Head ")
                    }
                    val name = "$elementPrefix $propName"

                    when (value) {
                        is DmxSlider -> channelDescriptions[value.channelNo] = name
                        is DmxColour -> {
                            channelDescriptions[value.redSlider.channelNo] = "$elementPrefix Red"
                            channelDescriptions[value.greenSlider.channelNo] = "$elementPrefix Green"
                            channelDescriptions[value.blueSlider.channelNo] = "$elementPrefix Blue"
                        }
                        is DmxFixtureSetting<*> -> channelDescriptions[value.channelNo] = name
                    }
                }
            }
        }

        return firstChannel.rangeUntil(firstChannel + channelCount).associateWith {
            channelDescriptions[it].orEmpty()
        }
    }

    fun generatePropertyDescriptors(): List<PropertyDescriptor> {
        // First pass: collect all properties
        val descriptors = mutableListOf<PropertyDescriptor>()
        var colourDescriptor: ColourPropertyDescriptor? = null

        // Track extended colour channels for bundling (marked with bundleWithColour = true)
        var whiteChannel: ChannelRef? = null
        var amberChannel: ChannelRef? = null
        var uvChannel: ChannelRef? = null

        for (prop in fixtureProperties) {
            val value = prop.classProperty.call(this) ?: continue
            val displayName = prop.description.ifEmpty { prop.name.formatPropertyName() }

            when (value) {
                is DmxColour -> {
                    // Create base colour descriptor, will add extended channels after
                    colourDescriptor = ColourPropertyDescriptor(
                        name = prop.name,
                        displayName = displayName,
                        redChannel = ChannelRef(universe.universe, value.redSlider.channelNo),
                        greenChannel = ChannelRef(universe.universe, value.greenSlider.channelNo),
                        blueChannel = ChannelRef(universe.universe, value.blueSlider.channelNo)
                    )
                }
                is DmxSlider -> {
                    // Check if this is an extended colour channel to bundle
                    if (prop.bundleWithColour) {
                        when (prop.category) {
                            PropertyCategory.WHITE -> whiteChannel = ChannelRef(universe.universe, value.channelNo)
                            PropertyCategory.AMBER -> amberChannel = ChannelRef(universe.universe, value.channelNo)
                            PropertyCategory.UV -> uvChannel = ChannelRef(universe.universe, value.channelNo)
                            else -> {} // Ignore other bundleWithColour sliders
                        }
                    } else {
                        descriptors.add(
                            SliderPropertyDescriptor(
                                name = prop.name,
                                displayName = displayName,
                                category = prop.category.name.lowercase(),
                                channel = ChannelRef(universe.universe, value.channelNo),
                                min = value.min.toInt(),
                                max = value.max.toInt()
                            )
                        )
                    }
                }
                is DmxFixtureSetting<*> -> {
                    descriptors.add(
                        SettingPropertyDescriptor(
                            name = prop.name,
                            displayName = displayName,
                            category = prop.category.name.lowercase(),
                            channel = ChannelRef(universe.universe, value.channelNo),
                            options = value.sortedValues.map { settingValue ->
                                SettingOption(
                                    name = settingValue.name,
                                    level = settingValue.level.toInt(),
                                    displayName = settingValue.name.formatSettingName(),
                                    colourPreview = (settingValue as? DmxFixtureColourSettingValue)?.colourPreview
                                )
                            }
                        )
                    )
                }
            }
        }

        // Bundle extended colour channels into the colour descriptor
        colourDescriptor?.let { colour ->
            descriptors.add(0, colour.copy(
                whiteChannel = whiteChannel,
                amberChannel = amberChannel,
                uvChannel = uvChannel
            ))
        }

        // Add position descriptor if fixture has pan and tilt
        if (this is WithPosition) {
            val panSlider = this.pan
            val tiltSlider = this.tilt
            if (panSlider is DmxSlider && tiltSlider is DmxSlider) {
                descriptors.add(
                    PositionPropertyDescriptor(
                        name = "position",
                        displayName = "Position",
                        panChannel = ChannelRef(universe.universe, panSlider.channelNo),
                        tiltChannel = ChannelRef(universe.universe, tiltSlider.channelNo),
                        panMin = panSlider.min.toInt(),
                        panMax = panSlider.max.toInt(),
                        tiltMin = tiltSlider.min.toInt(),
                        tiltMax = tiltSlider.max.toInt()
                    )
                )
            }
        }

        return descriptors
    }

    fun generateElementDescriptors(): List<ElementDescriptor>? {
        if (this !is MultiElementFixture<*>) return null

        return elements.map { element ->
            val properties = extractElementProperties(element)
            ElementDescriptor(
                index = element.elementIndex,
                key = element.elementKey,
                displayName = "Head ${element.elementIndex + 1}",
                properties = properties
            )
        }
    }

    private fun extractElementProperties(element: FixtureElement<*>): List<PropertyDescriptor> {
        val descriptors = mutableListOf<PropertyDescriptor>()

        // Get all properties from the element class with @FixtureProperty annotation
        val elementProperties = element::class.memberProperties.flatMap { classProperty ->
            classProperty.annotations.filterIsInstance<FixtureProperty>().map { fixtureProperty ->
                Fixture.Property(
                    classProperty as kotlin.reflect.KProperty1<out Fixture, *>,
                    classProperty.name,
                    fixtureProperty.description,
                    fixtureProperty.category,
                    fixtureProperty.bundleWithColour
                )
            }
        }

        for (prop in elementProperties) {
            @Suppress("UNCHECKED_CAST")
            val propTyped = prop.classProperty as kotlin.reflect.KProperty1<Any, *>
            val value = propTyped.call(element) ?: continue
            val displayName = prop.description.ifEmpty { prop.name.formatPropertyName() }

            when (value) {
                is DmxColour -> {
                    descriptors.add(
                        ColourPropertyDescriptor(
                            name = prop.name,
                            displayName = displayName,
                            redChannel = ChannelRef(universe.universe, value.redSlider.channelNo),
                            greenChannel = ChannelRef(universe.universe, value.greenSlider.channelNo),
                            blueChannel = ChannelRef(universe.universe, value.blueSlider.channelNo)
                        )
                    )
                }
                is DmxSlider -> {
                    // Skip bundled colour channels in element properties
                    if (!prop.bundleWithColour) {
                        descriptors.add(
                            SliderPropertyDescriptor(
                                name = prop.name,
                                displayName = displayName,
                                category = prop.category.name.lowercase(),
                                channel = ChannelRef(universe.universe, value.channelNo),
                                min = value.min.toInt(),
                                max = value.max.toInt()
                            )
                        )
                    }
                }
                is DmxFixtureSetting<*> -> {
                    descriptors.add(
                        SettingPropertyDescriptor(
                            name = prop.name,
                            displayName = displayName,
                            category = prop.category.name.lowercase(),
                            channel = ChannelRef(universe.universe, value.channelNo),
                            options = value.sortedValues.map { settingValue ->
                                SettingOption(
                                    name = settingValue.name,
                                    level = settingValue.level.toInt(),
                                    displayName = settingValue.name.formatSettingName(),
                                    colourPreview = (settingValue as? DmxFixtureColourSettingValue)?.colourPreview
                                )
                            }
                        )
                    )
                }
            }
        }

        // Add position descriptor if element has pan and tilt
        if (element is WithPosition) {
            val panSlider = element.pan
            val tiltSlider = element.tilt
            if (panSlider is DmxSlider && tiltSlider is DmxSlider) {
                descriptors.add(
                    PositionPropertyDescriptor(
                        name = "position",
                        displayName = "Position",
                        panChannel = ChannelRef(universe.universe, panSlider.channelNo),
                        tiltChannel = ChannelRef(universe.universe, tiltSlider.channelNo),
                        panMin = panSlider.min.toInt(),
                        panMax = panSlider.max.toInt(),
                        tiltMin = tiltSlider.min.toInt(),
                        tiltMax = tiltSlider.max.toInt()
                    )
                )
            }
        }

        return descriptors
    }

    private fun String.formatPropertyName(): String {
        return this.replace(Regex("([a-z])([A-Z])"), "$1 $2")
            .replaceFirstChar { it.uppercase() }
    }

    private fun String.formatSettingName(): String {
        return this.replace("_", " ")
            .lowercase()
            .split(" ")
            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
    }
}
