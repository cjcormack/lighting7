package uk.me.cormack.lighting7.testsupport

import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.FixtureAttribute
import uk.me.cormack.lighting7.fixture.FixtureType
import uk.me.cormack.lighting7.fixture.AttributeCategory
import uk.me.cormack.lighting7.fixture.DmxFixture
import uk.me.cormack.lighting7.fixture.dmx.DmxSlider

/**
 * A head with what no library type has (fixture-optics session 1): a FOCUS slider that declares no
 * range — `FocusRangeTest` requires every library focus to declare one — and a declared
 * `depthOfField`. Test-only: it is not in `FixtureTypeRegistry`'s list, so no patch can name it.
 */
@FixtureType("test-unranged-focus-head", depthOfField = 4.5)
class TestFocusHead(
    universe: Universe,
    key: String,
    firstChannel: Int,
    private val transaction: ControllerTransaction? = null,
) : DmxFixture(universe, firstChannel, 2, key, key) {

    override fun withTransaction(transaction: ControllerTransaction): TestFocusHead =
        TestFocusHead(universe, key, firstChannel, transaction)

    @FixtureAttribute("Dimmer", category = AttributeCategory.DIMMER)
    val dimmer = DmxSlider(transaction, universe, firstChannel)

    @FixtureAttribute("Focus", category = AttributeCategory.FOCUS)
    val focus = DmxSlider(transaction, universe, firstChannel + 1)
}
