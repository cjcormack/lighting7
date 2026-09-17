package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fixture.group.FixtureElement
import uk.me.cormack.lighting7.fixture.group.MultiElementFixture
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
     * **The parent↔cell rule, stated once** (busk-further plan D11): does [held] cover [pressed]?
     *
     * True when [pressed] is in [held] outright, or when it is a **cell** — a fixture-typed target
     * whose key is an element key — and its parent fixture is in [held]. Never the other way: a
     * layer on four cells does not cover the whole bar, however many cells it holds, because the
     * parent has properties no cell does. [held] is expected in [expand]ed form (groups already
     * replaced by their members); a cell is resolved through `Fixtures` and its key is never parsed,
     * so a key nothing resolves is covered only by itself.
     *
     * Read by `pressWouldRelease`, `ProgrammerLayerStack.toggle`, [narrow], the desk selection, the
     * cook's layer filter (`CueComposer.LayerTargets.covers`), and the record path's scope tests
     * (`collectProgrammerEntries`, `targetInScope`, `lookRowInRemit`); `appliedState` reads the same
     * fact through [cells] and [parentOf]. Nothing else, which is the point: the client keeps
     * reading the desk's resolved applied state rather than carrying a copy of this.
     */
    fun covers(held: Set<CueTargetDto>, pressed: CueTargetDto): Boolean {
        if (pressed in held) return true
        val parent = parentOf(pressed) ?: return false
        return parent in held
    }

    /**
     * The cells of [target], as fixture-typed targets, when it resolves to a multi-element fixture;
     * empty for anything else (a cell, a plain fixture, a group, or a key that does not resolve).
     */
    fun cells(target: CueTargetDto): List<CueTargetDto> {
        if (TargetRef.ofOrNull(target.type, target.key) !is TargetRef.Fixture) return emptyList()
        val fixture = runCatching { fixtures()?.untypedFixture(target.key) }.getOrNull()
            as? MultiElementFixture<*> ?: return emptyList()
        return fixture.elements.map { CueTargetDto(TargetRef.Fixture.TYPE, it.elementKey) }
    }

    /** [target]'s parent as a fixture-typed target when it is a cell, else null. */
    fun parentOf(target: CueTargetDto): CueTargetDto? {
        if (TargetRef.ofOrNull(target.type, target.key) !is TargetRef.Fixture) return null
        val element = runCatching { fixtures()?.untypedGroupableFixture(target.key) }.getOrNull()
            as? FixtureElement<*> ?: return null
        return CueTargetDto(TargetRef.Fixture.TYPE, element.parentFixture.key)
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
     *
     * Cells follow [covers] in both directions. A held head the press covers — named outright, or a
     * cell whose parent is pressed — comes off. A held **parent** some of whose cells are pressed is
     * rewritten as the cells the press did not name, which costs that entry the whole-fixture
     * spelling (and its whole-fixture properties) exactly as a split group costs the group spelling;
     * a parent none of whose cells are pressed keeps its own.
     */
    fun narrow(held: List<CueTargetDto>, pressed: Set<CueTargetDto>): List<CueTargetDto> {
        if (held.isEmpty() || pressed.isEmpty()) return held
        val remaining = held.flatMap { entry ->
            val expanded = expand(listOf(entry))
            val kept = expanded.flatMap { head ->
                when {
                    covers(pressed, head) -> emptyList()
                    else -> {
                        val cells = cells(head)
                        if (cells.any { it in pressed }) cells.filterNot { it in pressed } else listOf(head)
                    }
                }
            }
            if (kept == expanded) listOf(entry) else kept
        }
        return if (remaining == held) held else remaining.distinct()
    }
}
