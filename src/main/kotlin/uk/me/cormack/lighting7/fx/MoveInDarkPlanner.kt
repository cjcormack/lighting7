package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.fixture.AttributeCategory
import uk.me.cormack.lighting7.models.MoveInDarkSettings
import uk.me.cormack.lighting7.models.MoveInDarkWhen
import java.time.Duration

/**
 * Which dark heads a stack moves, and where to (move-in-dark plan D5, §3 step 1). Pure: it reads the
 * stack's list, its settings and what each cue's own rows say, never the output — whether a head is
 * dark now is [MoveInDarkService]'s question.
 *
 * From the live entry it walks the play order — the effective next (the armed standby, else the
 * positional next), then on positionally, wrapping on a looping stack, at most once round the list
 * and never back onto the live entry — and gives each head the **first** entry whose cue lights it:
 * an intensity row above 0 on the head (or its parent's dimmer; for a parent lit only through its
 * elements, on one of them), or an Intensity FX on it. *Late*
 * looks at the effective next alone. A cue that does not light a head is walked through, so a head
 * dark across two cues is moved for the third.
 *
 * What moves is only what the target cue's rows set on the head, in the families the stack moves.
 * Nothing that lights a head moves: DIMMER, STROBE, UV, an LED ring or macro, a colour carrying UV, and
 * Colour on a head whose colour is its intensity ([HeadIntensity.colourIsIntensity]). A head whose target sets nothing that
 * may move is not in the plan.
 */
object MoveInDarkPlanner {

    /** What one cue's own rows and FX say, as the planner reads them. */
    class CueContent(
        /** The cue's cooked rows, groups expanded — `buildCombinedCueLayerRows`'. */
        val rows: List<CueAssignmentResolver.Assignment>,
        /** Head keys (fixtures or elements) an Intensity FX of the cue drives; a parent's key lights its elements. */
        val intensityFxHeads: Set<String> = emptySet(),
    )

    class Input(
        val settings: MoveInDarkSettings,
        /** The stack's cue entries in play order, markers left out. */
        val ordered: List<CueEntryRef>,
        val liveEntryId: Int,
        /** What the next GO fires: `CueRunStateTracker.effectiveNext`. */
        val next: CueEntryRef?,
        val loop: Boolean,
        val heads: Map<String, HeadIntensity>,
        val content: (cueId: Int) -> CueContent,
    )

    /** One head's planned move: the entry it is for, and the target cue's rows that move. */
    data class HeadMove(
        val head: String,
        val targetEntryId: Int,
        val targetCueId: Int,
        val rows: List<CueAssignmentResolver.Assignment>,
        val delay: Duration,
        val time: Duration,
    )

    /**
     * A stack's plan: its [moves], and the heads its live cue lights ([litByLive]), which a GO back
     * onto a cue that lights them must let go of whatever the walk ahead says.
     */
    data class Plan(
        val moves: Map<String, HeadMove>,
        val litByLive: Set<String>,
        /** The stack's Move in dark is off: nothing to plan until its settings change. */
        val off: Boolean = false,
    )

    /** The heads [content] lights. */
    fun litHeads(content: CueContent, heads: Map<String, HeadIntensity>): Set<String> {
        val byHead = content.rows.groupBy { it.targetKey }
        return heads.filterValues { lights(it, heads, byHead, content.intensityFxHeads) }.keys
    }

    /** Every head the stack will move, by key. Empty when the stack is off or nothing ahead lights a dark head. */
    fun plan(input: Input): Map<String, HeadMove> {
        if (!input.settings.on) return emptyMap()
        val candidates = candidates(input)
        if (candidates.isEmpty()) return emptyMap()
        val decided = HashSet<String>()
        val plan = LinkedHashMap<String, HeadMove>()
        for (entry in candidates) {
            if (decided.size == input.heads.size) break
            val content = input.content(entry.cueId)
            val byHead = content.rows.groupBy { it.targetKey }
            for ((key, head) in input.heads) {
                if (key in decided) continue
                if (!lights(head, input.heads, byHead, content.intensityFxHeads)) continue
                decided += key
                val rows = byHead[key].orEmpty().filter { moves(it, head, input.settings) }
                if (rows.isEmpty()) continue
                plan[key] = HeadMove(key, entry.entryId, entry.cueId, rows, input.settings.delay, input.settings.time)
            }
        }
        return plan
    }

    /** The entries a head may be moved for, nearest first (D5). */
    internal fun candidates(input: Input): List<CueEntryRef> {
        val next = input.next ?: return emptyList()
        if (next.entryId == input.liveEntryId) return emptyList()
        if (input.settings.whenMode == MoveInDarkWhen.LATE) return listOf(next)
        val ordered = input.ordered
        val start = ordered.indexOfFirst { it.entryId == next.entryId }
        if (start < 0) return listOf(next)
        val walk = ArrayList<CueEntryRef>()
        var index = start
        repeat(ordered.size) {
            val entry = ordered[index]
            if (entry.entryId == input.liveEntryId) return walk
            walk += entry
            index++
            if (index >= ordered.size) {
                if (!input.loop) return walk
                index = 0
            }
        }
        return walk
    }

    /** Whether a cue's [byHead] rows or [intensityFx] light [head]. */
    private fun lights(
        head: HeadIntensity,
        heads: Map<String, HeadIntensity>,
        byHead: Map<String, List<CueAssignmentResolver.Assignment>>,
        intensityFx: Set<String>,
    ): Boolean {
        if (head.headKey in intensityFx) return true
        val parent = head.parentKey
        if (parent != null && parent in intensityFx) return true
        if (byHead[head.headKey].orEmpty().any { lightsOwn(it, head) }) return true
        for (key in head.elementKeys) {
            val element = heads[key] ?: continue
            if (key in intensityFx || byHead[key].orEmpty().any { lightsOwn(it, element) }) return true
        }
        if (parent == null) return false
        return byHead[parent].orEmpty().any { it.category == AttributeCategory.DIMMER && above0(it.value) }
    }

    private fun lightsOwn(row: CueAssignmentResolver.Assignment, head: HeadIntensity): Boolean = when (row.category) {
        AttributeCategory.DIMMER, AttributeCategory.UV -> above0(row.value)
        AttributeCategory.COLOUR, AttributeCategory.WHITE, AttributeCategory.AMBER -> head.colourIsIntensity && above0(row.value)
        else -> false
    }

    private fun above0(value: CueAssignmentResolver.AttributeValue): Boolean = when (value) {
        is CueAssignmentResolver.AttributeValue.Slider -> value.value.toInt() > 0
        is CueAssignmentResolver.AttributeValue.Setting -> value.channelValue.toInt() > 0
        is CueAssignmentResolver.AttributeValue.Colour -> value.value.let {
            it.color.red > 0 || it.color.green > 0 || it.color.blue > 0 ||
                it.white.toInt() > 0 || it.amber.toInt() > 0 || it.uv.toInt() > 0
        }
        is CueAssignmentResolver.AttributeValue.Position -> false
    }

    /** Whether [row] is one a move may carry on [head] (D5). */
    internal fun moves(row: CueAssignmentResolver.Assignment, head: HeadIntensity, settings: MoveInDarkSettings): Boolean {
        val category = row.category
        if (category in NEVER_MOVES) return false
        val family = if (row.attribute.equals("position", ignoreCase = true)) AttributeFamily.POSITION else category.family()
        if (family == AttributeFamily.COLOUR && head.colourIsIntensity) return false
        // A colour carrying UV would light the UV emitter in the dark: it waits for its GO.
        val colour = row.value as? CueAssignmentResolver.AttributeValue.Colour
        if (colour != null && colour.value.uv.toInt() > 0) return false
        return family in settings.families
    }

    /** What lights a head, and so never moves: its dimmer, strobe, UV, an LED ring or macro. */
    private val NEVER_MOVES = setOf(
        AttributeCategory.DIMMER, AttributeCategory.STROBE, AttributeCategory.UV,
        AttributeCategory.RING, AttributeCategory.RING_SPEED, AttributeCategory.LED_MACRO,
    )
}
