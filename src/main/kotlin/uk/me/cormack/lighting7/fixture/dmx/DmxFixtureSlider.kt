package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.DmxController
import uk.me.cormack.lighting7.fixture.FixtureSlider

class DmxFixtureSlider(
    val controller: DmxController,
    val channelNo: Int,
    val min: UByte = 0u,
    val max: UByte = 255u,
): FixtureSlider {
    override var value: UByte
        get() = controller.getValue(channelNo)
        set(value) = controller.setValue(channelNo, maxOf(min, minOf(value, max)))

    override fun fadeToValue(value: UByte, fadeMs: Long) {
        controller.setValue(channelNo, maxOf(min, minOf(value, max)), fadeMs)
    }
}
