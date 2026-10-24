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
fun FixtureGroup<*>.generateGroupAttributeDescriptors(): List<GroupAttributeDescriptor> {
    if (fixtures.isEmpty()) return emptyList()

    // Only support DMX fixtures for now
    val dmxFixtures = fixtures.filterIsInstance<DmxFixture>()
    if (dmxFixtures.isEmpty()) return emptyList()

    val result = mutableListOf<GroupAttributeDescriptor>()

    // Aggregate fixture-level properties
    val firstFixture = dmxFixtures.first()
    val templateAttributes = firstFixture.generateAttributeDescriptors()

    for (templateProp in templateAttributes) {
        val aggregated = when (templateProp) {
            is SliderAttributeDescriptor -> aggregateSliderAttribute(templateProp, dmxFixtures)
            is ColourAttributeDescriptor -> aggregateColourAttribute(templateProp, dmxFixtures)
            is PositionAttributeDescriptor -> aggregatePositionAttribute(templateProp, dmxFixtures)
            is SettingAttributeDescriptor -> aggregateSettingAttribute(templateProp, dmxFixtures)
            is WhiteMixAttributeDescriptor -> aggregateWhiteMixAttribute(templateProp, dmxFixtures)
            // A trigger fires per fixture, from its own panel: a group has no trigger control.
            is TriggerAttributeDescriptor -> null
            // A command runs per fixture, from its own panel and behind its own confirm.
            is CommandAttributeDescriptor -> null
        }
        if (aggregated != null) result.add(aggregated)
    }

    // Aggregate element group properties (virtual "all heads" properties from multi-head fixtures)
    val allEgp = dmxFixtures.map { it.generateElementGroupAttributeDescriptors() }
    if (allEgp.all { it != null } && allEgp.isNotEmpty()) {
        val egpLists = allEgp.filterNotNull()
        val templateEgp = egpLists.first()

        for (templateProp in templateEgp) {
            // Skip if fixture-level aggregation already covered this property name+type
            if (result.any { it.name == templateProp.name && it::class == templateProp::class }) continue

            val aggregated = aggregateElementGroupAttribute(templateProp, egpLists)
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
private fun aggregateElementGroupAttribute(
    template: GroupAttributeDescriptor,
    allEgpLists: List<List<GroupAttributeDescriptor>>
): GroupAttributeDescriptor? {
    // Find matching property in each fixture's element group properties
    val allMatching = allEgpLists.map { egpList ->
        egpList.find { it.name == template.name && it::class == template::class }
    }
    if (allMatching.any { it == null }) return null

    return when (template) {
        is GroupSliderAttributeDescriptor -> {
            val allChannels = allMatching.flatMap { (it as GroupSliderAttributeDescriptor).memberChannels }
            GroupSliderAttributeDescriptor(
                name = template.name,
                displayName = template.displayName,
                category = template.category,
                min = template.min,
                max = template.max,
                memberChannels = allChannels
            )
        }
        is GroupColourAttributeDescriptor -> {
            val allChannels = allMatching.flatMap { (it as GroupColourAttributeDescriptor).memberColourChannels }
            GroupColourAttributeDescriptor(
                name = template.name,
                displayName = template.displayName,
                memberColourChannels = allChannels
            )
        }
        is GroupPositionAttributeDescriptor -> {
            val allChannels = allMatching.flatMap { (it as GroupPositionAttributeDescriptor).memberPositionChannels }
            GroupPositionAttributeDescriptor(
                name = template.name,
                displayName = template.displayName,
                memberPositionChannels = allChannels
            )
        }
        is GroupWhiteMixAttributeDescriptor -> {
            val matching = allMatching.filterNotNull().filterIsInstance<GroupWhiteMixAttributeDescriptor>()
            if (matching.any { it.warmKelvin != template.warmKelvin || it.coldKelvin != template.coldKelvin }) return null
            template.copy(memberChannels = matching.flatMap { it.memberChannels })
        }
        is GroupSettingAttributeDescriptor -> {
            val matching = allMatching.filterNotNull().filterIsInstance<GroupSettingAttributeDescriptor>()
            // Verify all have matching options
            if (matching.any { !optionsMatch(template.options, it.options) }) return null
            val allChannels = matching.flatMap { it.memberChannels }
            GroupSettingAttributeDescriptor(
                name = template.name,
                displayName = template.displayName,
                category = template.category,
                options = template.options,
                memberChannels = allChannels
            )
        }
    }
}

/** A warm/cold white pair across all fixtures, when every one has it with the same whites. */
private fun aggregateWhiteMixAttribute(
    template: WhiteMixAttributeDescriptor,
    fixtures: List<DmxFixture>,
): GroupWhiteMixAttributeDescriptor? {
    val members = fixtures.mapNotNull { fixture ->
        fixture.generateAttributeDescriptors().filterIsInstance<WhiteMixAttributeDescriptor>()
            .find { it.name == template.name && it.warmKelvin == template.warmKelvin && it.coldKelvin == template.coldKelvin }
            ?.let { MemberWhiteMixChannels(fixture.key, it.warmChannel, it.coldChannel) }
    }
    if (members.size != fixtures.size) return null
    return GroupWhiteMixAttributeDescriptor(template.name, template.displayName, template.warmKelvin, template.coldKelvin, members)
}

/**
 * Aggregate slider property across all fixtures.
 */
private fun aggregateSliderAttribute(
    template: SliderAttributeDescriptor,
    fixtures: List<DmxFixture>
): GroupSliderAttributeDescriptor? {
    val memberChannels = fixtures.mapNotNull { fixture ->
        val props = fixture.generateAttributeDescriptors()
        val matching = props.filterIsInstance<SliderAttributeDescriptor>()
            .find { it.name == template.name }
        matching?.channel
    }

    // Only include if ALL fixtures have this property
    if (memberChannels.size != fixtures.size) return null

    return GroupSliderAttributeDescriptor(
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
private fun aggregateColourAttribute(
    template: ColourAttributeDescriptor,
    fixtures: List<DmxFixture>
): GroupColourAttributeDescriptor? {
    val memberColourChannels = fixtures.mapNotNull { fixture ->
        val props = fixture.generateAttributeDescriptors()
        val matching = props.filterIsInstance<ColourAttributeDescriptor>()
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

    return GroupColourAttributeDescriptor(
        name = template.name,
        displayName = template.displayName,
        memberColourChannels = memberColourChannels
    )
}

/**
 * Aggregate position property across all fixtures.
 */
private fun aggregatePositionAttribute(
    template: PositionAttributeDescriptor,
    fixtures: List<DmxFixture>
): GroupPositionAttributeDescriptor? {
    val memberPositionChannels = fixtures.mapNotNull { fixture ->
        val props = fixture.generateAttributeDescriptors()
        val matching = props.filterIsInstance<PositionAttributeDescriptor>()
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

    return GroupPositionAttributeDescriptor(
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
private fun aggregateSettingAttribute(
    template: SettingAttributeDescriptor,
    fixtures: List<DmxFixture>
): GroupSettingAttributeDescriptor? {
    val memberChannels = fixtures.mapNotNull { fixture ->
        val props = fixture.generateAttributeDescriptors()
        val matching = props.filterIsInstance<SettingAttributeDescriptor>()
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

    return GroupSettingAttributeDescriptor(
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
