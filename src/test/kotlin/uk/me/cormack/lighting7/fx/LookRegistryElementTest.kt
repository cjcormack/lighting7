package uk.me.cormack.lighting7.fx

import org.junit.Test
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.dmx.LedLightbar12PixelFixture
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.show.Fixtures
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [LookRegistry.expand] carries an element row under its **element key** — the Include path and
 * `literalFor` see a cell's value the way they see a fixture's. Before this, both loops skipped a
 * row with an `elementKey` (`FU-LOOK-ELEMENT-ROWS`).
 */
class LookRegistryElementTest {

    private val universe = Universe(0, 0)
    private val uuid: UUID = UUID.fromString("5c0a7d2e-91b4-4f3a-8e6d-2b7c1f0a9e34")

    private fun fixtures(): Fixtures {
        val fixtures = Fixtures()
        fixtures.register {
            addFixture(LedLightbar12PixelFixture.Mode48Ch(universe, "bar-1", "Bar 1", 1))
        }
        return fixtures
    }

    private fun registry(vararg rows: LookRowEntry) = LookRegistry(
        fixtures = { fixtures() },
        loader = { requested ->
            LookSnapshot(lookId = 4, lookUuid = uuid, name = "Cells", rows = rows.toList(), effects = emptyList())
                .takeIf { it.lookUuid == requested }
        },
    )

    @Test
    fun `an element row expands under its element key`() {
        val reg = registry(
            LookRowEntry(TargetRef.Fixture("bar-1"), "rgbColour", "#ff0000", elementKey = "bar-1.pixel-2"),
        )
        assertEquals("#ff0000", reg.literalFor(uuid, "bar-1.pixel-2", "rgbColour"))
        assertNull(reg.literalFor(uuid, "bar-1", "rgbColour"), "the parent holds nothing of its own")
        assertNull(reg.literalFor(uuid, "bar-1.pixel-3", "rgbColour"), "a sibling cell is not covered")
    }

    @Test
    fun `an element row and a whole-fixture row live side by side`() {
        val reg = registry(
            LookRowEntry(TargetRef.Fixture("bar-1"), "rgbColour", "#0000ff"),
            LookRowEntry(TargetRef.Fixture("bar-1"), "rgbColour", "#ff0000", elementKey = "bar-1.pixel-2"),
        )
        assertEquals("#0000ff", reg.literalFor(uuid, "bar-1", "rgbColour"))
        assertEquals("#ff0000", reg.literalFor(uuid, "bar-1.pixel-2", "rgbColour"))
    }
}
