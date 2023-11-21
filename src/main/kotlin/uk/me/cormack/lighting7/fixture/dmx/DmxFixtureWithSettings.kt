package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.artnet.ArtNetController
import uk.me.cormack.fixture.FixtureSetting
import uk.me.cormack.fixture.FixtureSettingValue
import uk.me.cormack.fixture.FixtureWithSettings

interface DmxFixtureSettingValue: FixtureSettingValue {
    val level: UByte
}

@ExperimentalUnsignedTypes
class DmxFixtureSetting<T : DmxFixtureSettingValue>(val controller: ArtNetController, val channelNo: Int, val settingValues: Array<T>) : FixtureSetting<T> {
    override var setting: T
        get() = getValueForLevel(controller.getValue(channelNo))
        set(value) = controller.setValue(channelNo, value.level, 0)

    private val sortedValues: List<T>
    private val valuesByName: Map<String, T>

    init {
        check(settingValues.isNotEmpty())

        sortedValues = settingValues.sortedBy { it.level }
        valuesByName = settingValues.associateBy { it.name }

        check(valuesByName.size == settingValues.size)
    }

    fun getValueForLevel(level: UByte): T {
        return sortedValues.firstOrNull { it.level >= level } ?: sortedValues.first()
    }

    fun getValueForName(name: String): T {
        return valuesByName[name] ?: throw Exception("No such value '$name'")
    }

    override fun setValue(valueName: String) {
        setting = getValueForName(valueName)
    }
}

@ExperimentalUnsignedTypes
class DmxFixtureWithSettings(override val settings: Map<String, DmxFixtureSetting<*>>) : FixtureWithSettings {
    override fun setSetting(settingName: String, valueName: String) {
        val setting = settings[settingName] ?: throw Exception("No such setting '$settingName'")
        setting.setValue(valueName)
    }
}
