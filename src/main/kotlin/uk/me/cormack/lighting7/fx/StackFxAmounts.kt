package uk.me.cormack.lighting7.fx

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update

/**
 * Each cue stack's **FX amount** (effects plan D10, P3): a runtime weight, 0–1, on that stack's
 * effects — `w = amount × envelope × stackAmount`, applied after the blend like the effect's own amount,
 * so 50 % puts a stack's chase halfway over what is underneath and never scales its raw output.
 *
 * Owned by [CueStackManager], so it is per show: a project switch builds a new one and every stack
 * starts at 100 %, and a same-project reload carries it (`show/ShowCarry.kt`). Nothing is stored.
 * Only amounts below 100 % are held; an absent stack is at full.
 *
 * Which stack weighs an instance is [weighingStackOf]: its cue's highest hold, never the
 * first-hold tag [FxInstance.cueStackId] (§11, question 21).
 */
class StackFxAmounts {
    private val amounts = MutableStateFlow<Map<Int, Double>>(emptyMap())

    /** Every stack below full, by id. The `stackFxAmount.state` frame and the surfaces' feedback. */
    val state: StateFlow<Map<Int, Double>> = amounts.asStateFlow()

    /** [stackId]'s FX amount, 1.0 when it has not been moved. */
    fun of(stackId: Int): Double = amounts.value[stackId] ?: FULL

    /**
     * Set [stackId]'s FX amount, clamped into 0–1, and answer it as held. A non-finite amount is dropped
     * and the current one answered.
     */
    fun set(stackId: Int, amount: Double): Double = change(stackId, amount).second

    /** [set], answering the amount before and after as one atomic step, so two writers never both read one `before`. */
    internal fun change(stackId: Int, amount: Double): Pair<Double, Double> {
        if (!amount.isFinite()) return of(stackId).let { it to it }
        val clamped = amount.coerceIn(0.0, FULL)
        val before = amounts.getAndUpdate { current -> if (clamped >= FULL) current - stackId else current + (stackId to clamped) }
        return (before[stackId] ?: FULL) to clamped
    }

    /** Drop [stackId] — the stack is gone. */
    fun forget(stackId: Int) = amounts.update { it - stackId }

    /** Every FX amount below full, for a reload to carry. */
    fun snapshot(): Map<Int, Double> = amounts.value

    /** Take [carried] as this show's FX amounts — a reload's, before its GOs, so the effects arrive weighed. */
    fun restore(carried: Map<Int, Double>) {
        amounts.value = carried.filterValues { it.isFinite() && it < FULL }.mapValues { it.value.coerceAtLeast(0.0) }
    }

    /** The FX amount that weighs [effect]: its weighing stack's, 1.0 for anything else. */
    fun weightOf(effect: FxInstance): Double {
        val all = amounts.value
        if (all.isEmpty()) return FULL
        val stackId = weighingStackOf(effect) ?: return FULL
        return all[stackId] ?: FULL
    }

    companion object {
        const val FULL = 1.0
    }
}

/**
 * The stack whose FX amount weighs [effect]: the stack of its cue's highest hold, which is what its
 * [FxInstance.priority] is — `CueHolds` restamps every instance of a cue whenever its top hold
 * changes, and retags an instance a cue adopts or takes back. Null for an effect no cue owns, and
 * for a cue whose top hold is a loose press (`CueHolds.LOOSE_BAND`), which a press holds at 100 %.
 *
 * A **leaving** instance keeps its cue and its priority, so it shrinks from the FX amount of the stack
 * that let it go rather than jumping to full as its fade starts.
 */
internal fun weighingStackOf(effect: FxInstance): Int? {
    if (effect.cueId == null) return null
    val priority = effect.priority
    if (priority < STACK_PRIORITY_BAND || priority >= CueHolds.LOOSE_BAND) return null
    return stackOfDerivedPriority(priority)
}
