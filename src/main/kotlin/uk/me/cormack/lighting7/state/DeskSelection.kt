package uk.me.cormack.lighting7.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import uk.me.cormack.lighting7.fx.TargetCoverage
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.show.Fixtures

/**
 * The desk's selection: the groups and fixtures a selection-relative surface control (a
 * `SelectionProperty` fader, a `LocateSelection` button) and a busk-page press act on.
 *
 * **One desk, one selection, server-owned.** The composition model's "exactly one programmer,
 * shared by every client" argument applies verbatim (`docs/lighting-composition-model.md`
 * §"Layer 2"): DMX is one byte per channel, so two selections would force a "whose press wins?"
 * policy nothing expresses. Every client — the busk view, the programmer page, the X-Touch's
 * select buttons — reads and writes this one list, and the `selection.state` frame keeps them
 * honest. It is transient: cleared on project switch, never persisted, and outside the sync
 * decision tree's portable branch because it is not a table.
 *
 * [targets] is ordered as added and never holds a duplicate spelling. A group and one of its
 * members may both be present — they are two spellings, and [coverage] is how the two are
 * compared. Every mutation is one [MutableStateFlow.update], so one socket frame; a mutation
 * that changes nothing emits nothing.
 */
class DeskSelection(fixtures: () -> Fixtures?) {
    private val fixturesProvider = fixtures
    private val coverageRule = TargetCoverage(fixtures)

    private val _targets = MutableStateFlow<List<CueTargetDto>>(emptyList())
    val targets: StateFlow<List<CueTargetDto>> = _targets.asStateFlow()

    /** Replace the selection. Duplicates collapse, order is the caller's. */
    fun set(targets: List<CueTargetDto>) {
        val next = targets.distinct()
        _targets.update { current -> if (current == next) current else next }
    }

    fun clear() = set(emptyList())

    /**
     * Toggle one target, head by head.
     *
     * When every head [target] covers is already selected — a group by its members counts —
     * the press takes those heads off: an entry the press does not touch is kept as written, and
     * a group it only partly covers is rewritten as the members left behind ([TargetCoverage.narrow],
     * the same rule a busk press applies to a sibling layer). Otherwise the target is appended.
     * Returns true when the target is selected afterwards.
     */
    fun toggle(target: CueTargetDto): Boolean {
        var selected = false
        _targets.update { current ->
            if (covers(current, target)) {
                selected = false
                coverageRule.narrow(current, coverageRule.expand(listOf(target)).toSet())
            } else {
                selected = true
                if (target in current) current else current + target
            }
        }
        return selected
    }

    /** The selection with every group expanded to its member fixtures. */
    fun coverage(): List<CueTargetDto> = coverageRule.expand(_targets.value)

    /** True when every head [target] covers is selected — what a select button's LED shows. */
    fun covers(target: CueTargetDto): Boolean = covers(_targets.value, target)

    private fun covers(selection: List<CueTargetDto>, target: CueTargetDto): Boolean {
        if (selection.isEmpty()) return false
        val heads = coverageRule.expand(listOf(target))
        val selected = coverageRule.expand(selection).toSet()
        return heads.all { it in selected }
    }

    /**
     * Drop every entry that no longer resolves in the patch, keeping the rest in order — the
     * same rule a press applies to a stale target. Called on fixture reload.
     */
    fun prune() {
        val fixtures = fixturesProvider() ?: return
        _targets.update { current ->
            val kept = current.filter { resolves(fixtures, it) }
            if (kept.size == current.size) current else kept
        }
    }

    private fun resolves(fixtures: Fixtures, target: CueTargetDto): Boolean =
        when (TargetRef.ofOrNull(target.type, target.key)) {
            is TargetRef.Fixture -> runCatching { fixtures.untypedFixture(target.key) }.isSuccess
            is TargetRef.Group -> runCatching { fixtures.untypedGroup(target.key) }.isSuccess
            null -> false
        }
}
