package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.DmxController
import uk.me.cormack.lighting7.fixture.DmxFixture
import uk.me.cormack.lighting7.fixture.FixtureProperty
import uk.me.cormack.lighting7.fixture.FixtureType
import uk.me.cormack.lighting7.fixture.FixtureWithDimmer

@FixtureType("quadbar")
class QuadBarFixture (
    controller: DmxController,
    key: String,
    fixtureName: String,
    firstChannel: Int,
    position: Int,
    maxDimmerLevel: UByte = 255u,
): DmxFixture(controller, firstChannel, 6, key, fixtureName, position), FixtureWithDimmer {
    enum class Movement(override val level: UByte): DmxFixtureSettingValue {
        MOVEMENT_0(0u),
        MOVEMENT_1(8u),
        MOVEMENT_2(23u),
        MOVEMENT_3(38u),
        MOVEMENT_4(53u),
        MOVEMENT_5(68u),
        MOVEMENT_6(83u),
        MOVEMENT_7(98u),
        MOVEMENT_8(113u),
        MOVEMENT_9(128u),
        MOVEMENT_10(143u),
        MOVEMENT_11(158u),
        MOVEMENT_12(173u),
        MOVEMENT_13(188u),
        MOVEMENT_14(203u),
        MOVEMENT_15(218u),
        MOVEMENT_016(233u),
        SOUND_ACTIVE(248u),
    }

    @FixtureProperty
    override val dimmer = DmxFixtureSlider(controller, firstChannel, max = maxDimmerLevel)

    @FixtureProperty
    val movement = DmxFixtureSetting(controller, firstChannel + 2, Movement.entries.toTypedArray())
}
