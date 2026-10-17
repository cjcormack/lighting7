@file:OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
package uk.me.cormack.lighting7.fx

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.dmx.EasingCurve
import uk.me.cormack.lighting7.models.CueAdHocEffectDto
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.EffectTargetRef
import uk.me.cormack.lighting7.models.LookEffectSpec
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.telemetry.Tracing

private val logger = LoggerFactory.getLogger("CueHolds")

/**
 * What keeps a cue live (cue model plan §3.1, D3). A **stack hold** is a running stack whose live
 * entry names the cue; a **loose hold** is a press from outside any stack — a busk pad, a cue slot, a
 * MIDI `FireCue`, the hand, REST `/cues/{id}/apply` or MCP `apply_cue` (D6).
 */
sealed interface CueHold {
    data class Stack(val stackId: Int, val entryId: Int) : CueHold

    /** [seq] orders loose presses: the newest composes on top (D10). */
    data class Loose(val seq: Long) : CueHold
}

/**
 * **The hold registry** (cue model plan P3, §3.4): the only place that says what holds a cue live.
 *
 * The engine layers below it stay keyed by cue — `CueAssignmentLayer`'s rows and fade weights,
 * `CueTriggerManager`'s jobs, the effects' `cueId` — and D3 is what makes that correct: a cue live in
 * two places is **one** contribution, held until the last. So every path that makes a cue live or
 * lets it go comes here, and this decides when the cue's contribution goes up, comes down, and at
 * what priority it composes:
 *
 * - [acquire] on a cue's **first** hold brings it on — its effects (growing from nothing over the
 *   GO's fade, effects plan D1), its Layer 4 rows (at weight 0 when a crossfade will bring them in),
 *   its stomp, its timed effects and triggers, and its events (D15). Any other acquire only
 *   restamps its priority: nothing on stage moves.
 * - [release] of its **last** hold takes it off — triggers stopped, effects shrinking to nothing
 *   over the GO's fade (or removed, on a snap), and its rows either dropped or left at full weight
 *   for the caller's crossfade to take down. Any other release only restamps.
 * - A GO from a cue coming off to one coming on keeps every effect the two have in common running,
 *   phase intact (effects plan P4): the incoming cue adopts the outgoing cue's instance of the same
 *   stored effect on the same target ([acquire]'s `adoptFrom`).
 * - A cue brought on again while its fade-out is still running keeps its fading instances and takes
 *   them back up from where they stand rather than respawning them.
 * - A cue's priority is its **highest** hold's. A stack hold is `stackBand + entry position`
 *   ([cueDerivedPriority]); a loose hold is [LOOSE_BAND] `+ seq`, above every stack, newest on top
 *   (D10).
 *
 * - A held cue is **edited since GO** ([markEdited], show-cue-editing plan §3.5) when a write
 *   changes what *Update stage* sends of it, or a Look or palette it layers moves its effects
 *   ([markIfLayerEffectsMoved]): the stage still shows what it was when it came on. Its first hold,
 *   *Update stage* ([clearEdited]) and its last release clear the mark; a Record or Update republish
 *   does not, since it moves the rows alone.
 *
 * The [lock] guards the bookkeeping and nothing else: the engine work runs outside it. A stack delete
 * stops its stack from inside its own transaction, and the pool is one SQLite connection — a lock
 * held across a cook (which can read the Look cache from the database) would let that delete and a
 * GO on another thread wait on each other until Hikari timed out. Reads ([isHeld], [priorityOf],
 * [stackOf]) take the volatile [view], so the engine can ask them under its own publish lock.
 *
 * Runtime only: never persisted, never synced. A same-project reload carries the holds across
 * (`ShowCarry`), and a project switch starts empty.
 */
class CueHolds internal constructor(
    private val fxEngine: FxEngine,
    private val crossfades: CueCrossfadeDriver,
    private val projectId: Int?,
    /** Where [Frame]s go — `State.cueHoldsFrame`, which outlives this show; the socket's `cueHolds.state`. */
    private val sink: MutableStateFlow<Frame>,
    /** Called after every change, outside [lock]: the surfaces' cue-pad LEDs re-read [looseHeld]. */
    private val onChanged: () -> Unit,
) {
    /** The bank a press sets the cue's speed masters on ([takeLooseCue]). */
    internal val speedMasters: SpeedMasterBank get() = fxEngine.speedMasters

    /**
     * How an acquire's contribution arrives: at full now, or from nothing over the GO's fade — its
     * rows at weight 0 for the caller's crossfade to raise, its effects' envelopes on a ramp of
     * their own ([EffectEnvelope]) over the same time and curve.
     */
    enum class Arrival { SNAP, FADE_IN }

    /**
     * How a release's contribution leaves: dropped now, or over the GO's fade — its rows left at full
     * weight for the caller's crossfade to take down, its effects shrinking to nothing on their own
     * ramp and removed at 0 ([FxEngine.fadeOutEffects]).
     */
    enum class Departure { SNAP, FADE_OUT }

    /**
     * [first] when this hold brought the cue on; [hasRows] when it published Layer 4 rows;
     * [fadingEffects] how many of its effects are growing from below full — spawned at nothing, or
     * taken back from a fade-out — which a crossfade must be started for, as for rows; [adopted] what
     * it took over from the cue its GO takes off.
     */
    data class Acquired(
        val first: Boolean,
        val effectCount: Int,
        val hasRows: Boolean,
        val fadingEffects: Int = 0,
        /** What this hold took over from the acquire's `adoptFrom` (P4), for [giveBack]. */
        val adopted: List<Adoption> = emptyList(),
    )

    /**
     * [last] when this release took the cue off; [fadingRows] when its rows are left for a crossfade;
     * [fadingEffects] how many of its effects are shrinking to nothing rather than removed.
     */
    data class Released(val last: Boolean, val effectsRemoved: Int, val fadingRows: Boolean, val fadingEffects: Int = 0) {
        companion object {
            val NOT_HELD = Released(last = false, effectsRemoved = 0, fadingRows = false)
        }
    }

    /**
     * One live cue as the wire carries it: its [holds] in the order taken, the [priority] it composes
     * at, [stackId] — the stack of its highest hold, null when that is loose (provenance's "which
     * stack") — and whether it is [edited] since it came on.
     */
    data class LiveCue(val cueId: Int, val priority: Int, val holds: List<CueHold>, val stackId: Int?, val edited: Boolean = false) {
        val looseHeld: Boolean get() = holds.any { it is CueHold.Loose }
    }

    /** Every live cue, by id — `cueHolds.state`. [projectId] is the show's. */
    data class Frame(val projectId: Int?, val cues: List<LiveCue>) {
        companion object {
            val EMPTY = Frame(null, emptyList())
        }
    }

    private class Live {
        /** Each hold and the priority it asks for, in the order taken. */
        val holds = LinkedHashMap<CueHold, Int>()
    }

    private val lock = Any()
    private val live = HashMap<Int, Live>()

    /** The held cues edited since they came on — a subset of [live]'s keys. */
    private val editedSinceGo = HashSet<Int>()

    /**
     * What each held cue's untimed effects were when it came on or was last brought in line, by
     * [CueEffectKey]: *Update stage* starts only an effect that is not here, so one that ran out its
     * N-shot or that a stomp took off stays off.
     */
    private val spawnedEffects = HashMap<Int, Set<CueEffectKey>>()

    /**
     * What each held cue's untimed layer effects cooked to at the same moments, as their Looks and
     * palettes held them — so an edit to one of those that moves its effects marks the cue.
     */
    private val cookedLayerEffects = HashMap<Int, Set<LayerEffectKey>>()
    private var looseSeq = 0L

    @Volatile private var view: Map<Int, LiveCue> = emptyMap()

    /** Set by [close]: a torn-down show's registry no longer writes the shared [sink]. */
    private var closed = false

    /** The frame this registry last wrote, so [close] empties the sink only while it is still ours. */
    private var lastFrame: Frame? = null

    init {
        // Provenance and `underlyingSources` ask the assignment layer which stack a cue's rows belong
        // to; since D3 that is the registry's question (§3.4), answered off the volatile view.
        fxEngine.cueLayer.holdStackOf = { cueId -> view[cueId]?.stackId }
        synchronized(lock) { publishLocked() }
    }

    // ─── Reads ───────────────────────────────────────────────────────────────────────────────

    fun isHeld(cueId: Int): Boolean = cueId in view

    fun looseHeld(cueId: Int): Boolean = view[cueId]?.looseHeld == true

    /** The priority [cueId] composes at, or null when nothing holds it. */
    fun priorityOf(cueId: Int): Int? = view[cueId]?.priority

    /** The stack of [cueId]'s highest hold; null when that is loose or nothing holds it. */
    fun stackOf(cueId: Int): Int? = view[cueId]?.stackId

    fun holdsOf(cueId: Int): List<CueHold> = view[cueId]?.holds.orEmpty()

    fun isEdited(cueId: Int): Boolean = view[cueId]?.edited == true

    /** [cueId]'s [spawnedEffects], empty when nothing holds it. */
    internal fun spawnedEffectsOf(cueId: Int): Set<CueEffectKey> = synchronized(lock) { spawnedEffects[cueId].orEmpty() }

    /**
     * Record what held [cueId]'s effects are now — [keys] spawned, its [layerEffects] cooked to —
     * after *Update stage* brought them in line.
     */
    internal fun setSpawnedEffects(cueId: Int, keys: Set<CueEffectKey>, layerEffects: List<CookedLayerEffect>) {
        synchronized(lock) {
            if (cueId !in live) return
            spawnedEffects[cueId] = keys
            cookedLayerEffects[cueId] = layerEffects.keys()
        }
    }

    /**
     * Mark held [cueId] edited since GO when its untimed layer effects now cook to [layerEffects]
     * rather than what they cooked to as it came on or was last brought in line: a Look or palette
     * it layers changed its effects, which its rows' republish does not move. Answers whether the
     * mark is new.
     */
    internal fun markIfLayerEffectsMoved(cueId: Int, layerEffects: List<CookedLayerEffect>): Boolean {
        val moved = synchronized(lock) { cookedLayerEffects[cueId]?.let { it != layerEffects.keys() } == true }
        return moved && markEdited(cueId)
    }

    fun snapshot(): List<LiveCue> = view.values.sortedBy { it.cueId }

    /**
     * The cues only [stackId] holds — what a GO on that stack would let go of. Preview leaves these
     * out of what it composes against, and keeps a cue another hold still holds (§3.4).
     */
    fun cuesHeldOnlyBy(stackId: Int): Set<Int> = view.values
        .filter { cue -> cue.holds.all { it is CueHold.Stack && it.stackId == stackId } }
        .mapTo(HashSet()) { it.cueId }

    /** The loosely held cues, oldest press first: what a reload takes again, in the order it was pressed. */
    fun looseCueIdsInPressOrder(): List<Int> = view.values
        .mapNotNull { cue -> cue.holds.filterIsInstance<CueHold.Loose>().maxOfOrNull { it.seq }?.let { cue.cueId to it } }
        .sortedBy { it.second }
        .map { it.first }

    // ─── Acquire and release ─────────────────────────────────────────────────────────────────

    /**
     * Take [hold] on the cue [data] describes, asking for [holdPriority]. On the cue's first hold this
     * brings it on (see the class KDoc); otherwise it only restamps. [data] is a GO's build
     * (`buildCueGoData`), so its events and its label are there to fire with; [fireEvents] false (a GO
     * BACK, a reload) fires none. [stackTag] is the stack an effect is tagged with, null for a loose
     * hold.
     *
     * [fadeMs] and [curve] are the GO's fade, which an [Arrival.FADE_IN]'s effects grow over.
     * [adoptFrom] is the cue the same GO is about to take off (its last hold is the one the caller
     * releases next): an effect of this cue that matches one of that cue's live instances — the same
     * stored Look or palette effect, on the same target, with the same settings — takes that instance
     * over rather than spawning, so it runs on untouched (effects plan P4).
     */
    internal fun acquire(
        state: State,
        data: CueApplyData,
        hold: CueHold,
        holdPriority: Int,
        arrival: Arrival,
        scope: CoroutineScope,
        fireEvents: Boolean,
        stackTag: Int?,
        fadeMs: Long = 0,
        curve: EasingCurve = EasingCurve.LINEAR,
        adoptFrom: Int? = null,
    ): Acquired {
        val (first, priority) = synchronized(lock) { registerLocked(data.cueId, hold, holdPriority) }
        val fade = if (arrival == Arrival.FADE_IN) Fade(fadeMs, curve, crossfade = true) else Fade.SNAP
        return afterRegister(state, data, hold, first, priority, fade, scope, fireEvents, stackTag, adoptFrom)
    }

    /**
     * How one acquire or release moves the cue: [crossfade] when the caller's crossfade takes its rows
     * ([Arrival.FADE_IN], [Departure.FADE_OUT]), and the time and curve its effects' envelopes ramp
     * over. [SNAP] for neither.
     */
    private data class Fade(val durationMs: Long, val curve: EasingCurve, val crossfade: Boolean) {
        /** Whether the effects ramp; a crossfade of 0 ms moves them as a snap does. */
        val fades: Boolean get() = crossfade && durationMs > 0

        companion object {
            val SNAP = Fade(0, EasingCurve.LINEAR, crossfade = false)
        }
    }

    /** Add [hold] to [cueId]'s holds under [lock]: whether it is the first, and the cue's priority now. */
    private fun registerLocked(cueId: Int, hold: CueHold, holdPriority: Int): Pair<Boolean, Int> {
        val cue = live.getOrPut(cueId) { Live() }
        val first = cue.holds.isEmpty()
        // Coming on lands the cue as stored, so whatever was edited is on stage now.
        if (first) editedSinceGo.remove(cueId)
        cue.holds[hold] = holdPriority
        publishLocked()
        return first to cue.holds.values.max()
    }

    /** [acquire]'s engine half, once [hold] is registered — outside [lock], per the class KDoc. */
    private fun afterRegister(
        state: State,
        data: CueApplyData,
        hold: CueHold,
        first: Boolean,
        priority: Int,
        fade: Fade,
        scope: CoroutineScope,
        fireEvents: Boolean,
        stackTag: Int?,
        adoptFrom: Int?,
    ): Acquired {
        val cueId = data.cueId
        try {
            if (!first) {
                fxEngine.repriorityCues(mapOf(cueId to priority))
                return Acquired(first = false, effectCount = 0, hasRows = fxEngine.cueLayer.hasAssignments(cueId))
            }
            // A cue a crossfade elsewhere is still taking down is on again: that fade must stop
            // touching it, or it would pin it back to 0 and drop its rows at its end, and cut its
            // effects off if it were cancelled. [bringOn] takes those effects back.
            crossfades.forgetOutgoing(cueId)
            val adopted = mutableListOf<Adoption>()
            val brought = try {
                bringOn(state, data.copy(livePriority = priority), fade, scope, stackTag, adoptFrom, adopted)
            } catch (e: Exception) {
                // Take down whatever part of it came on, and let the hold go: a hold left behind
                // would make the next acquire a restamp of a cue that is not on stage. What it took
                // over goes back first, so the outgoing cue the GO now keeps still runs it.
                runCatching { giveBack(adopted) }
                runCatching { takeOff(state, cueId, Fade.SNAP) }
                synchronized(lock) {
                    live[cueId]?.let { cue -> cue.holds.remove(hold); if (cue.holds.isEmpty()) forgetLocked(cueId) }
                    publishLocked()
                }
                throw e
            }
            // A hold taken while this one was spawning may have raised the priority it asked for.
            priorityOf(cueId)?.takeIf { it != priority }?.let { fxEngine.repriorityCues(mapOf(cueId to it)) }
            // Its events fire as it comes on (D15): on the first hold, whatever path took it, and only
            // then — a GO into a cue another hold already keeps live fires nothing.
            if (fireEvents) Tracing.span("cue.events") { state.effectsService.onCueGo(cueId, data.cueLabel, data.goEvents) }
            return brought
        } finally {
            onChanged()
        }
    }

    /**
     * Let [hold] on [cueId] go. On its last hold this takes the cue off (see the class KDoc): on
     * [Departure.SNAP] its rows are dropped and its effects removed; on [Departure.FADE_OUT] its rows
     * are left at full weight for the caller's crossfade and its effects shrink to nothing over
     * [fadeMs] on [curve]. Otherwise it only restamps. A hold the cue does not have answers
     * [Released.NOT_HELD].
     */
    internal fun release(
        state: State,
        cueId: Int,
        hold: CueHold,
        departure: Departure,
        fadeMs: Long = 0,
        curve: EasingCurve = EasingCurve.LINEAR,
    ): Released {
        val remaining = synchronized(lock) {
            val cue = live[cueId] ?: return Released.NOT_HELD
            if (cue.holds.remove(hold) == null) return Released.NOT_HELD
            if (cue.holds.isEmpty()) forgetLocked(cueId)
            publishLocked()
            cue.holds.values.maxOrNull()
        }
        try {
            if (remaining != null) {
                fxEngine.repriorityCues(mapOf(cueId to remaining))
                return Released(last = false, effectsRemoved = 0, fadingRows = false)
            }
            return takeOff(state, cueId, if (departure == Departure.FADE_OUT) Fade(fadeMs, curve, crossfade = true) else Fade.SNAP)
        } finally {
            onChanged()
        }
    }

    /**
     * Take a loose hold on the cue [data] describes (D6): above every stack and on top of every earlier
     * loose press (D10), with no follow and no tracking. A cue already held loose is moved to the top
     * rather than held twice — one loose hold per cue, which is what a press toggles (D11). [data] is
     * `buildCueGoData(cue, null)`, so a loose cue starts its timed effects and triggers as a GO does,
     * and its events fire under its name.
     */
    internal fun takeLoose(state: State, data: CueApplyData, scope: CoroutineScope, fireEvents: Boolean = true): Acquired {
        val cueId = data.cueId
        // The check for an earlier press and the new hold are one step under [lock]: two presses
        // racing in from different threads would otherwise each see none and hold the cue twice,
        // and a stop would then let only one of them go.
        val hold: CueHold.Loose
        val registered = synchronized(lock) {
            val seq = ++looseSeq
            hold = CueHold.Loose(seq)
            val cue = live[cueId]
            val previous = cue?.holds?.keys?.filterIsInstance<CueHold.Loose>()?.firstOrNull()
            if (cue != null && previous != null) {
                cue.holds.remove(previous)
                cue.holds[hold] = loosePriority(seq)
                publishLocked()
                null
            } else {
                registerLocked(cueId, hold, loosePriority(seq))
            }
        }
        val acquired = if (registered == null) {
            priorityOf(cueId)?.let { fxEngine.repriorityCues(mapOf(cueId to it)) }
            onChanged()
            Acquired(first = false, effectCount = 0, hasRows = fxEngine.cueLayer.hasAssignments(cueId))
        } else {
            val (first, priority) = registered
            afterRegister(state, data, hold, first, priority, Fade.SNAP, scope, fireEvents, stackTag = null, adoptFrom = null)
        }
        state.sceneryService.onLooseHeld(cueId)
        return acquired
    }

    /** Let [cueId]'s loose hold go, if it has one — `/stop`, `stop_cue`, a second press, a solo sibling. */
    internal fun dropLoose(state: State, cueId: Int): Released {
        val hold = view[cueId]?.holds?.filterIsInstance<CueHold.Loose>()?.firstOrNull() ?: return Released.NOT_HELD
        val released = release(state, cueId, hold, Departure.SNAP)
        state.sceneryService.onLooseReleased(cueId)
        return released
    }

    /** Every loose hold but [exceptCueId]'s — `replaceAll`. A stack's holds are never touched (§3.4). */
    internal fun dropAllLoose(state: State, exceptCueId: Int? = null): Int =
        view.values.filter { it.looseHeld && it.cueId != exceptCueId }.sumOf { dropLoose(state, it.cueId).effectsRemoved }

    /**
     * Let every hold on [cueId] go, at once — a deleted cue (a forced delete releases every hold
     * first, §3.6). A stack that held it keeps its live entry until its next GO, which then has nothing
     * to release.
     */
    internal fun dropAll(state: State, cueId: Int): Int {
        val holds = view[cueId]?.holds.orEmpty()
        var removed = 0
        for (hold in holds) removed += release(state, cueId, hold, Departure.SNAP).effectsRemoved
        if (holds.any { it is CueHold.Loose }) state.sceneryService.onLooseReleased(cueId)
        return removed
    }

    /**
     * Mark held [cueId] edited since GO: a write changed what *Update stage* sends of it, which
     * reaches the stage on its next first hold or *Update stage*. A cue nothing holds is not marked.
     * Answers whether the mark is new.
     */
    fun markEdited(cueId: Int): Boolean = synchronized(lock) {
        if (cueId !in live || !editedSinceGo.add(cueId)) return false
        publishLocked()
        true
    }

    /** Clear [cueId]'s mark: *Update stage* has put what it holds on stage. */
    fun clearEdited(cueId: Int) {
        synchronized(lock) {
            if (editedSinceGo.remove(cueId)) publishLocked()
        }
    }

    /**
     * Restamp the stack holds on the entries in [entryPriorities] (`entryId → stackBand + position`),
     * after a write shifted positions — a reorder, an add, a sort, a delete or insert mid-list, a
     * marker. A live cue's priority is still its highest hold's, so a loose hold above keeps it there.
     *
     * A hold is restamped only by a priority in its own stack's band: an entry moved to another stack
     * keeps its id, and the stack it left goes on holding it at that stack's priority until its next
     * GO, whatever the stack it joined does meanwhile.
     */
    fun restampEntries(entryPriorities: Map<Int, Int>) {
        if (entryPriorities.isEmpty()) return
        val changed = synchronized(lock) {
            val out = HashMap<Int, Int>()
            for ((cueId, cue) in live) {
                var touched = false
                for (hold in cue.holds.keys.toList()) {
                    if (hold !is CueHold.Stack) continue
                    val priority = entryPriorities[hold.entryId] ?: continue
                    if (stackOfDerivedPriority(priority) != hold.stackId) continue
                    if (cue.holds.put(hold, priority) != priority) touched = true
                }
                if (touched) out[cueId] = cue.holds.values.max()
            }
            if (out.isNotEmpty()) publishLocked()
            out
        }
        if (changed.isNotEmpty()) {
            fxEngine.repriorityCues(changed)
            onChanged()
        }
    }

    /**
     * Forget every hold without touching the engine — the show is being torn down with its engine
     * (`Show.close`). The shared [sink] outlives the show, so this empties it — but only while it
     * still holds this registry's own last frame: the next show may already have written its own,
     * and a late hold or restamp from this one must not overwrite it, which [closed] stops.
     */
    internal fun close() {
        synchronized(lock) {
            live.clear()
            editedSinceGo.clear()
            spawnedEffects.clear()
            cookedLayerEffects.clear()
            publishLocked()
            closed = true
            if (sink.value === lastFrame) sink.value = Frame.EMPTY
        }
    }

    // ─── Bringing a cue on and taking it off ─────────────────────────────────────────────────

    /**
     * The cue's contribution, up: its layers' effects and ad-hoc effects spawned at its priority, its
     * Layer 4 rows published (at weight 0 on a fade), its stomp, its timed effects and its triggers.
     * One cook for both halves (sweep item C8) — see [CueComposer.cookAll].
     *
     * Each effect it cooks to arrives one of three ways, by one rule ([continues]):
     * - **taken back** — the cue was still fading out (its last hold went on a GO with a fade, and
     *   the fade has not ended): an instance of its own that is the same effect is kept, phase intact,
     *   and grows back from where it stands over [fade] (at once on a snap). One the cue no longer
     *   cooks to — it was edited meanwhile — finishes going.
     * - **adopted** — an effect matching one of [adoptFrom]'s live instances (the same stored effect,
     *   target and settings, effects plan P4) takes that instance over and runs on untouched, unless
     *   that instance is paused or an N-shot: those arrive afresh, as the cue would start them.
     * - **spawned** — everything else, growing from nothing over [fade] (full at once on a snap).
     *
     * Every adoption is recorded in [adopted] as it is made, so a caller can hand them back if the
     * GO fails or the outgoing cue turns out to be held elsewhere ([giveBack]).
     */
    private fun bringOn(
        state: State,
        data: CueApplyData,
        fade: Fade,
        scope: CoroutineScope,
        stackTag: Int?,
        adoptFrom: Int?,
        adopted: MutableList<Adoption>,
    ): Acquired {
        val cueId = data.cueId
        val priority = cueDerivedPriority(data)
        warnDeadAssignments(state, data)
        val (immediateAdHoc, timedAdHoc) = data.adHocEffects.partition { it.delayMs == null && it.intervalMs == null }
        val cooked = Tracing.span("cue.compose") {
            CueComposer.cookAll(
                fixtures = state.show.fixtures,
                cueId = cueId,
                priority = priority,
                layers = data.layers,
                localRows = buildCueAssignmentsForCue(state.show.fixtures, data),
                resolveLook = state.show.lookRegistry::snapshot,
                resolveTemplate = state.show.templateRegistry::snapshot,
            )
        }

        val nowMs = System.currentTimeMillis()
        val keptPool = takeBack(cueId).toMutableList()
        val adoptable = adoptFrom?.let { from ->
            fxEngine.cueEffects(from).filterTo(mutableListOf()) { it.layerEffectUuid != null && it.mayContinue() }
        } ?: mutableListOf()
        val kept = mutableListOf<FxInstance>()
        val spawning = mutableListOf<FxInstance>()
        // Every instance the cue runs, in cook order — layer order, then the ad-hoc ones — which is
        // its composition order for same-priority effects (sweep item C7).
        val ordered = mutableListOf<FxInstance>()
        Tracing.span("cue.spawn") { span ->
            fun arrive(fresh: FxInstance, layer: CookLayer?) {
                val own = keptPool.firstOrNull { it.continues(fresh) }
                if (own != null) {
                    keptPool.remove(own)
                    kept += own.also { it.tagged(cueId, stackTag, priority) }
                    ordered += own
                    return
                }
                val continuing = layer?.let { adoptable.firstOrNull { it.continues(fresh) } }
                if (continuing != null) {
                    adoptable.remove(continuing)
                    adopted += Adoption(continuing, fresh.tagged(cueId, stackTag, priority), continuing.cueId, continuing.cueStackId, continuing.priority, continuing.source, continuing.cueLayerId)
                    fxEngine.retagEffects(listOf(continuing)) {
                        it.source = layer.source
                        it.cueLayerId = layer.layerId
                        it.tagged(cueId, stackTag, priority)
                    }
                    ordered += continuing
                    return
                }
                spawning += fresh.tagged(cueId, stackTag, priority)
                ordered += fresh
            }
            val fresh = cueEffectInstances(state, cueId, cooked.effects, immediateAdHoc)
            synchronized(lock) {
                if (cueId in live) {
                    spawnedEffects[cueId] = fresh.mapTo(HashSet()) { it.first.cueEffectKey() }
                    cookedLayerEffects[cueId] = cooked.effects.keys()
                }
            }
            for ((instance, layer) in fresh) arrive(instance, layer)

            // On a fade they arrive from nothing, set before the add so no pass paints them at full.
            if (fade.fades) {
                for (instance in spawning) instance.carryEnvelope(EffectEnvelope.ramp(0.0, 1.0, fade.durationMs, fade.curve, nowMs))
            }
            fxEngine.addEffects(spawning)
            span?.setData("cue.effects", spawning.size)
            span?.setData("cue.effects_continuing", adopted.size + kept.size)
        }
        // Its own instances under the hold that took it back, from where they stand; and what it no
        // longer cooks to finishes going.
        if (kept.isNotEmpty()) {
            fxEngine.retagEffects(kept) {}
            if (fade.fades) fxEngine.fadeInEffects(kept, fade.durationMs, fade.curve, nowMs) else fxEngine.settleEffects(kept)
        }
        if (keptPool.isNotEmpty()) fxEngine.fadeOutEffects(keptPool, if (fade.fades) fade.durationMs else 0, fade.curve, nowMs)
        // An instance carried over keeps its old compose order; the cue's layer order decides.
        if (kept.isNotEmpty() || adopted.isNotEmpty()) fxEngine.restampComposeOrder(ordered)
        val fadingEffects = if (fade.fades) spawning.size + kept.size else 0
        val effectCount = ordered.size

        // Its rows. On a crossfade the cue arrives at weight 0 atomically with the insert and the
        // driver raises it; a crossfade is already the transition, so its rows' own fades are not
        // honoured — the two never compound on one key.
        val hasRows = cooked.values.rows.isNotEmpty()
        if (hasRows) {
            fxEngine.cueLayer.setAssignments(
                cueId, cooked.values.rows,
                weight = if (fade.crossfade) 0.0 else 1.0,
                stompSuppression = cooked.values.stompSuppression,
                honourRowFades = !fade.crossfade,
            )
        } else {
            fxEngine.cueLayer.removeAssignments(cueId)
        }
        if (data.stomp) fxEngine.stompForCue(cueId, buildStompOverlap(state.show.fixtures, data, cooked.values))

        // Its timed effects and its triggers, on every hold's first — a loose cue's included (§3.4).
        if (data.layers.any { it.enabled && it.isTimed } || timedAdHoc.isNotEmpty()) {
            state.cueTriggerManager.activateTimedEffectsForCue(
                cueId = cueId,
                cueStackId = stackTag,
                priority = { priorityOf(cueId) ?: priority },
                cueData = data,
                timedAdHocEffects = timedAdHoc,
                scope = scope,
            )
        }
        if (data.triggers.isNotEmpty()) {
            state.cueTriggerManager.activateTriggersForCue(cueId = cueId, cueStackId = stackTag, triggers = data.triggers, scope = scope)
        }
        return Acquired(first = true, effectCount = effectCount, hasRows = hasRows, fadingEffects = fadingEffects, adopted = adopted.toList())
    }

    /**
     * The instances a cue's untimed effects spawn — each cooked layer effect, then each ad-hoc one,
     * in that order, with the layer it came from (null for an ad-hoc one) — built and not added.
     * Each carries its stored effect's uuid in [FxInstance.layerEffectUuid]: a Look or palette
     * effect's for a layer's, the cue's own row's for an ad-hoc one. What [bringOn] arrives and
     * *Update stage* reconciles the running instances against. An unresolvable target is skipped.
     */
    internal fun cueEffectInstances(
        state: State,
        cueId: Int,
        layerEffects: List<Triple<CookLayer, EffectEntry, EffectTargetRef>>,
        adHocEffects: List<CueAdHocEffectDto>,
    ): List<Pair<FxInstance, CookLayer?>> = buildList {
        for ((layer, lookEffect, target) in layerEffects) {
            val spec = lookEffect.toEffectSpec()
            val fxTarget = try {
                EffectSpawner.resolveTargetForCue(state, target, spec)
            } catch (e: Exception) {
                logger.warn("cue {}: layer on '{}' — target '{}' unresolvable — skipping effect: {}", cueId, layer.source.name, target.key, e.message)
                null
            } ?: continue
            val instance = EffectSpawner.createEffectInstanceOrNull(
                spec, fxTarget, state = state,
                context = "cue $cueId: layer on '${layer.source.name}'",
                overrideSpeedMasterUuid = layer.speedMasterUuid,
                overrideRateSpeedMasterUuid = layer.rateSpeedMasterUuid,
            ) ?: continue
            // The whole source, kind included: a template layer owns effects (D5), and
            // `FxInstance.lookId` derives the Look-only id from it.
            instance.source = layer.source
            instance.cueLayerId = layer.layerId
            instance.layerEffectUuid = lookEffect.uuid
            add(instance to layer)
        }
        for (adHoc in adHocEffects) {
            val spec = adHoc.toLookEffectSpec()
            val fxTarget = try {
                EffectSpawner.resolveTargetForCue(state, adHoc.target, spec)
            } catch (e: Exception) {
                logger.warn("cue {}: ad-hoc effect on '{}' — target unresolvable — skipping: {}", cueId, adHoc.targetKey, e.message)
                null
            } ?: continue
            val instance = EffectSpawner.createEffectInstanceOrNull(spec, fxTarget, state, "cue $cueId: ad-hoc effect on '${adHoc.targetKey}'")
                ?: continue
            // Its own row's uuid: what a cue taken back matches its own instance on. No other
            // cue holds that row, so an ad-hoc effect is never adopted across a GO.
            instance.layerEffectUuid = adHoc.uuid?.let { runCatching { java.util.UUID.fromString(it) }.getOrNull() }
            add(instance to null)
        }
    }

    /**
     * [cueId]'s own instances still fading out from its last release, taken back rather than
     * respawned and held where they stand. One the engine retired in the instant its fade
     * reached 0 is gone, and the cook spawns that effect afresh.
     */
    private fun takeBack(cueId: Int): List<FxInstance> {
        val leaving = fxEngine.leavingCueEffects(cueId)
        return if (leaving.isEmpty()) emptyList() else fxEngine.reclaimEffects(leaving)
    }

    /**
     * Whether this live instance is the same effect [fresh] would start (effects plan P4): the same
     * stored effect row, on the same target and property, with the same settings — its type, timing,
     * masters, size, spread and the rest. An effect two cues layer at different speed masters, say,
     * is two effects: one leaves and one arrives.
     */
    private fun FxInstance.continues(fresh: FxInstance): Boolean =
        layerEffectUuid != null && layerEffectUuid == fresh.layerEffectUuid &&
            target.targetRef == fresh.target.targetRef &&
            target.propertyName == fresh.target.propertyName &&
            fxEngine.speedMasters.master1Uuid().let { comparableEffectSettings(it) == fresh.comparableEffectSettings(it) }

    /**
     * Whether another cue may take this instance over (P4): not a paused one — the
     * incoming cue's effect starts running — and not an N-shot, which is an accent each GO fires.
     */
    private fun FxInstance.mayContinue(): Boolean = isRunning && dynamics.nShot == null

    /**
     * One instance a cue took over from [fromCueId] (P4), with what it was before and the [fresh]
     * instance the taking cue would otherwise have spawned — so [giveBack] can undo it.
     */
    data class Adoption(
        val instance: FxInstance,
        val fresh: FxInstance,
        val fromCueId: Int?,
        val fromStackId: Int?,
        val fromPriority: Int,
        val fromSource: uk.me.cormack.lighting7.models.LayerSource?,
        val fromLayerId: Int?,
    )

    /**
     * Undo [adoptions]: each instance goes back to the cue it came from, as it was, at that cue's
     * priority now. With [respawnFor], the cue that took them gets its own fresh instances instead, at
     * full — a GO whose outgoing cue turned out to be held elsewhere, so it never came off.
     */
    internal fun giveBack(adoptions: List<Adoption>, respawnFor: Int? = null) {
        if (adoptions.isEmpty()) return
        fxEngine.retagEffects(adoptions.map { it.instance }) { instance ->
            val a = adoptions.first { it.instance === instance }
            instance.cueId = a.fromCueId
            instance.cueStackId = a.fromStackId
            instance.priority = a.fromCueId?.let { priorityOf(it) } ?: a.fromPriority
            instance.source = a.fromSource
            instance.cueLayerId = a.fromLayerId
        }
        if (respawnFor != null) {
            val priority = priorityOf(respawnFor) ?: adoptions.first().instance.priority
            fxEngine.addEffects(adoptions.map { a -> a.fresh.also { it.priority = priority } })
        }
    }

    /**
     * The cue's contribution, down: its triggers and timed effects stopped, its effects removed — by
     * cue, never by stack, since another stack's effects are not its — or shrinking to nothing over
     * [fade], and its rows dropped, or left at full weight for a crossfade.
     *
     * Timed effects and triggers stop at once even on a fade: they are fired, not held, and the
     * trigger manager's own sweep takes them.
     */
    private fun takeOff(state: State, cueId: Int, fade: Fade): Released {
        state.cueTriggerManager.deactivateTriggersForCue(cueId)
        val (removed, fadingEffects) = if (fade.fades) {
            0 to fxEngine.fadeOutEffects(fxEngine.cueEffects(cueId), fade.durationMs, fade.curve)
        } else {
            fxEngine.removeCueEffects(cueId) to 0
        }
        val fading = fade.crossfade && fxEngine.cueLayer.hasAssignments(cueId)
        if (fading) {
            // A prior mid-flight crossfade may have left it partial; it fades out from full.
            fxEngine.cueLayer.updateFadeWeights(mapOf(cueId to 1.0))
        } else {
            fxEngine.cueLayer.removeAssignments(cueId)
        }
        return Released(last = true, effectsRemoved = removed, fadingRows = fading, fadingEffects = fadingEffects)
    }

    /**
     * One rate-limited warn per cue coming on whose rows name a removed or renamed fixture, group or
     * property. The per-row warns inside the build helpers stay — they are the detailed trail; this
     * is the operator-facing summary, throttled so a stack GO'ing the same dead cue on every beat
     * doesn't flood the logs.
     */
    private fun warnDeadAssignments(state: State, data: CueApplyData) {
        val dead = data.propertyAssignments.filter {
            PersistedFixtureReferenceValidator.validateTargetedReference(
                state.show.fixtures, it.target, it.propertyName, allowElements = true,
            ) != uk.me.cormack.lighting7.models.AssignmentHealth.Ok
        }
        if (dead.isEmpty()) return
        val signature = dead.joinToString(";") { "${it.targetType}:${it.targetKey}.${it.propertyName}" }
        val now = System.currentTimeMillis()
        val previous = deadWarnState[data.cueId]
        if (previous != null && previous.second == signature && (now - previous.first) < DEAD_WARN_THROTTLE_MS) return
        deadWarnState[data.cueId] = now to signature
        logger.warn("cue '{}' ({}): {} dead assignment(s): {}", data.cueName, data.cueId, dead.size, signature)
    }

    /**
     * *Update stage*'s rows: [cueId]'s Layer 4 taken to [rows] over [fade] while a hold still has it
     * — asked under the publish lock a release's row drop takes too — at the weight a crossfade is
     * bringing it in at when it had none. Answers whether it was held.
     */
    internal fun restage(
        cueId: Int,
        rows: List<CueAssignmentResolver.Assignment>,
        stompSuppression: LayerStompSuppression,
        fade: TransmitFade,
    ): Boolean = fxEngine.cueLayer.restageAssignments(
        cueId, rows, stompSuppression, fade,
        held = { isHeld(cueId) },
        seedWeight = { crossfades.incomingWeight(cueId) },
    )

    internal fun FxInstance.tagged(cueId: Int, stackTag: Int?, priority: Int): FxInstance = apply {
        this.cueId = cueId
        this.cueStackId = stackTag
        this.priority = priority
    }

    // ─── The frame ───────────────────────────────────────────────────────────────────────────

    /** Drop [cueId], its last hold gone, under [lock]. */
    private fun forgetLocked(cueId: Int) {
        live.remove(cueId)
        editedSinceGo.remove(cueId)
        spawnedEffects.remove(cueId)
        cookedLayerEffects.remove(cueId)
    }

    /** Rebuild [view] and the frame from [live]. Under [lock]. */
    private fun publishLocked() {
        view = live.mapValues { (cueId, cue) ->
            val top = cue.holds.maxByOrNull { it.value }
            LiveCue(
                cueId = cueId,
                priority = top?.value ?: 0,
                holds = cue.holds.keys.toList(),
                stackId = (top?.key as? CueHold.Stack)?.stackId,
                edited = cueId in editedSinceGo,
            )
        }
        if (closed) return
        val frame = Frame(projectId, view.values.sortedBy { it.cueId })
        lastFrame = frame
        sink.value = frame
    }

    companion object {
        /**
         * The loose band (D10): above every stack's `stackId * 1_000_000 + …` while stack ids stay
         * under 2,000, and [LOOSE_SPAN] below the programmer's effect band
         * ([FxEngine.PROGRAMMER_FX_PRIORITY_BASE]), which stays above everything a cue asserts.
         */
        const val LOOSE_BAND = 2_000_000_000

        /** How many loose presses one show run can stack before the newest stops rising. */
        const val LOOSE_SPAN = 100_000_000

        fun loosePriority(seq: Long): Int = LOOSE_BAND + seq.coerceIn(0, LOOSE_SPAN.toLong()).toInt()

        /**
         * The dead-assignment warn's throttle: a log-rate gate, not a cache of correctness, capped at
         * [DEAD_WARN_STATE_MAX] cues with the oldest evicted so a long run can't grow it per cue.
         */
        private const val DEAD_WARN_THROTTLE_MS = 30_000L
        private const val DEAD_WARN_STATE_MAX = 1024
        private val deadWarnState = java.util.Collections.synchronizedMap(
            object : java.util.LinkedHashMap<Int, Pair<Long, String>>(16, 0.75f, /* accessOrder = */ true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Pair<Long, String>>?): Boolean =
                    size > DEAD_WARN_STATE_MAX
            },
        )
    }
}

/** An ad-hoc cue effect as the spawner takes it. */
internal fun CueAdHocEffectDto.toLookEffectSpec() = LookEffectSpec(
    effectType = effectType,
    category = category,
    propertyName = propertyName,
    beatDivision = beatDivision,
    blendMode = blendMode,
    distribution = distribution,
    phaseOffset = phaseOffset,
    elementMode = elementMode,
    elementFilter = elementFilter,
    stepTiming = stepTiming,
    parameters = parameters,
    speedMasterUuid = speedMasterUuid,
    rateSpeedMasterUuid = rateSpeedMasterUuid,
    seconds = seconds,
    size = size,
    direction = direction,
    nShot = nShot,
    spread = spread,
    wings = wings,
    blocks = blocks,
    groups = groups,
)

/** What a loose press did: the cue's name, and whether it brought the cue on. */
internal data class LoosePress(val cueName: String, val acquired: CueHolds.Acquired)

/**
 * Take a loose hold on cue [cueId] — every loose door's one path (`/cues/{id}/apply`, `apply_cue`, a
 * busk cue pad, `FireCue`, a reload's carry). Built as a GO builds it, with no entry: its events fire
 * under its name, its timers start (cue model plan §3.4) and it sets its speed masters. Null when the
 * cue does not exist; the caller has already checked it is the current project's.
 */
internal fun CueHolds.takeLooseCue(
    state: State,
    cueId: Int,
    fireEvents: Boolean = true,
    scope: CoroutineScope = kotlinx.coroutines.GlobalScope,
    setTempo: Boolean = true,
): LoosePress? {
    val data = org.jetbrains.exposed.v1.jdbc.transactions.transaction(state.database) {
        uk.me.cormack.lighting7.models.DaoCue.findById(cueId)?.let { buildCueGoData(it, null) }
    } ?: return null
    val press = LoosePress(data.cueName, takeLoose(state, data, scope, fireEvents))
    // A press brings the cue on as a GO does, so it sets its masters as a GO does (S16); a reload does not.
    if (setTempo) speedMasters.setCueTempos(data.cueLabel, data.goSpeedMasters)
    return press
}

/**
 * A loose press's toggle (D11): let [cueId]'s loose hold go when it has one, else take one. Answers
 * whether the press put it on, or null when the cue does not exist.
 */
internal fun CueHolds.toggleLooseCue(state: State, cueId: Int, scope: CoroutineScope = kotlinx.coroutines.GlobalScope): Pair<Boolean, Int>? =
    if (looseHeld(cueId)) {
        false to dropLoose(state, cueId).effectsRemoved
    } else {
        takeLooseCue(state, cueId, scope = scope)?.let { true to it.acquired.effectCount }
    }

/** One effect a cue runs: its stored effect's uuid, the layer it came through (none for an ad-hoc one), its target and property. */
internal data class CueEffectKey(val uuid: java.util.UUID?, val layerId: Int?, val target: FxTargetRef, val propertyName: String)

internal fun FxInstance.cueEffectKey() = CueEffectKey(layerEffectUuid, cueLayerId, target.targetRef, target.propertyName)

/** One layer effect a cue's cook names: its layer, its stored effect and its target. */
internal typealias CookedLayerEffect = Triple<CookLayer, EffectEntry, EffectTargetRef>

/** A [CookedLayerEffect] by its layer's row id, so a change to the cue's own layer is not one to its Look. */
internal data class LayerEffectKey(val layerId: Int, val effect: EffectEntry, val target: EffectTargetRef)

/** The untimed ones of these, as keys: timed layers' effects are fired, never spawned on GO. */
private fun List<CookedLayerEffect>.keys(): Set<LayerEffectKey> =
    filter { (layer, _, _) -> !layer.isTimed }.mapTo(HashSet()) { (layer, effect, target) -> LayerEffectKey(layer.layerId, effect, target) }
