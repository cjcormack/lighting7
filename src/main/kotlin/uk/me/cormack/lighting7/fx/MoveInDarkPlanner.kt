package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.fixture.AttributeCategory
import uk.me.cormack.lighting7.models.CueMoveInDarkMode
import uk.me.cormack.lighting7.models.CueMoveInDarkSettings
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
 * elements, on one of them), or an Intensity FX on it. A cue that does not light a head is walked
 * through, so a head dark across two cues is moved for the third.
 *
 * Whether, when and how fast a head moves is its **target cue's** setting (D3,
 * [CueMoveInDarkSettings.effective]): *Stack* follows the stack's, *Early* and *Late* apply even on a
 * stack that is off, and *Off* — or a head the cue keeps live — moves in the GO's fade, lit. *Late*
 * moves a head only for the effective next. A delay or move time the cue sets is its own; else the
 * stack's.
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
        /** The cue's own setting (D3), read when it is a head's target. */
        val moveInDark: CueMoveInDarkSettings = CueMoveInDarkSettings.DEFAULT,
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
        /** How a person names the target: `Q12`, or the cue's name where its entry has no number. */
        val label: String = "",
    )

    /**
     * A stack's plan: its [moves], and the heads its live cue lights ([litByLive]), which a GO back
     * onto a cue that lights them must let go of whatever the walk ahead says.
     */
    data class Plan(
        val moves: Map<String, HeadMove>,
        val litByLive: Set<String>,
        /** Neither the stack nor any cue of its list moves a head: nothing to plan until one changes. */
        val off: Boolean = false,
    )

    /** The heads [content] lights. */
    fun litHeads(content: CueContent, heads: Map<String, HeadIntensity>): Set<String> {
        val byHead = content.rows.groupBy { it.targetKey }
        return heads.filterValues { lights(it, heads, byHead, content.intensityFxHeads) }.keys
    }

    /**
     * Every head the stack will move, by key. Empty when nothing ahead lights a dark head, or when no
     * target cue's setting moves it (the stack off and its cues on *Stack*, say).
     */
    fun plan(input: Input): Map<String, HeadMove> {
        val candidates = candidates(input)
        if (candidates.isEmpty()) return emptyMap()
        val decided = HashSet<String>()
        val plan = LinkedHashMap<String, HeadMove>()
        for ((index, entry) in candidates.withIndex()) {
            if (decided.size == input.heads.size) break
            val content = input.content(entry.cueId)
            val effective = content.moveInDark.effective(input.settings)
            val byHead = content.rows.groupBy { it.targetKey }
            for ((key, head) in input.heads) {
                if (key in decided) continue
                if (!lights(head, input.heads, byHead, content.intensityFxHeads)) continue
                decided += key
                if (effective == null || content.moveInDark.keepsLive(key, head.parentKey)) continue
                if (effective.whenMode == MoveInDarkWhen.LATE && index > 0) continue
                val rows = byHead[key].orEmpty().filter { moves(it, head, effective) }
                if (rows.isEmpty()) continue
                plan[key] = HeadMove(key, entry.entryId, entry.cueId, rows, effective.delay, effective.time)
            }
        }
        return plan
    }

    /**
     * The entries a head may be moved for, nearest first (D5): the whole walk, since a target cue's
     * own *Early* reaches past the next GO whatever the stack says.
     */
    internal fun candidates(input: Input): List<CueEntryRef> {
        val next = input.next ?: return emptyList()
        if (next.entryId == input.liveEntryId) return emptyList()
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


    // ─── The static plan ────────────────────────────────────────────────────────────────────

    /** Why a head the target cue lights will move in view rather than in the dark (§3). */
    enum class LiveReason {
        /** The entry before the target lights it too: there is no dark to move in. */
        LIT_BEFORE,
        /** Followed into the target too soon for the delay and the move time. */
        TOO_QUICK,
        /** The target cue keeps it live, a fly-in. */
        KEPT_LIVE,
        /** The target cue is *Off*. */
        CUE_OFF,
    }

    /** A head moved in the dark for an entry: the entry its move happens in, and what it moves. */
    data class StaticMove(val head: String, val fromEntryId: Int, val families: Set<AttributeFamily>)

    /** A head that will move lit into an entry, and why. */
    data class StaticLive(val head: String, val reason: LiveReason)

    /** One cue entry of the static plan: the heads moved in the dark for it, and the ones that move lit. */
    data class StaticEntry(val entryId: Int, val cueId: Int, val moves: List<StaticMove>, val live: List<StaticLive>)

    class StaticInput(
        val settings: MoveInDarkSettings,
        /** The stack's cue entries in play order, markers left out. */
        val ordered: List<CueEntryRef>,
        val loop: Boolean,
        val heads: Map<String, HeadIntensity>,
        val content: (cueId: Int) -> CueContent,
        /** An entry's follow: its delay when it auto-advances, else null (a manual GO). */
        val followMs: (entryId: Int) -> Long?,
        /** A cue's fade. */
        val fadeMs: (cueId: Int) -> Long,
    )

    /**
     * The plan the stored list makes, entry by entry, with no other stack running (§3): for each entry,
     * every head its cue lights that a move would carry, either moved in the dark — from the first
     * entry it is dark in (the entry before it under *Late*) — or listed as moving lit, with the
     * reason. A head lit by the entry before with the same values moves not at all and is in neither
     * list; so is every head of a cue whose setting moves nothing (the stack off and the cue on
     * *Stack*), and of the first entry of a list that does not loop.
     *
     * *Too quick* counts only follows: the head is dark from its first dark entry's GO (less that cue's
     * fade, when it takes the head out) to the target's, and a manual GO in that span leaves the time
     * open.
     */
    fun staticPlan(input: StaticInput): List<StaticEntry> {
        val ordered = input.ordered
        val lit = HashMap<Int, Set<String>>()
        fun litBy(cueId: Int): Set<String> = lit.getOrPut(cueId) { litHeads(input.content(cueId), input.heads) }
        fun before(index: Int): Int? = when {
            index > 0 -> index - 1
            input.loop && ordered.size > 1 -> ordered.lastIndex
            else -> null
        }
        return ordered.mapIndexed { t, target ->
            val content = input.content(target.cueId)
            val setting = content.moveInDark
            val effective = setting.effective(input.settings)
            val families = (effective ?: input.settings).families
            val byHead = content.rows.groupBy { it.targetKey }
            val moves = ArrayList<StaticMove>()
            val live = ArrayList<StaticLive>()
            val previous = before(t)
            val engaged = effective != null || setting.mode == CueMoveInDarkMode.OFF
            if (previous != null && engaged) for (key in litBy(target.cueId)) {
                val head = input.heads.getValue(key)
                val rows = byHead[key].orEmpty().filter { moves(it, head, families) }
                if (rows.isEmpty()) continue
                val previousCue = ordered[previous].cueId
                if (key in litBy(previousCue)) {
                    val there = input.content(previousCue).rows.filter { it.targetKey == key }.associate { it.attribute to it.value }
                    if (rows.any { there[it.attribute] != it.value }) live += StaticLive(key, LiveReason.LIT_BEFORE)
                    continue
                }
                when {
                    effective == null -> live += StaticLive(key, LiveReason.CUE_OFF)
                    setting.keepsLive(key, head.parentKey) -> live += StaticLive(key, LiveReason.KEPT_LIVE)
                    else -> {
                        // The first entry it is dark in, walking back to the last one that lights it —
                        // round to the target itself on a loop, which lights it too.
                        var first: Int = previous
                        var takenOut = false
                        while (true) {
                            val back = before(first) ?: break
                            if (back == t || key in litBy(ordered[back].cueId)) { takenOut = true; break }
                            first = back
                        }
                        val from = if (effective.whenMode == MoveInDarkWhen.LATE) previous else first
                        val dark = darkMs(input, ordered, from, t, takenOut && from == first)
                        if (dark != null && dark < (effective.delay + effective.time).toMillis()) {
                            live += StaticLive(key, LiveReason.TOO_QUICK)
                        } else {
                            moves += StaticMove(key, ordered[from].entryId, rows.mapTo(LinkedHashSet()) { familyOf(it) })
                        }
                    }
                }
            }
            StaticEntry(target.entryId, target.cueId, moves, live)
        }
    }

    /**
     * How long a head is dark from [from]'s GO to [target]'s when every GO between is a follow, or null
     * when one is manual. [takenOut]: [from]'s cue fades the head out, so its fade comes off.
     */
    private fun darkMs(input: StaticInput, ordered: List<CueEntryRef>, from: Int, target: Int, takenOut: Boolean): Long? {
        var total = 0L
        var index = from
        while (index != target) {
            total += input.followMs(ordered[index].entryId) ?: return null
            index = if (index == ordered.lastIndex) 0 else index + 1
        }
        if (takenOut) total -= input.fadeMs(ordered[from].cueId)
        return total
    }

    /** The family a move of [row] is in: a cue's synthetic `position` row is Position. */
    internal fun familyOf(row: CueAssignmentResolver.Assignment): AttributeFamily =
        if (row.attribute.equals("position", ignoreCase = true)) AttributeFamily.POSITION else row.category.family()

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
    internal fun moves(row: CueAssignmentResolver.Assignment, head: HeadIntensity, settings: MoveInDarkSettings): Boolean =
        moves(row, head, settings.families)

    private fun moves(row: CueAssignmentResolver.Assignment, head: HeadIntensity, families: Set<AttributeFamily>): Boolean {
        if (row.category in NEVER_MOVES) return false
        val family = familyOf(row)
        if (family == AttributeFamily.COLOUR && head.colourIsIntensity) return false
        // A colour carrying UV would light the UV emitter in the dark: it waits for its GO.
        val colour = row.value as? CueAssignmentResolver.AttributeValue.Colour
        if (colour != null && colour.value.uv.toInt() > 0) return false
        return family in families
    }

    /** What lights a head, and so never moves: its dimmer, strobe, UV, an LED ring or macro. */
    private val NEVER_MOVES = setOf(
        AttributeCategory.DIMMER, AttributeCategory.STROBE, AttributeCategory.UV,
        AttributeCategory.RING, AttributeCategory.RING_SPEED, AttributeCategory.LED_MACRO,
    )
}
