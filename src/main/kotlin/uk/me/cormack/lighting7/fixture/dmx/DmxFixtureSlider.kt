package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.FixtureSlider

class DmxFixtureSlider(
    val transaction: ControllerTransaction?,
    val universe: Universe,
    val channelNo: Int,
    val min: UByte = 0u,
    val max: UByte = 255u,
): FixtureSlider {
    private val nonNullTransaction get() = checkNotNull(transaction) {
        "Attempted to use fixture outside of a transaction"
    }
    override var value: UByte
        get() = nonNullTransaction.getValue(universe, channelNo)
        set(value) = nonNullTransaction.setValue(universe, channelNo, maxOf(min, minOf(value, max)))

    override fun fadeToValue(value: UByte, fadeMs: Long) {
        nonNullTransaction.setValue(universe, channelNo, maxOf(min, minOf(value, max)), fadeMs)
    }
}
