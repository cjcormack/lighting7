package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.show.Fixtures

/**
 * The one rule for "do these targets mean the same heads?" — shared by [ProgrammerLayerStack]
 * (a pad press against the layers on the stack) and `state.DeskSelection` (a select button
 * against the desk's selection), so the two cannot drift.
 *
 * A group and the list of its members are two spellings of one selection; every coverage
 * question is asked of the expansion rather than of the written target. [fixtures] is a
 * provider because both callers outlive a `Show`: the layer stack is built before `State`
 * finishes wiring, and the selection survives a project switch (cleared, not rebuilt).
 */
class TargetCoverage(private val fixtures: () -> Fixtures?) {

    /**
     * [targets] with every group replaced by its member fixtures.
     *
     * A group that cannot be resolved, or that holds no `Fixture` members, expands to **itself**.
     * That keeps a stale target comparable — two layers naming a since-deleted group still match,
     * and neither matches a fixture — rather than collapsing to the empty set, which would make
     * every such layer look like every other. `ofOrNull`, not `of`: a target type this build does
     * not know stands for itself like an unresolvable group, rather than throwing out of a press.
     */
    fun expand(targets: List<CueTargetDto>): List<CueTargetDto> =
        targets.flatMap { target ->
            when (TargetRef.ofOrNull(target.type, target.key)) {
                is TargetRef.Group -> {
                    val members = runCatching { fixtures()?.untypedGroup(target.key) }.getOrNull()
                        ?.fixtures.orEmpty()
                        .filterIsInstance<Fixture>()
                        .map { CueTargetDto(TargetRef.Fixture.TYPE, it.key) }
                    members.ifEmpty { listOf(target) }
                }
                else -> listOf(target)
            }
        }

    /**
     * [held] with [pressed] taken off it, head by head: the argument **by identity** when the press
     * names none of its heads (callers count a change by identity), a narrowed copy when it names
     * some, and empty when it names all of them.
     *
     * A group the press only *partly* covers is rewritten as the members it did not name, which
     * costs that entry the group spelling. That is the same thing the operator would have had by
     * picking those fixtures by hand, which is the point of it; a target the press does not touch
     * keeps its own spelling, so the ordinary case never splits.
     */
    fun narrow(held: List<CueTargetDto>, pressed: Set<CueTargetDto>): List<CueTargetDto> {
        if (held.isEmpty() || pressed.isEmpty()) return held
        val remaining = held.flatMap { entry ->
            val expanded = expand(listOf(entry))
            val kept = expanded.filterNot { it in pressed }
            if (kept.size == expanded.size) listOf(entry) else kept
        }
        return if (remaining == held) held else remaining.distinct()
    }
}
