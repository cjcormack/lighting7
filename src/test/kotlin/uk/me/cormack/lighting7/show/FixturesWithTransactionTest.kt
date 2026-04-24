package uk.me.cormack.lighting7.show

import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.dmx.MockDmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertSame

/**
 * Tests that FixturesWithTransaction does not expose fixture registration methods.
 * GENERAL scripts receive a FixturesWithTransaction, so this ensures scripts
 * cannot register fixtures — only DbFixtureLoader can.
 */
class FixturesWithTransactionTest {

    @Test
    fun `FixturesWithTransaction does not expose register method`() {
        val methods = Fixtures.FixturesWithTransaction::class.members.map { it.name }
        assertFalse(
            methods.contains("register"),
            "FixturesWithTransaction should not expose a 'register' method — fixture registration is handled by DbFixtureLoader"
        )
    }

    @Test
    fun `repeated untypedFixture lookups reuse the wrapped instance`() {
        val universe = Universe(0, 0)
        val controller = MockDmxController(universe)
        val fixtures = Fixtures()
        fixtures.register {
            addController(controller)
            addFixture(HexFixture(universe, "hex-1", "Hex 1", 1))
        }

        val tx = ControllerTransaction(fixtures.controllers)
        val withTx = fixtures.withTransaction(tx)

        val first = withTx.untypedFixture("hex-1")
        val second = withTx.untypedFixture("hex-1")
        val third = withTx.untypedGroupableFixture("hex-1")

        assertSame(first, second, "Per-transaction wrapper cache should return the same fixture instance")
        assertSame(first, third, "Groupable lookup should hit the same cache slot as untypedFixture")
    }
}
