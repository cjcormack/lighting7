package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.ControllerTransaction

interface DmxFixtureWithTransaction {
    val transaction: ControllerTransaction
}
