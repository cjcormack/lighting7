package uk.me.cormack.lighting7.fixture

import uk.me.cormack.lighting7.dmx.Universe
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A `bundleWithColour` emitter is named by its category — `white`, `amber`, `uv` — on every
 * fixture type. Two things already lean on this without saying so: `FxTarget.getSlider` reaches
 * `WithWhite.white` / `WithUv.uv` by those names, and the surface library in `lighting-react`
 * offers the emitters as faders by deriving the name from the colour descriptor's
 * `whiteChannel` / `amberChannel` / `uvChannel`, since the descriptor list omits bundled sliders.
 * A fixture that named its bundled white `warmWhite` would bind a fader the desk then drops.
 */
class BundledEmitterNamesTest {

    @Test
    fun `every bundled emitter is named by its category`() {
        val expected = mapOf(
            PropertyCategory.WHITE to "white",
            PropertyCategory.AMBER to "amber",
            PropertyCategory.UV to "uv",
        )
        var seen = 0
        for (type in FixtureTypeRegistry.allTypes) {
            val fixture = FixtureTypeRegistry.instantiateByTypeKey(type.typeKey, Universe(0, 0), "probe", "Probe", 1)
            for ((category, name) in expected) {
                val property = fixture.bundledProperty(category) ?: continue
                seen++
                assertEquals(name, property.name, "${type.typeKey}: its bundled ${category.name.lowercase()} must be named '$name'")
            }
        }
        assertEquals(true, seen > 0, "at least one fixture type bundles an emitter")
    }
}
