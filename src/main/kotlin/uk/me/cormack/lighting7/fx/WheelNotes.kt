package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.fixture.FixtureTypeRegistry
import uk.me.cormack.lighting7.fixture.GroupableFixture
import uk.me.cormack.lighting7.fixture.dmx.ColourSplits
import uk.me.cormack.lighting7.fixture.dmx.DmxFixtureColourSettingValue
import uk.me.cormack.lighting7.fixture.dmx.DmxFixtureSetting
import uk.me.cormack.lighting7.fixture.dmx.DmxFixtureSettingValue
import uk.me.cormack.lighting7.fixture.group.FixtureElement
import uk.me.cormack.lighting7.fixture.media.FittedMedia
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * What the preview says about a chase on one colour-wheel head (wheel-colour-chase plan D8), so the
 * editor can say it before the rig shows it.
 */
internal sealed interface FxWheelNote {
    /** Two or more of the chase's steps ([colours], their labels) land on one [slot]. */
    data class SameSlot(val colours: List<String>, val slot: String) : FxWheelNote

    /** The chase splits [from] into [to], which this wheel cannot hold, so it changes at the split's middle. */
    data class NoSplit(val from: String, val to: String, val reason: Reason) : FxWheelNote {
        enum class Reason { WHEEL, NOT_NEIGHBOURS }
    }

    /** A step lasts [stepMs], shorter than the [needMs] the wheel takes for the chase's longest move. */
    data class TooFast(val needMs: Long, val stepMs: Long) : FxWheelNote
}

internal object WheelNotes {
    /**
     * The notes for [steps] on [target], a colour wheel of [fixture], each step [stepMs] long. [split]
     * is whether the chase holds splits. A setting that is no colour wheel answers none.
     */
    fun of(
        target: SettingTarget,
        fixture: GroupableFixture,
        steps: StepList,
        labels: List<String>,
        split: Boolean,
        media: FittedMedia?,
        stepMs: Double,
    ): List<FxWheelNote> {
        val setting = target.getSetting(fixture) ?: return emptyList()
        if (setting.sortedValues.first() !is DmxFixtureColourSettingValue) return emptyList()
        val n = steps.size
        if (n == 0) return emptyList()
        val slots: List<DmxFixtureSettingValue?> = List(n) { k ->
            target.outputLevel(fixture, FxOutput.Steps(k, k, 0.0, steps), setting, media)
                ?.let { level -> setting.sortedValues.firstOrNull { it.level == level } }
        }
        val notes = ArrayList<FxWheelNote>()

        if (target.colourWheel) {
            slots.indices.filter { slots[it] != null }.groupBy { slots[it]!! }.forEach { (slot, indices) ->
                val colours = indices.map { labels.getOrElse(it) { (it + 1).toString() } }.distinct()
                if (colours.size >= 2) notes += FxWheelNote.SameSlot(colours, slot.name)
            }
        }

        // Each move the chase makes on this head: a step into the next, the last into the first.
        val moves = if (n < 2) emptyList() else (0 until n).mapNotNull { k ->
            val a = slots[k]
            val b = slots[(k + 1) % n]
            if (a == null || b == null || a == b) null else a to b
        }.distinctBy { (a, b) -> setOf(a, b) }

        if (split) {
            val wheelSplits = ColourSplits.splits(setting)
            for ((a, b) in moves) {
                if (ColourSplits.levelBetween(setting, a, b) != null) continue
                val reason = if (wheelSplits) FxWheelNote.NoSplit.Reason.NOT_NEIGHBOURS else FxWheelNote.NoSplit.Reason.WHEEL
                notes += FxWheelNote.NoSplit(a.name, b.name, reason)
            }
        }

        colourMsOf(fixture)?.let { colourMs ->
            val needMs = moves.maxOfOrNull { (a, b) -> colourMs * share(setting, a, b, split) } ?: 0.0
            if (needMs > stepMs) notes += FxWheelNote.TooFast(needMs.roundToLong(), stepMs.roundToLong())
        }
        return notes
    }

    /**
     * How much of the wheel's range a move from [a] to [b] crosses: through their split, of the
     * continuous band; else between indexed levels, of the channel, as the Stage view's travel reads it.
     */
    private fun share(setting: DmxFixtureSetting<*>, a: DmxFixtureSettingValue, b: DmxFixtureSettingValue, split: Boolean): Double {
        val ca = (a as? DmxFixtureColourSettingValue)?.continuousLevel
        val cb = (b as? DmxFixtureColourSettingValue)?.continuousLevel
        if (split && ca != null && cb != null && ColourSplits.levelBetween(setting, a, b) != null) {
            val band = setting.sortedValues.mapNotNull { (it as? DmxFixtureColourSettingValue)?.continuousLevel?.toInt() }
            val span = band.max() - band.min()
            if (span > 0) return abs(ca.toInt() - cb.toInt()).toDouble() / span
        }
        return abs(a.level.toInt() - b.level.toInt()) / 255.0
    }

    /** The time [fixture]'s type declares to cross its colour wheel, a cell's its parent's; null for none. */
    private fun colourMsOf(fixture: GroupableFixture): Int? {
        val owner: Any = (fixture as? FixtureElement<*>)?.parentFixture ?: fixture
        val typeKey = FixtureTypeRegistry.typeKeyForClass(owner::class) ?: return null
        return FixtureTypeRegistry.typeInfoForKey(typeKey)?.travel?.colourMs
    }
}
