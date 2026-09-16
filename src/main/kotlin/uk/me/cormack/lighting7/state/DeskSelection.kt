package uk.me.cormack.lighting7.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.fx.PropertyMaskGroup
import uk.me.cormack.lighting7.fx.TargetCoverage
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.show.Fixtures

/**
 * Who moved the desk selection last (multi-screen plan D7): a window, by the name it announced,
 * or a control surface. It is the *last mover*, not a lock — a press never touches it, and a chip
 * reading "from Screen 1" an hour later is still true.
 *
 * [kind] is [KIND_WINDOW] or [KIND_SURFACE]. [id] is the window's socket-minted identity once
 * the windows registry exists (session 2); this session every window is name-only, so it is null.
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
class DeskSelection(fixtures: () -> Fixtures?) {
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

    /** The selection with every group expanded to its member fixtures. */
    fun coverage(): List<CueTargetDto> = coverageRule.expand(_state.value.targets)

    /** True when every head [target] covers is selected — what a select button's LED shows. */
    fun covers(target: CueTargetDto): Boolean = covers(_state.value.targets, target)

    private fun covers(selection: List<CueTargetDto>, target: CueTargetDto): Boolean {
        if (selection.isEmpty()) return false
        val heads = coverageRule.expand(listOf(target))
        val selected = coverageRule.expand(selection).toSet()
        return heads.all { it in selected }
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
            is TargetRef.Fixture -> runCatching { fixtures.untypedFixture(target.key) }.isSuccess
            is TargetRef.Group -> runCatching { fixtures.untypedGroup(target.key) }.isSuccess
            null -> false
        }

    /** One spelling of "no mask": empty and complete both collapse to null, as `parseMaskGroups` does. */
    private fun normalise(families: Set<PropertyMaskGroup>?): Set<PropertyMaskGroup>? =
        families?.takeUnless { it.isEmpty() || it.size == PropertyMaskGroup.entries.size }
}
