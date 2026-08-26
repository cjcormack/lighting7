package uk.me.cormack.lighting7.fixture.group

import uk.me.cormack.lighting7.fixture.DmxFixture
import uk.me.cormack.lighting7.fixture.trait.WithColour
import uk.me.cormack.lighting7.fixture.trait.WithDimmer
import uk.me.cormack.lighting7.fixture.trait.WithPosition
import uk.me.cormack.lighting7.fixture.trait.WithStrobe
import uk.me.cormack.lighting7.fixture.trait.WithUv

/**
 * The property categories every member of a group supports, as the coarse `dimmer` / `colour` /
 * `position` / `uv` / `strobe` vocabulary the authoring surfaces speak.
 *
 * Lives beside [FixtureGroup] rather than in `routes/` because effect spawning needs it too:
 * [uk.me.cormack.lighting7.fx.EffectSpawner] asks a group what it can do when a stored spec names
 * no property of its own, and the AI tool surface asks the same question. A capability is a fact
 * about the fixtures, not about a REST response.
 */
internal fun FixtureGroup<*>.detectCapabilities(): List<String> {
    val allFixtures = fixtures  // Uses allMembers (includes subgroups)
    if (allFixtures.isEmpty()) return emptyList()

    val capabilities = mutableListOf<String>()

    if (allFixtures.all { it is WithDimmer }) {
        capabilities.add("dimmer")
    }
    if (allFixtures.all { it is WithColour }) {
        capabilities.add("colour")
    }
    if (allFixtures.all { it is WithPosition }) {
        capabilities.add("position")
    }
    if (allFixtures.all { it is WithUv }) {
        capabilities.add("uv")
    }
    if (allFixtures.all { it is WithStrobe }) {
        capabilities.add("strobe")
    }

    // Also detect capabilities available via element group properties on multi-head DmxFixtures
    val dmxFixtures = allFixtures.filterIsInstance<DmxFixture>()
    if (dmxFixtures.size == allFixtures.size && dmxFixtures.isNotEmpty()) {
        val allEgp = dmxFixtures.map { it.generateElementGroupPropertyDescriptors() }
        if (allEgp.all { it != null }) {
            val egpList = allEgp.filterNotNull()
            if ("dimmer" !in capabilities && egpList.all { egp -> egp.any { it is GroupSliderPropertyDescriptor && it.category == "dimmer" } }) {
                capabilities.add("dimmer")
            }
            if ("colour" !in capabilities && egpList.all { egp -> egp.any { it is GroupColourPropertyDescriptor } }) {
                capabilities.add("colour")
            }
            if ("position" !in capabilities && egpList.all { egp -> egp.any { it is GroupPositionPropertyDescriptor } }) {
                capabilities.add("position")
            }
        }
    }

    return capabilities
}
