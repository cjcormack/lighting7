package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.DmxController
import uk.me.cormack.lighting7.fixture.FixtureWithDimmer
import kotlin.math.min

class DmxFixtureWithDimmer(val controller: DmxController, val dimmerChannelNo: Int, val maxDimmerLevel: UByte = 0u): FixtureWithDimmer {
    override var level: UByte
        get() = controller.getValue(dimmerChannelNo)
        set(value) = controller.setValue(dimmerChannelNo, min(maxDimmerLevel.toUInt(), value.toUInt()).toUByte())

    override fun fadeToLevel(level: UByte, fadeMs: Long) {
        controller.setValue(dimmerChannelNo, min(maxDimmerLevel.toUInt(), level.toUInt()).toUByte(), fadeMs)
    }
}
