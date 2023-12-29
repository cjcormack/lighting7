package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.DmxController
import uk.me.cormack.lighting7.fixture.FixtureSettingValue

interface DmxFixtureSettingValue: FixtureSettingValue {
    val level: UByte
}

class DmxFixtureSetting<T : DmxFixtureSettingValue>(
    val controller: DmxController,
    val channelNo: Int,
    settingValues: Array<T>,
) {
    var setting: T
        get() = valueForLevel(controller.getValue(channelNo))
        set(value) = controller.setValue(channelNo, value.level)

    private val sortedValues: List<T>
    private val valuesByName: Map<String, T>

    init {
        check(settingValues.isNotEmpty())

        sortedValues = settingValues.sortedBy { it.level }
        valuesByName = settingValues.associateBy { it.name }

        check(valuesByName.size == settingValues.size)
    }

    fun valueForLevel(level: UByte): T {
        return sortedValues.firstOrNull { it.level >= level } ?: sortedValues.first()
    }

    fun valueForName(name: String): T {
        return valuesByName[name] ?: throw Exception("No such value '$name'")
    }
}
