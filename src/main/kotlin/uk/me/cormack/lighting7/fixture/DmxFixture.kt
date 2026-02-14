package uk.me.cormack.lighting7.fixture

import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.dmx.DmxColour
import uk.me.cormack.lighting7.fixture.dmx.DmxFixtureColourSettingValue
import uk.me.cormack.lighting7.fixture.dmx.DmxFixtureSetting
import uk.me.cormack.lighting7.fixture.dmx.DmxSlider
import uk.me.cormack.lighting7.fixture.group.*
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
                            fixtureProperty.bundleWithColour,
                            fixtureProperty.compactDisplay
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
                        blueChannel = ChannelRef(universe.universe, value.blueSlider.channelNo),
                        compactDisplay = prop.compactDisplay.serialized()
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
                                max = value.max.toInt(),
                                compactDisplay = prop.compactDisplay.serialized()
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
                            },
                            compactDisplay = prop.compactDisplay.serialized()
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

    /**
     * Generates aggregated group property descriptors across all elements of a multi-element fixture.
     *
     * This allows the frontend to display "all heads" controls that operate on every element
     * simultaneously, using the same group property visualizers used for fixture groups.
     *
     * Only properties common to ALL elements (matched by name and type) are included.
     *
     * @return List of group property descriptors, or null if not a multi-element fixture or has fewer than 2 elements
     */
    fun generateElementGroupPropertyDescriptors(): List<GroupPropertyDescriptor>? {
        val elementDescriptors = generateElementDescriptors() ?: return null
        if (elementDescriptors.size < 2) return null

        val template = elementDescriptors.first()

        return template.properties.mapNotNull { templateProp ->
            val allMatching = elementDescriptors.map { elem ->
                elem.properties.find { it.name == templateProp.name && it::class == templateProp::class }
            }
            if (allMatching.any { it == null }) return@mapNotNull null

            @Suppress("UNCHECKED_CAST")
            when (templateProp) {
                is SliderPropertyDescriptor -> GroupSliderPropertyDescriptor(
                    name = templateProp.name,
                    displayName = templateProp.displayName,
                    category = templateProp.category,
                    min = templateProp.min,
                    max = templateProp.max,
                    memberChannels = allMatching.map { (it as SliderPropertyDescriptor).channel }
                )
                is ColourPropertyDescriptor -> GroupColourPropertyDescriptor(
                    name = templateProp.name,
                    displayName = templateProp.displayName,
                    memberColourChannels = elementDescriptors.mapIndexed { idx, elem ->
                        val p = allMatching[idx] as ColourPropertyDescriptor
                        MemberColourChannels(
                            fixtureKey = elem.key,
                            redChannel = p.redChannel,
                            greenChannel = p.greenChannel,
                            blueChannel = p.blueChannel,
                            whiteChannel = p.whiteChannel,
                            amberChannel = p.amberChannel,
                            uvChannel = p.uvChannel
                        )
                    }
                )
                is PositionPropertyDescriptor -> GroupPositionPropertyDescriptor(
                    name = templateProp.name,
                    displayName = templateProp.displayName,
                    memberPositionChannels = elementDescriptors.mapIndexed { idx, elem ->
                        val p = allMatching[idx] as PositionPropertyDescriptor
                        MemberPositionChannels(
                            fixtureKey = elem.key,
                            panChannel = p.panChannel,
                            tiltChannel = p.tiltChannel,
                            panMin = p.panMin,
                            panMax = p.panMax,
                            tiltMin = p.tiltMin,
                            tiltMax = p.tiltMax
                        )
                    }
                )
                is SettingPropertyDescriptor -> GroupSettingPropertyDescriptor(
                    name = templateProp.name,
                    displayName = templateProp.displayName,
                    category = templateProp.category,
                    options = templateProp.options,
                    memberChannels = elementDescriptors.mapIndexed { idx, _ ->
                        val p = allMatching[idx] as SettingPropertyDescriptor
                        MemberSettingChannel(fixtureKey = elementDescriptors[idx].key, channel = p.channel)
                    }
                )
            }
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
                            blueChannel = ChannelRef(universe.universe, value.blueSlider.channelNo),
                            compactDisplay = prop.compactDisplay.serialized()
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
                                max = value.max.toInt(),
                                compactDisplay = prop.compactDisplay.serialized()
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
                            },
                            compactDisplay = prop.compactDisplay.serialized()
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
