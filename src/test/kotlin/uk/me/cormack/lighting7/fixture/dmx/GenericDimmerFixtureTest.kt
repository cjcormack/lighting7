package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.createTestTransaction
import kotlin.test.Test
import kotlin.test.assertEquals

class GenericDimmerFixtureTest {

    private val universe = Universe(0, 0)

    @Test
    fun `dimmer write round-trips through transaction and controller`() {
        val (controller, transaction) = createTestTransaction(universe)
        val fixture = GenericDimmerFixture(universe, "house-1", "House 1", 42)
            .withTransaction(transaction)

        fixture.dimmer.value = 200u

        assertEquals(200u.toUByte(), transaction.getValue(universe, 42))

        transaction.apply()

        assertEquals(200u.toUByte(), controller.getValue(42))
    }
}
