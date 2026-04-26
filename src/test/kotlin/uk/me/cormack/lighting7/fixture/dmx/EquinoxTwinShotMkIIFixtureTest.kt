package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.createTestTransaction
import kotlin.test.Test
import kotlin.test.assertEquals

class EquinoxTwinShotMkIIFixtureTest {

    private val universe = Universe(0, 0)

    @Test
    fun `output1, output2 and master write to consecutive channels`() {
        val (controller, transaction) = createTestTransaction(universe)
        val fixture = EquinoxTwinShotMkIIFixture(universe, "twin-shot-1", "Twin Shot 1", 100)
            .withTransaction(transaction)

        fixture.output1.value = 200u
        fixture.output2.value = 210u
        fixture.master.value = 220u

        transaction.apply()

        assertEquals(200u.toUByte(), controller.getValue(100))
        assertEquals(210u.toUByte(), controller.getValue(101))
        assertEquals(220u.toUByte(), controller.getValue(102))
    }
}
