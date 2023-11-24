package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.DmxController
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fixture.FixtureWithSettings

enum class BeamBarMovement(override val level: UByte): DmxFixtureSettingValue {
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

class QuadBarFixture (
    val controller: DmxController,
    key: String,
    fixtureName: String,
    firstChannel: Int,
    position: Int,
    maxDimmerLevel: UByte = 255u
): Fixture(key, fixtureName, position),
    FixtureWithSettings by DmxFixtureWithSettings(
        mapOf(
            "movement" to DmxFixtureSetting(controller, firstChannel, BeamBarMovement.entries.toTypedArray()),
        )
    )
