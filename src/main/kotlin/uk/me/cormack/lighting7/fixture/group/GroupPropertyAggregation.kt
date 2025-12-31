package uk.me.cormack.lighting7.fixture.group

import uk.me.cormack.lighting7.fixture.*
import uk.me.cormack.lighting7.fixture.dmx.DmxFixtureColour
import uk.me.cormack.lighting7.fixture.dmx.DmxFixtureColourSettingValue
import uk.me.cormack.lighting7.fixture.dmx.DmxFixtureSetting
import uk.me.cormack.lighting7.fixture.dmx.DmxFixtureSlider
import uk.me.cormack.lighting7.routes.*

/**
 * Generates aggregated property descriptors for a fixture group.
 *
 * This function collects property information from all group members and creates
 * descriptors that include channel references for every member. This allows the
 * frontend to:
 * - Read values from all member channels and compute min/max/uniform status
 * - Update all member channels when editing a group property
 *
 * Only properties that are common to ALL group members are included. Properties
 * are matched by name and type.
 *
 * @return List of group property descriptors, or empty list if group is empty or has no DMX fixtures
 */
fun FixtureGroup<*>.generateGroupPropertyDescriptors(): List<GroupPropertyDescriptor> {
    if (fixtures.isEmpty()) return emptyList()

    // Only support DMX fixtures for now
    val dmxFixtures = fixtures.filterIsInstance<DmxFixture>()
    if (dmxFixtures.isEmpty()) return emptyList()

    val firstFixture = dmxFixtures.first()
    val templateProperties = firstFixture.generatePropertyDescriptors()

    return templateProperties.mapNotNull { templateProp ->
        when (templateProp) {
            is SliderPropertyDescriptor -> aggregateSliderProperty(templateProp, dmxFixtures)
            is ColourPropertyDescriptor -> aggregateColourProperty(templateProp, dmxFixtures)
            is PositionPropertyDescriptor -> aggregatePositionProperty(templateProp, dmxFixtures)
            is SettingPropertyDescriptor -> aggregateSettingProperty(templateProp, dmxFixtures)
        }
    }
}

/**
 * Aggregate slider property across all fixtures.
 */
private fun aggregateSliderProperty(
    template: SliderPropertyDescriptor,
    fixtures: List<DmxFixture>
): GroupSliderPropertyDescriptor? {
    val memberChannels = fixtures.mapNotNull { fixture ->
        val props = fixture.generatePropertyDescriptors()
        val matching = props.filterIsInstance<SliderPropertyDescriptor>()
            .find { it.name == template.name }
        matching?.channel
    }

    // Only include if ALL fixtures have this property
    if (memberChannels.size != fixtures.size) return null

    return GroupSliderPropertyDescriptor(
        name = template.name,
        displayName = template.displayName,
        category = template.category,
        min = template.min,
        max = template.max,
        memberChannels = memberChannels
    )
}

/**
 * Aggregate colour property across all fixtures.
 */
private fun aggregateColourProperty(
    template: ColourPropertyDescriptor,
    fixtures: List<DmxFixture>
): GroupColourPropertyDescriptor? {
    val memberColourChannels = fixtures.mapNotNull { fixture ->
        val props = fixture.generatePropertyDescriptors()
        val matching = props.filterIsInstance<ColourPropertyDescriptor>()
            .find { it.name == template.name }

        matching?.let {
            MemberColourChannels(
                fixtureKey = fixture.key,
                redChannel = it.redChannel,
                greenChannel = it.greenChannel,
                blueChannel = it.blueChannel,
                whiteChannel = it.whiteChannel,
                amberChannel = it.amberChannel,
                uvChannel = it.uvChannel
            )
        }
    }

    // Only include if ALL fixtures have this property
    if (memberColourChannels.size != fixtures.size) return null

    return GroupColourPropertyDescriptor(
        name = template.name,
        displayName = template.displayName,
        memberColourChannels = memberColourChannels
    )
}

/**
 * Aggregate position property across all fixtures.
 */
private fun aggregatePositionProperty(
    template: PositionPropertyDescriptor,
    fixtures: List<DmxFixture>
): GroupPositionPropertyDescriptor? {
    val memberPositionChannels = fixtures.mapNotNull { fixture ->
        val props = fixture.generatePropertyDescriptors()
        val matching = props.filterIsInstance<PositionPropertyDescriptor>()
            .find { it.name == template.name }

        matching?.let {
            MemberPositionChannels(
                fixtureKey = fixture.key,
                panChannel = it.panChannel,
                tiltChannel = it.tiltChannel,
                panMin = it.panMin,
                panMax = it.panMax,
                tiltMin = it.tiltMin,
                tiltMax = it.tiltMax
            )
        }
    }

    // Only include if ALL fixtures have this property
    if (memberPositionChannels.size != fixtures.size) return null

    return GroupPositionPropertyDescriptor(
        name = template.name,
        displayName = template.displayName,
        memberPositionChannels = memberPositionChannels
    )
}

/**
 * Aggregate setting property across all fixtures.
 *
 * Settings are only aggregated if ALL fixtures have the same options.
 * This ensures the dropdown will work correctly for all members.
 */
private fun aggregateSettingProperty(
    template: SettingPropertyDescriptor,
    fixtures: List<DmxFixture>
): GroupSettingPropertyDescriptor? {
    val memberChannels = fixtures.mapNotNull { fixture ->
        val props = fixture.generatePropertyDescriptors()
        val matching = props.filterIsInstance<SettingPropertyDescriptor>()
            .find { it.name == template.name }

        // Verify options match (same levels and names)
        if (matching != null && optionsMatch(template.options, matching.options)) {
            MemberSettingChannel(
                fixtureKey = fixture.key,
                channel = matching.channel
            )
        } else {
            null
        }
    }

    // Only include if ALL fixtures have this property with matching options
    if (memberChannels.size != fixtures.size) return null

    return GroupSettingPropertyDescriptor(
        name = template.name,
        displayName = template.displayName,
        category = template.category,
        options = template.options,
        memberChannels = memberChannels
    )
}

/**
 * Check if two option lists are compatible for group editing.
 */
private fun optionsMatch(a: List<SettingOption>, b: List<SettingOption>): Boolean {
    if (a.size != b.size) return false
    return a.zip(b).all { (optA, optB) ->
        optA.name == optB.name && optA.level == optB.level
    }
}
