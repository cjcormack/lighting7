package uk.me.cormack.fixture.hue

import uk.me.cormack.fixture.FixtureWithDimmer

class HueFixtureWithDimmer(val controller: HueController, val lightId: Int): FixtureWithDimmer {
    override var level: UByte
        get() = throw NotImplementedError()
        set(value) = controller.setHueLightLevel(lightId, value)

    override fun fadeToLevel(level: UByte, fadeMs: Long) {
        controller.setHueLightLevel(lightId, level, fadeMs)
    }
}
