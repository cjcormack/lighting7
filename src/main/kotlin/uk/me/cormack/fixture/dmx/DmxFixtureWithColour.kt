package uk.me.cormack.fixture.dmx

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ObsoleteCoroutinesApi
import uk.me.cormack.artnet.ArtNetController
import uk.me.cormack.fixture.FixtureWithColour
import java.awt.Color

@OptIn(ExperimentalCoroutinesApi::class, ObsoleteCoroutinesApi::class)
class DmxFixtureWithColour(
    val controller: ArtNetController,
    val redChannelNo: Int,
    val greenChannelNo: Int,
    val blueChannelNo: Int,
    val whiteChannelNo: Int? = null,
    val amberChannelNo: Int? = null,
    val uvChannelNo: Int? = null
): FixtureWithColour {
    override val whiteSupport: Boolean = whiteChannelNo != null
    override val amberSupport: Boolean = amberChannelNo != null
    override val uvSupport: Boolean = uvChannelNo != null

    override var rgbColor: Color
        get() {
            val redLevel = controller.getValue(redChannelNo).toInt()
            val greenLevel = controller.getValue(greenChannelNo).toInt()
            val blueLevel = controller.getValue(blueChannelNo).toInt()

            return Color(redLevel, greenLevel, blueLevel)
        }
        set(value) {
            controller.setValue(redChannelNo, value.red.toUByte())
            controller.setValue(greenChannelNo, value.green.toUByte())
            controller.setValue(blueChannelNo, value.blue.toUByte())
        }

    override var whiteLevel: UByte
        get() = if (whiteChannelNo != null) {
            controller.getValue(whiteChannelNo)
        } else {
            0u
        }
        set(value) {
            if (whiteChannelNo != null) {
                controller.setValue(whiteChannelNo, value)
            }
        }

    override var amberLevel: UByte
        get() = if (amberChannelNo != null) {
            controller.getValue(amberChannelNo)
        } else {
            0u
        }
        set(value) {
            if (amberChannelNo != null) {
                controller.setValue(amberChannelNo, value)
            }
        }

    override var uvLevel: UByte
        get() = if (uvChannelNo != null) {
            controller.getValue(uvChannelNo)
        } else {
            0u
        }
        set(value) {
            if (uvChannelNo != null) {
                controller.setValue(uvChannelNo, value)
            }
        }

    override fun fadeToColour(rgbColor: Color, fadeMs: Long) {
        controller.setValue(redChannelNo, rgbColor.red.toUByte(), fadeMs)
        controller.setValue(greenChannelNo, rgbColor.green.toUByte(), fadeMs)
        controller.setValue(blueChannelNo, rgbColor.blue.toUByte(), fadeMs)
    }

    override fun fadeToWhiteLevel(level: UByte, fadeMs: Long) {
        if (whiteChannelNo != null) {
            controller.setValue(whiteChannelNo, level)
        }
    }

    override fun fadeToAmberLevel(level: UByte, fadeMs: Long) {
        if (amberChannelNo != null) {
            controller.setValue(amberChannelNo, level)
        }
    }

    override fun fadeToUvLevel(level: UByte, fadeMs: Long) {
        if (uvChannelNo != null) {
            controller.setValue(uvChannelNo, level)
        }
    }
}
