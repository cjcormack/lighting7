package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.DmxController
import uk.me.cormack.lighting7.fixture.FixtureWithSliders

data class DmxFixtureSliderSettings(
    val channelNo: Int,
    val minValue: UByte = 0u,
    val maxValue: UByte = 255u,
)

class DmxFixtureWithSliders(val controller: DmxController, val sliders: Map<String, DmxFixtureSliderSettings>) : FixtureWithSliders {
    override fun setSlider(sliderName: String, level: UByte, fadeMs: Long) {
//        val slider = sliders[sliderName] ?: throw Exception("No such slider ('$sliderName')")
//
//        val clippedValue = if (level == UByte.MIN_VALUE) {
//            0u
//        } else {
//            maxOf(slider.minValue, minOf(slider.maxValue, level))
//        }

//        controller.setValue(slider.channelNo, clippedValue, fadeMs)
    }
}
