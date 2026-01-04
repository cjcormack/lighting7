package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.dmx.DmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.FixtureSettingValue

interface DmxFixtureSettingValue: FixtureSettingValue {
    val level: UByte
}

/**
 * Setting value with an associated colour preview for display in the UI.
 * Use this for colour preset enums (e.g., RED, GREEN, BLUE presets).
 */
interface DmxFixtureColourSettingValue : DmxFixtureSettingValue {
    /** Hex colour string for UI preview (e.g., "#FF0000"), or null for no preview */
    val colourPreview: String?
}

/**
 * Maps an enum of setting values to a single DMX channel.
 *
 * Unlike [DmxSlider] and [DmxColour], this class retains the "Fixture" prefix because
 * settings are inherently fixture-specific (each fixture defines its own enum values).
 * There's no generic `Setting` interface since the type parameter varies per fixture.
 *
 * @param T The fixture-specific enum type implementing [DmxFixtureSettingValue]
 * @param transaction The controller transaction context
 * @param universe The DMX universe containing the channel
 * @param channelNo The DMX channel number
 * @param settingValues Array of all possible setting values
 */
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

    val sortedValues: List<T>
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

