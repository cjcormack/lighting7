package uk.me.cormack.lighting7.fx

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update

/**
 * Each cue stack's **effect size** (effects plan D10, P3): a runtime weight, 0–1, on that stack's
 * effects — `w = size × envelope × stackSize`, applied after the blend like the effect's own size,
 * so 50 % puts a stack's chase halfway over what is underneath and never scales its raw output.
 *
 * Owned by [CueStackManager], so it is per show: a project switch builds a new one and every stack
 * starts at 100 %, and a same-project reload carries it (`show/ShowCarry.kt`). Nothing is stored.
 * Only sizes below 100 % are held; an absent stack is at full.
 *
 * Which stack weighs an instance is [weighingStackOf]: its cue's highest hold, never the
 * first-hold tag [FxInstance.cueStackId] (§11, question 21).
 */
class StackEffectSizes {
    private val sizes = MutableStateFlow<Map<Int, Double>>(emptyMap())

    /** Every stack below full, by id. The `stackEffectSize.state` frame and the surfaces' feedback. */
    val state: StateFlow<Map<Int, Double>> = sizes.asStateFlow()

    /** [stackId]'s size, 1.0 when it has not been moved. */
    fun of(stackId: Int): Double = sizes.value[stackId] ?: FULL

    /**
     * Set [stackId]'s size, clamped into 0–1, and answer it as held. A non-finite size is dropped
     * and the current one answered.
     */
    fun set(stackId: Int, size: Double): Double = change(stackId, size).second

    /** [set], answering the size before and after as one atomic step, so two writers never both read one `before`. */
    internal fun change(stackId: Int, size: Double): Pair<Double, Double> {
        if (!size.isFinite()) return of(stackId).let { it to it }
        val clamped = size.coerceIn(0.0, FULL)
        val before = sizes.getAndUpdate { current -> if (clamped >= FULL) current - stackId else current + (stackId to clamped) }
        return (before[stackId] ?: FULL) to clamped
    }

    /** Drop [stackId] — the stack is gone. */
    fun forget(stackId: Int) = sizes.update { it - stackId }

    /** Every size below full, for a reload to carry. */
    fun snapshot(): Map<Int, Double> = sizes.value

    /** Take [carried] as this show's sizes — a reload's, before its GOs, so the effects arrive weighed. */
    fun restore(carried: Map<Int, Double>) {
        sizes.value = carried.filterValues { it.isFinite() && it < FULL }.mapValues { it.value.coerceAtLeast(0.0) }
    }

    /** The stack size that weighs [effect]: its weighing stack's, 1.0 for anything else. */
    fun weightOf(effect: FxInstance): Double {
        val all = sizes.value
        if (all.isEmpty()) return FULL
        val stackId = weighingStackOf(effect) ?: return FULL
        return all[stackId] ?: FULL
    }

    companion object {
        const val FULL = 1.0
    }
}

/**
 * The stack whose size weighs [effect]: the stack of its cue's highest hold, which is what its
 * [FxInstance.priority] is — `CueHolds` restamps every instance of a cue whenever its top hold
 * changes, and retags an instance a cue adopts or takes back. Null for an effect no cue owns, and
 * for a cue whose top hold is a loose press (`CueHolds.LOOSE_BAND`), which a press holds at 100 %.
 *
 * A **leaving** instance keeps its cue and its priority, so it shrinks from the size of the stack
 * that let it go rather than jumping to full as its fade starts.
 */
internal fun weighingStackOf(effect: FxInstance): Int? {
    if (effect.cueId == null) return null
    val priority = effect.priority
    if (priority < STACK_PRIORITY_BAND || priority >= CueHolds.LOOSE_BAND) return null
    return stackOfDerivedPriority(priority)
}
