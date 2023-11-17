package uk.me.cormack.fixture.dmx

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ObsoleteCoroutinesApi
import uk.me.cormack.artnet.ArtNetController
import uk.me.cormack.fixture.FixtureWithSliders

data class DmxFixtureSliderSettings(
    val channelNo: Int,
    val minValue: UByte = 0u,
    val maxValue: UByte = 255u,
)

@OptIn(ExperimentalCoroutinesApi::class, ObsoleteCoroutinesApi::class)
class DmxFixtureWithSliders(val controller: ArtNetController, val sliders: Map<String, DmxFixtureSliderSettings>) : FixtureWithSliders {
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