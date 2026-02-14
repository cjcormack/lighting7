package uk.me.cormack.lighting7.fixture.group

import uk.me.cormack.lighting7.fixture.*
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
 * For multi-element fixtures, element group properties (virtual "all heads" properties)
 * are also included if all group members have matching element group properties.
 *
 * @return List of group property descriptors, or empty list if group is empty or has no DMX fixtures
 */
fun FixtureGroup<*>.generateGroupPropertyDescriptors(): List<GroupPropertyDescriptor> {
    if (fixtures.isEmpty()) return emptyList()

    // Only support DMX fixtures for now
    val dmxFixtures = fixtures.filterIsInstance<DmxFixture>()
    if (dmxFixtures.isEmpty()) return emptyList()

    val result = mutableListOf<GroupPropertyDescriptor>()

    // Aggregate fixture-level properties
    val firstFixture = dmxFixtures.first()
    val templateProperties = firstFixture.generatePropertyDescriptors()

    for (templateProp in templateProperties) {
        val aggregated = when (templateProp) {
            is SliderPropertyDescriptor -> aggregateSliderProperty(templateProp, dmxFixtures)
            is ColourPropertyDescriptor -> aggregateColourProperty(templateProp, dmxFixtures)
            is PositionPropertyDescriptor -> aggregatePositionProperty(templateProp, dmxFixtures)
            is SettingPropertyDescriptor -> aggregateSettingProperty(templateProp, dmxFixtures)
        }
        if (aggregated != null) result.add(aggregated)
    }

    // Aggregate element group properties (virtual "all heads" properties from multi-head fixtures)
    val allEgp = dmxFixtures.map { it.generateElementGroupPropertyDescriptors() }
    if (allEgp.all { it != null } && allEgp.isNotEmpty()) {
        val egpLists = allEgp.filterNotNull()
        val templateEgp = egpLists.first()

        for (templateProp in templateEgp) {
            // Skip if fixture-level aggregation already covered this property name+type
            if (result.any { it.name == templateProp.name && it::class == templateProp::class }) continue

            val aggregated = aggregateElementGroupProperty(templateProp, egpLists)
            if (aggregated != null) result.add(aggregated)
        }
    }

    return result
}

/**
 * Aggregate a single element group property across all fixtures' element group properties.
 *
 * This merges the member channels from each fixture's version of the property into
 * a single group property descriptor that covers all elements across all fixtures.
 */
private fun aggregateElementGroupProperty(
    template: GroupPropertyDescriptor,
    allEgpLists: List<List<GroupPropertyDescriptor>>
): GroupPropertyDescriptor? {
    // Find matching property in each fixture's element group properties
    val allMatching = allEgpLists.map { egpList ->
        egpList.find { it.name == template.name && it::class == template::class }
    }
    if (allMatching.any { it == null }) return null

    return when (template) {
        is GroupSliderPropertyDescriptor -> {
            val allChannels = allMatching.flatMap { (it as GroupSliderPropertyDescriptor).memberChannels }
            GroupSliderPropertyDescriptor(
                name = template.name,
                displayName = template.displayName,
                category = template.category,
                min = template.min,
                max = template.max,
                memberChannels = allChannels
            )
        }
        is GroupColourPropertyDescriptor -> {
            val allChannels = allMatching.flatMap { (it as GroupColourPropertyDescriptor).memberColourChannels }
            GroupColourPropertyDescriptor(
                name = template.name,
                displayName = template.displayName,
                memberColourChannels = allChannels
            )
        }
        is GroupPositionPropertyDescriptor -> {
            val allChannels = allMatching.flatMap { (it as GroupPositionPropertyDescriptor).memberPositionChannels }
            GroupPositionPropertyDescriptor(
                name = template.name,
                displayName = template.displayName,
                memberPositionChannels = allChannels
            )
        }
        is GroupSettingPropertyDescriptor -> {
            val matching = allMatching.filterNotNull().filterIsInstance<GroupSettingPropertyDescriptor>()
            // Verify all have matching options
            if (matching.any { !optionsMatch(template.options, it.options) }) return null
            val allChannels = matching.flatMap { it.memberChannels }
            GroupSettingPropertyDescriptor(
                name = template.name,
                displayName = template.displayName,
                category = template.category,
                options = template.options,
                memberChannels = allChannels
            )
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
