package uk.me.cormack.lighting7.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.fx.PropertyMaskGroup
import uk.me.cormack.lighting7.fx.SpreadOver
import uk.me.cormack.lighting7.fx.TargetCoverage
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.show.Fixtures

/**
 * Who moved the desk selection last (multi-screen plan D7): a window, by the name it announced,
 * or a control surface. It is the *last mover*, not a lock — a press never touches it, and a chip
 * reading "from Screen 1" an hour later is still true.
 *
 * [kind] is [KIND_WINDOW] or [KIND_SURFACE]. [id] is the writing window's **registry row id**
 * ([WindowRegistry.Window.id], socket-minted) — the id `windows.show` addresses, not the
 * client-minted `windowId`, which a duplicated tab shares (plan D9), so the two agree on what
 * "that window" means. It is null for a control surface, and for a socket that has not announced
 * and is naming itself with session 1's `sourceName` stub (`FU-WINDOWS-RETIRE-SOURCENAME`).
 */
@Serializable
data class SelectionSource(
    val kind: String,
    val id: String? = null,
    val name: String,
) {
    companion object {
        const val KIND_WINDOW = "window"
        const val KIND_SURFACE = "surface"

        /**
         * A MIDI write. One value for every surface: the input router hands the actions no device
         * identity, and to the chip every surface is "the desk".
         */
        val SURFACE = SelectionSource(KIND_SURFACE, null, "Control surface")

        fun window(name: String, id: String? = null) = SelectionSource(KIND_WINDOW, id, name)
    }
}

/**
 * The desk's selection: the groups and fixtures a selection-relative surface control (a
 * `SelectionProperty` fader, a `LocateSelection` button) and a busk-page press act on, the
 * attribute families that press is masked to, and who moved it last.
 *
 * **One desk, one selection, server-owned.** The composition model's "exactly one programmer,
 * shared by every client" argument applies verbatim (`docs/lighting-composition-model.md`
 * §"Layer 2"): DMX is one byte per channel, so two selections would force a "whose press wins?"
 * policy nothing expresses. Every client — the busk view, the programmer page, the X-Touch's
 * select buttons — reads and writes this one fact, and the `selection.state` frame keeps them
 * honest. It is transient: cleared on project switch, never persisted, and outside the sync
 * decision tree's portable branch because it is not a table.
 *
 * **The mask is part of the selection, not a second fact** (multi-screen plan D2). A mask without
 * targets means nothing and clearing the selection must clear it, so the two travel as one
 * [Snapshot] and one frame. The rule that keeps them one fact: [set] replaces the whole fact
 * (targets *and* families — an absent mask is every attribute), [toggle] edits the heads and
 * keeps the mask, [clear] drops both, [prune] keeps both. So a marquee publishes both halves; a
 * tap on the busk band or an X-Touch select button adds a head under whatever mask is standing;
 * a `SelectTarget(REPLACE)` button replaces the heads and, having no column axis to speak with,
 * clears the mask by passing none.
 *
 * [Snapshot.targets] is ordered as added and never holds a duplicate spelling. A group and one of
 * its members may both be present — they are two spellings, and [coverage] is how the two are
 * compared. Every mutation is one [MutableStateFlow.update], so one socket frame; a mutation
 * that changes nothing emits nothing.
 */
/**
 * The ways `selection.subselect` rewrites the selection's **targets** (busk-further plan D12) — the
 * Cells chip's face and menu, and a MIDI `SelectionCells` / `SelectionNext` / `SelectionPrev`
 * button, sharing one rule on the desk. See [DeskSelection.subselect].
 */
@Serializable
enum class SubselectMode {
    /** Every selected cell widened to its whole fixture; heads and groups kept as written. */
    ALL,

    /** The 1st, 3rd, 5th … unit in rig order. */
    ODD,

    /** The 2nd, 4th, 6th … unit in rig order. */
    EVEN,

    /** The first half of the units (the larger half when odd). */
    FIRST_HALF,

    /** The rest. */
    SECOND_HALF,

    /** Every unit of the rig the selection does not cover, at the selection's granularity. */
    INVERT,

    /** The whole selection one step along rig order, wrapping; an empty selection lands on the first step. */
    NEXT,

    /** The whole selection one step back, wrapping; an empty selection lands on the last step. */
    PREV,

    /** Every cell dropped; the parents and groups that were written stay. */
    MASTERS,
    ;

    companion object {
        fun byName(name: String): SubselectMode? = entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
    }
}

class DeskSelection(
    /**
     * The effective rig order (`BuskRigOrder`) for [subselect]'s *Next* / *Prev* and *Invert*, and
     * for the order the odd / even / half modes count in. A provider, because the rig is read from
     * the tables at gesture time and the fixtures are per show; null when there is no show yet,
     * which makes the stepping modes no-ops and the others count in the order written. First so
     * that [fixtures] stays the trailing lambda every existing caller passes.
     */
    private val rigOrder: () -> BuskRigOrder? = { null },
    fixtures: () -> Fixtures?,
) {
    private val fixturesProvider = fixtures
    private val coverageRule = TargetCoverage(fixtures)

    /**
     * The whole fact. [families] null is *every attribute* — the only spelling of "no mask", so
     * `set(families = emptySet())` and a set naming all four both land as null. [source] is null
     * after [clear] and a project switch, and after a write from a socket that has no name yet.
     */
    data class Snapshot(
        val targets: List<CueTargetDto> = emptyList(),
        val families: Set<PropertyMaskGroup>? = null,
        val source: SelectionSource? = null,
    )

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    /**
     * Replace the whole fact: targets, mask and mover. Duplicates collapse, order is the caller's.
     * An absent [families] is every attribute — a replace that kept the standing mask would let a
     * surface's `REPLACE` carry a stale Colour mask onto heads picked with no column in mind.
     */
    fun set(targets: List<CueTargetDto>, families: Set<PropertyMaskGroup>? = null, source: SelectionSource? = null) {
        val next = Snapshot(targets.distinct(), normalise(families), source)
        _state.update { current -> if (current == next) current else next }
    }

    /** Nothing selected, no mask, no mover — the snapshot a project switch also lands on. */
    fun clear() {
        val next = Snapshot()
        _state.update { current -> if (current == next) current else next }
    }

    /**
     * Toggle one target, head by head, under the standing mask.
     *
     * When every head [target] covers is already selected — a group by its members counts —
     * the press takes those heads off: an entry the press does not touch is kept as written, and
     * a group it only partly covers is rewritten as the members left behind ([TargetCoverage.narrow],
     * the same rule a busk press applies to a sibling layer). Otherwise the target is appended.
     * The mask is kept either way; [source] becomes the mover. Returns true when the target is
     * selected afterwards.
     */
    fun toggle(target: CueTargetDto, source: SelectionSource? = null): Boolean {
        var selected = false
        _state.update { current ->
            val targets = current.targets
            val next = if (covers(targets, target)) {
                selected = false
                coverageRule.narrow(targets, coverageRule.expand(listOf(target)).toSet())
            } else {
                selected = true
                if (target in targets) targets else targets + target
            }
            current.copy(targets = next, source = source)
        }
        return selected
    }

    /**
     * Rewrite the selection's targets by [mode] (busk-further plan D12, §3.5). The mask is kept and
     * [source] becomes the mover; a rewrite that changes nothing emits nothing.
     *
     * The **unit** the odd / even / half modes count is decided by what is selected: over the
     * selection's **cells** where any selected head has elements (or is itself a cell), over heads
     * where none does. Units are counted in rig order — a group's members in member order, a
     * fixture's cells in element order — so *Odd* on a group of bars is every other cell across the
     * bars, not every other bar. [SubselectMode.INVERT] takes the rig as its universe at that same
     * granularity. [SubselectMode.NEXT] / [SubselectMode.PREV] step the whole selection one **step**
     * of the rig (a group tile is one step, a `PER_CELL` tile one per cell, a `HALVES` tile one per
     * half), at cell granularity when every selected target is a cell — so *Next* on one pip moves
     * to the next pip — and wrapping at either end. Cells are read through `TargetCoverage`, never
     * parsed.
     */
    fun subselect(mode: SubselectMode, source: SelectionSource? = null) {
        // The rig read is a database transaction in production. Taken once, outside the atomic
        // update — whose lambda re-runs on a lost compare-and-set — and only for the modes that
        // count along the rig; `ALL` and `MASTERS` are pure rewrites of the written targets.
        val order = if (mode.readsRig) rigOrder() else null
        _state.update { current ->
            val next = rewrite(current.targets, mode, order) ?: return@update current
            if (next == current.targets) current else current.copy(targets = next, source = source)
        }
    }

    private val SubselectMode.readsRig: Boolean
        get() = this != SubselectMode.ALL && this != SubselectMode.MASTERS

    /** The rewritten targets, or null when [mode] cannot act (no rig order for a stepping mode). */
    private fun rewrite(targets: List<CueTargetDto>, mode: SubselectMode, order: BuskRigOrder?): List<CueTargetDto>? {
        return when (mode) {
            SubselectMode.ALL -> targets.map { coverageRule.parentOf(it) ?: it }.distinct()
            SubselectMode.MASTERS -> targets.filter { coverageRule.parentOf(it) == null }
            SubselectMode.ODD, SubselectMode.EVEN, SubselectMode.FIRST_HALF, SubselectMode.SECOND_HALF -> {
                val units = units(targets, order)
                val half = (units.size + 1) / 2
                when (mode) {
                    SubselectMode.ODD -> units.filterIndexed { i, _ -> i % 2 == 0 }
                    SubselectMode.EVEN -> units.filterIndexed { i, _ -> i % 2 == 1 }
                    SubselectMode.FIRST_HALF -> units.take(half)
                    else -> units.drop(half)
                }
            }
            SubselectMode.INVERT -> {
                val cellular = isCellular(targets)
                val universe = if (order != null) {
                    order.steps(if (cellular) SpreadOver.CELLS else SpreadOver.HEADS).flatten()
                } else {
                    fixturesProvider()?.fixtures?.map { CueTargetDto(TargetRef.Fixture.TYPE, it.key) }.orEmpty()
                }
                val selected = coverageRule.expand(targets).toSet()
                unitsOf(coverageRule.expand(universe), cellular).filterNot { coverageRule.covers(selected, it) }
            }
            SubselectMode.NEXT, SubselectMode.PREV -> {
                if (order == null) return null
                val cellular = targets.isNotEmpty() && targets.all { coverageRule.parentOf(it) != null }
                val steps = order.steps(if (cellular) SpreadOver.CELLS else SpreadOver.HEADS)
                if (steps.isEmpty()) return null
                // A step is occupied when the selection covers every target on it — and not when a
                // *larger* occupied step already covers it: with a group selected, its member's own
                // tile is lit too, but the selection is one thing and steps as one thing.
                val covered = steps.indices.filter { i -> steps[i].all { covers(targets, it) } }
                // Each covered step expanded once, not once per pair: with everything selected this
                // is every step of the band, and the pairwise test below is over all of them.
                val expanded = covered.associateWith { coverageRule.expand(steps[it]).toSet() }
                val occupied = covered.filter { i ->
                    covered.none { j -> j != i && stepSubsumes(steps[j], expanded.getValue(j), steps[i], expanded.getValue(i)) }
                }
                val delta = if (mode == SubselectMode.NEXT) 1 else -1
                val moved = if (occupied.isEmpty()) {
                    listOf(if (mode == SubselectMode.NEXT) 0 else steps.lastIndex)
                } else {
                    occupied.map { (it + delta + steps.size) % steps.size }.sorted()
                }
                moved.flatMap { steps[it] }.distinct()
            }
        }
    }

    /**
     * True when [outer] covers every target of [inner] and [inner] does not cover all of [outer];
     * [outerHeads] / [innerHeads] are the two steps' expansions, computed once by the caller.
     */
    private fun stepSubsumes(
        outer: List<CueTargetDto>,
        outerHeads: Set<CueTargetDto>,
        inner: List<CueTargetDto>,
        innerHeads: Set<CueTargetDto>,
    ): Boolean {
        if (!inner.all { coverageRule.covers(outerHeads, it) }) return false
        return !outer.all { coverageRule.covers(innerHeads, it) }
    }

    /** True when the selection is at cell granularity: any selected head has cells, or is one. */
    private fun isCellular(targets: List<CueTargetDto>): Boolean =
        coverageRule.expand(targets).any { coverageRule.parentOf(it) != null || coverageRule.cells(it).isNotEmpty() }

    /** The selection's units in rig order: its heads, or their cells when [isCellular]. */
    private fun units(targets: List<CueTargetDto>, order: BuskRigOrder?): List<CueTargetDto> {
        val heads = coverageRule.expand(targets).distinct()
        val ordered = order?.sort(heads) ?: heads
        return unitsOf(ordered, isCellular(targets))
    }

    private fun unitsOf(heads: List<CueTargetDto>, cellular: Boolean): List<CueTargetDto> =
        if (!cellular) heads.distinct()
        else heads.flatMap { head -> coverageRule.cells(head).ifEmpty { listOf(head) } }.distinct()

    /** The selection with every group expanded to its member fixtures. */
    fun coverage(): List<CueTargetDto> = coverageRule.expand(_state.value.targets)

    /** True when every head [target] covers is selected — what a select button's LED shows. */
    fun covers(target: CueTargetDto): Boolean = covers(_state.value.targets, target)

    private fun covers(selection: List<CueTargetDto>, target: CueTargetDto): Boolean {
        if (selection.isEmpty()) return false
        val heads = coverageRule.expand(listOf(target))
        val selected = coverageRule.expand(selection).toSet()
        // Per head through [TargetCoverage.covers], so a cell under a selected bar counts as in.
        return heads.all { coverageRule.covers(selected, it) }
    }

    /**
     * Drop every entry that no longer resolves in the patch, keeping the rest in order — the
     * same rule a press applies to a stale target. Called on fixture reload. The mask and the
     * mover are kept: a repatch is not a gesture, so nobody new moved the selection.
     */
    fun prune() {
        val fixtures = fixturesProvider() ?: return
        _state.update { current ->
            val kept = current.targets.filter { resolves(fixtures, it) }
            if (kept.size == current.targets.size) current else current.copy(targets = kept)
        }
    }

    private fun resolves(fixtures: Fixtures, target: CueTargetDto): Boolean =
        when (TargetRef.ofOrNull(target.type, target.key)) {
            // `untypedGroupableFixture`: a selected cell survives a reload as its parent does.
            is TargetRef.Fixture -> runCatching { fixtures.untypedGroupableFixture(target.key) }.isSuccess
            is TargetRef.Group -> runCatching { fixtures.untypedGroup(target.key) }.isSuccess
            null -> false
        }

    /** One spelling of "no mask": empty and complete both collapse to null, as `parseMaskGroups` does. */
    private fun normalise(families: Set<PropertyMaskGroup>?): Set<PropertyMaskGroup>? =
        families?.takeUnless { it.isEmpty() || it.size == PropertyMaskGroup.entries.size }
}
