package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.dmx.DmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.FixtureSettingValue

interface DmxFixtureSettingValue: FixtureSettingValue {
    val level: UByte
}

class DmxFixtureSetting<T : DmxFixtureSettingValue>(
    val transaction: ControllerTransaction?,
    val universe: Universe,
    val channelNo: Int,
    settingValues: Array<T>,
) {
    private val nonNullTransaction get() = checkNotNull(transaction) {
        "Attempted to use fixture outside of a transaction"
    }

    var setting: T
        get() = valueForLevel(nonNullTransaction.getValue(universe, channelNo))
        set(value) = nonNullTransaction.setValue(universe, channelNo, value.level)

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

