package uk.me.cormack.lighting7.fixture.hue

import uk.me.cormack.lighting7.fixture.FixtureWithColour
import java.awt.Color

//class HueFixtureGroupWithColour(
//    val controller: HueController,
//    val groupId: Int,
//    val maxBrightness: Int = 254
//): FixtureWithColour {
////    override val whiteSupport: Boolean = false
////    override val amberSupport: Boolean = false
////    override val uvSupport: Boolean = false
//
//    override var rgbColour: Color
//        get() = throw NotImplementedError()
//        set(value) {
//            controller.setHueGroupColor(groupId, value, maxBrightness)
//        }
//
//    override fun fadeToColour(rgbColor: Color, fadeMs: Long) {
//        controller.setHueGroupColor(groupId, rgbColor, maxBrightness, fadeMs)
//    }
//}
