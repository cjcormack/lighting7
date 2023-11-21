package uk.me.cormack.lighting7.fixture.dmx

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ObsoleteCoroutinesApi
import uk.me.cormack.artnet.ArtNetController
import uk.me.cormack.fixture.FixtureWithDimmer
import kotlin.math.min

@ExperimentalUnsignedTypes
class DmxFixtureWithDimmer @OptIn(ExperimentalCoroutinesApi::class,
    ObsoleteCoroutinesApi::class
) constructor(val controller: ArtNetController, val dimmerChannelNo: Int, val maxDimmerLevel: UByte = 0u): FixtureWithDimmer {
    override var level: UByte
        get() = controller.getValue(dimmerChannelNo)
        set(value) = controller.setValue(dimmerChannelNo, min(maxDimmerLevel.toUInt(), value.toUInt()).toUByte())

    override fun fadeToLevel(level: UByte, fadeMs: Long) {
        controller.setValue(dimmerChannelNo, min(maxDimmerLevel.toUInt(), level.toUInt()).toUByte(), fadeMs)
    }
}
