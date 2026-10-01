package uk.me.cormack.lighting7.show

import uk.me.cormack.lighting7.models.ElementStates
import java.util.UUID

/**
 * What the stage's scenery shows (stage-view plan session 8): each element's states — `visible`, a
 * drawn drape's `open`, a flown piece's `trimM` — resolved from the show's scenery changes. Pure: the
 * service ([uk.me.cormack.lighting7.state.SceneryService]) reads the desk's live state and the rows,
 * this decides, and a unit test pins the order.
 *
 * **Scenery tracks; lighting does not.** A cue is a complete lighting state, but a tab closed at
 * Q14 stays closed at Q15 unless something opens it. So a stack's contribution is computed from its
 * cue **list**, not its history: every change from the top of the list down to the live cue, the
 * last one winning — GO TO Q20 lands the set as if the list had been run.
 *
 * Per element and per state, highest last:
 *
 * 1. the element's **base** (its own `params.states`);
 * 2. each live stack's **set**, then its **cues** from the top of the list down to the live cue —
 *    stacks folded oldest GO first, so with two stacks live the most recently GO'd wins (not the
 *    lighting resolver's database-id order);
 * 3. the **Looks layered in the live cues**, stack by stack in the same order, in layer order;
 * 4. the **Looks live in the programmer** — pressed, or on a busk pad — in layer order.
 *
 * "Live Looks win over the stack's cues and its set" is the design's rule (`StacksLooks.dc.html`),
 * so 3 and 4 sit above every cue. A state an element's kind cannot take (a drape no longer drawn) is
 * ignored, so an element edited after its scenery was written never moves wrongly.
 */
object SceneryResolver {
    /** One element as the resolver needs it: which states it takes, and its base values for them. */
    data class Element(
        val id: Int,
        val uuid: UUID,
        val name: String,
        /** `visible`, plus `open` for a drawn drape and `trimM` for a flown piece. */
        val keys: Set<String>,
        /** Every key in [keys] with its base value. */
        val base: ElementStates,
    )

    /** One change as a stack's set or a Look holds it. */
    data class Change(val elementId: Int, val state: ElementStates)

    /** One change of a cue, with the clock it moves on: its own transition, else the cue's fade. */
    data class CueChange(val elementId: Int, val state: ElementStates, val transitionMs: Long)

    data class Cue(val cueId: Int, val label: String, val changes: List<CueChange>)

    /**
     * A live stack: its [set], its cues from the top of the list **down to and including** the live
     * cue, in list order ([cues]), and the Looks the live cue layers, in layer order. [goSeq] orders
     * stacks by when they last went.
     */
    data class LiveStack(
        val stackId: Int,
        val goSeq: Long,
        val set: List<Change>,
        val cues: List<Cue>,
        val layeredLookIds: List<Int>,
    )

    /** Where a resolved value came from, which decides how long a move to it takes. */
    sealed interface Source {
        data object Base : Source
        data class StackSet(val stackId: Int) : Source
        data class CueRow(val stackId: Int, val cueId: Int, val cueLabel: String, val transitionMs: Long) : Source
        data class CueLook(val stackId: Int, val lookId: Int) : Source
        data class ProgrammerLook(val lookId: Int) : Source
    }

    data class Resolved(
        val element: Element,
        val state: ElementStates,
        /** Per state key, what decided it. */
        val sources: Map<String, Source>,
    )

    fun resolve(
        elements: Collection<Element>,
        stacks: List<LiveStack>,
        lookScenery: Map<Int, List<Change>>,
        programmerLookIds: List<Int>,
    ): Map<Int, Resolved> {
        val folds = elements.associate { it.id to Fold(it) }
        fun apply(change: Change, source: Source) = folds[change.elementId]?.apply(change.state, source)

        val ordered = stacks.sortedWith(compareBy({ it.goSeq }, { it.stackId }))
        for (stack in ordered) {
            stack.set.forEach { apply(it, Source.StackSet(stack.stackId)) }
            for (cue in stack.cues) {
                for (c in cue.changes) {
                    folds[c.elementId]?.apply(c.state, Source.CueRow(stack.stackId, cue.cueId, cue.label, c.transitionMs))
                }
            }
        }
        for (stack in ordered) {
            for (lookId in stack.layeredLookIds) {
                lookScenery[lookId].orEmpty().forEach { apply(it, Source.CueLook(stack.stackId, lookId)) }
            }
        }
        for (lookId in programmerLookIds) {
            lookScenery[lookId].orEmpty().forEach { apply(it, Source.ProgrammerLook(lookId)) }
        }
        return folds.mapValues { it.value.result() }
    }

    private class Fold(val element: Element) {
        var visible = element.base.visible
        var open = element.base.open
        var trim = element.base.trimM
        val sources = HashMap<String, Source>().apply { element.keys.forEach { put(it, Source.Base) } }

        fun apply(state: ElementStates, source: Source) {
            if (state.visible != null && "visible" in element.keys) { visible = state.visible; sources["visible"] = source }
            if (state.open != null && "open" in element.keys) { open = state.open; sources["open"] = source }
            if (state.trimM != null && "trimM" in element.keys) { trim = state.trimM; sources["trimM"] = source }
        }

        fun result() = Resolved(element, ElementStates(visible, open, trim), sources.toMap())
    }

    /**
     * Each state of [element] at the cue [cueId] of a stack, as its card shows it: what the cue's own
     * changes set is the cue's; everything else is **tracked** — from the last earlier cue that moved
     * it, or the stack's [set] — and comes back here with where it came from. Elements nothing in the
     * stack names are left out. [cues] is the whole stack in list order.
     */
    fun trackedAt(
        elements: Collection<Element>,
        set: List<Change>,
        cues: List<Cue>,
        cueId: Int,
    ): List<Pair<Resolved, Map<String, Source>>> {
        val upTo = cues.indexOfFirst { it.cueId == cueId }.takeIf { it >= 0 } ?: return emptyList()
        val own = cues[upTo].changes.associateBy { it.elementId }
        val named = (set.map { it.elementId } + cues.take(upTo + 1).flatMap { c -> c.changes.map { it.elementId } }).toSet()
        val resolved = resolve(
            elements.filter { it.id in named },
            listOf(LiveStack(stackId = 0, goSeq = 0, set = set, cues = cues.take(upTo + 1), layeredLookIds = emptyList())),
            emptyMap(),
            emptyList(),
        )
        return resolved.values
            .sortedBy { it.element.name.lowercase() }
            .mapNotNull { r ->
                // The keys the cue itself sets are its own changes, listed above the tracked ones.
                val ownKeys = own[r.element.id]?.state?.let(::keysOf).orEmpty()
                val tracked = r.sources.filter { (k, s) -> k !in ownKeys && s != Source.Base }
                if (tracked.isEmpty()) null else r to tracked
            }
    }

    fun keysOf(state: ElementStates): Set<String> = buildSet {
        if (state.visible != null) add("visible")
        if (state.open != null) add("open")
        if (state.trimM != null) add("trimM")
    }

    /** Only [keys] of [state]. */
    fun only(state: ElementStates, keys: Set<String>) = ElementStates(
        visible = state.visible.takeIf { "visible" in keys },
        open = state.open.takeIf { "open" in keys },
        trimM = state.trimM.takeIf { "trimM" in keys },
    )
}
