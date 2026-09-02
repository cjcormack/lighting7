package uk.me.cormack.lighting7.fixture.group

import uk.me.cormack.lighting7.fixture.DmxFixture
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fixture.GroupableFixture
import uk.me.cormack.lighting7.fixture.dmx.DmxFixtureSetting
import uk.me.cormack.lighting7.fixture.property.Slider
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

/**
 * Whether **every** one of [fixtures] supports [propertyName] — the per-property question
 * [detectCapabilities] answers a category at a time.
 *
 * One implementation for a group and for a single head, deliberately: a head is a group of one, and
 * a second copy of this trait dispatch would be a new way for the two answers to disagree.
 * `lightGroups.kt` asks it of a group before applying a group effect; the effect arm of a template
 * apply asks it of every target the click names, because [uk.me.cormack.lighting7.fx.FxTargetFactory]
 * never fails by design — `"rgbColour"` resolves to a `ColourTarget` whether or not the head has
 * colour, so without this check a head the effect cannot reach would get no light and no report.
 */
internal fun fixturesSupportProperty(
    fixtures: List<GroupableFixture>,
    propertyName: String,
): Boolean {
    if (fixtures.isEmpty()) return false

    val normalised = propertyName.lowercase()

    val directSupport = when (normalised) {
        "dimmer" -> fixtures.all { it is WithDimmer }
        "colour", "color", "rgbcolour" -> fixtures.all { it is WithColour }
        "position" -> fixtures.all { it is WithPosition }
        "uv" -> fixtures.all { it is WithUv }
        else -> {
            // Slider or setting property, by name.
            fixtures.all { fixture ->
                val prop = (fixture as? Fixture)?.fixtureProperty(propertyName)
                val value = prop?.classProperty?.call(fixture)
                value is Slider || value is DmxFixtureSetting<*>
            }
        }
    }
    if (directSupport) return true

    // Multi-element heads reach the same categories through their elements, so a bar whose
    // elements have colour supports `colour` even though the bar itself is not WithColour.
    val multiElementFixtures = fixtures.filterIsInstance<MultiElementFixture<*>>()
    if (multiElementFixtures.size != fixtures.size) return false
    if (multiElementFixtures.isEmpty()) return false

    return multiElementFixtures.all { mef ->
        val firstElement = mef.elements.firstOrNull() ?: return@all false
        when (normalised) {
            "dimmer" -> firstElement is WithDimmer
            "colour", "color", "rgbcolour" -> firstElement is WithColour
            "position" -> firstElement is WithPosition
            "uv" -> firstElement is WithUv
            else -> false
        }
    }
}
