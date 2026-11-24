package uk.me.cormack.lighting7.fixture.dmx

import java.util.concurrent.ConcurrentHashMap

/**
 * Where a colour wheel splits (wheel-colour-chase plan D5): a wheel parked between two adjacent
 * slots puts half of each in the beam. Only a slot that declares
 * [DmxFixtureColourSettingValue.continuousLevel] takes part, and nothing is guessed from names.
 *
 * The declared slots, in the wheel's order (its options' indexed levels), are the wheel's continuous
 * band: two slots split where they are next to each other among them, at the midpoint of their two
 * levels, rounded down. A level inside the band reads as the two declared slots either side of it and
 * how far it is from the first to the second ([reading]); the client reads it the same way through
 * `src/test/resources/fixture/colourSplit.fixture.json`.
 */
object ColourSplits {
    private class Declared(val option: DmxFixtureSettingValue, val level: UByte)

    private val declaredByOptions = ConcurrentHashMap<Class<*>, List<Declared>>()

    private fun declared(setting: DmxFixtureSetting<*>): List<Declared> {
        return declaredByOptions.getOrPut(setting.optionsKey()) {
            setting.sortedValues.mapNotNull { option ->
                (option as? DmxFixtureColourSettingValue)?.continuousLevel?.let { Declared(option, it) }
            }
        }
    }

    /** Whether [setting] splits at all. */
    fun splits(setting: DmxFixtureSetting<*>): Boolean = splits(declared(setting).map { it.level })

    /** Whether a wheel whose slots declare [levels], null for none, splits: two or more declare one. */
    fun splits(levels: List<UByte?>): Boolean = levels.count { it != null } >= 2

    /**
     * The level that holds the split of [a] and [b], either way round, or null where one declares no
     * level or they are not neighbours on the wheel. A slot and itself have no split.
     */
    fun levelBetween(setting: DmxFixtureSetting<*>, a: DmxFixtureSettingValue, b: DmxFixtureSettingValue): UByte? {
        if (a == b) return null
        val slots = declared(setting)
        val i = slots.indexOfFirst { it.option == a }
        val j = slots.indexOfFirst { it.option == b }
        if (i < 0 || j < 0 || kotlin.math.abs(i - j) != 1) return null
        return ((slots[i].level.toInt() + slots[j].level.toInt()) / 2).toUByte()
    }

    /** [levelBetween] for the options at indexed levels [a] and [b]. */
    fun levelBetween(setting: DmxFixtureSetting<*>, a: UByte, b: UByte): UByte? {
        val optionA = setting.sortedValues.firstOrNull { it.level == a } ?: return null
        val optionB = setting.sortedValues.firstOrNull { it.level == b } ?: return null
        return levelBetween(setting, optionA, optionB)
    }

    /**
     * A level read on the continuous band: the declared slots either side of it, by name, and [share]
     * of the way from [slotA] to [slotB], 0 to 1. Exactly on a declared level is that slot at share 0
     * with the next one (the last slot's at share 1 with the one before).
     */
    data class Reading(val slotA: String, val slotB: String, val share: Double)

    /** [level] on [setting]'s continuous band, or null where it lies outside it or the wheel does not split. */
    fun reading(setting: DmxFixtureSetting<*>, level: UByte): Reading? {
        val slots = declared(setting)
        if (slots.size < 2) return null
        if (level < slots.first().level || level > slots.last().level) return null
        val i = slots.indexOfLast { it.level <= level }.coerceAtMost(slots.size - 2)
        val a = slots[i]
        val b = slots[i + 1]
        val share = (level.toInt() - a.level.toInt()).toDouble() / (b.level.toInt() - a.level.toInt())
        return Reading(a.option.name, b.option.name, share)
    }
}
