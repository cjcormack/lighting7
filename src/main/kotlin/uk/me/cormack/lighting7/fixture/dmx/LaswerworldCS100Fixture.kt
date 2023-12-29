package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.DmxController
import uk.me.cormack.lighting7.fixture.DmxFixture
import uk.me.cormack.lighting7.fixture.FixtureType

enum class LaswerworldCS100OperationMode(override val level: UByte): DmxFixtureSettingValue {
    DMX_MODE(0u),
    SOUND_ACTIVE(200u),
    AUTO_MODE(225u),
}

@FixtureType("laserword_cs100")
class LaswerworldCS100Fixture(
    controller: DmxController,
    key: String,
    fixtureName: String,
    firstChannel: Int,
    position: Int,
): DmxFixture(controller, firstChannel, 13, key, fixtureName, position)
//    FixtureWithSettings by DmxFixtureWithSettingsImpl(
//        mapOf(
//            "mode" to DmxFixtureSettingImpl(controller, firstChannel+14, LaswerworldCS100OperationMode.entries.toTypedArray()),
//        )
//    )
