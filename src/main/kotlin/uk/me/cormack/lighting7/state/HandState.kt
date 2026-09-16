package uk.me.cormack.lighting7.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.models.BuskPadKind
import uk.me.cormack.lighting7.models.buskPadKind
import uk.me.cormack.lighting7.routes.BuskCueDto
import uk.me.cormack.lighting7.routes.LookDto
import uk.me.cormack.lighting7.routes.TemplateDto
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * The desk's **hand**: one held record — a template, a Look or a cue — picked up on any window and
 * placed on any other (multi-screen plan §3.5, D12).
 *
 * It exists because a pointer drag cannot cross an OS window boundary: the window that saw the
 * press keeps the pointer for the whole gesture, so the neighbour never receives a pointer event of
 * its own. The hand replaces the drag with two half-gestures the desk can hold between.
 *
 * **There is no `hand.place`.** Every target a place can land on already has a mutation with its
 * own validation — `POST /busk/banks/{bankId}/pads`, `assignCueSlot`, `programmer.addLayer`,
 * `patchProjectCue` through `buildCueInput` — so one frame that placed would reimplement four of
 * them. A place is the placing window's own existing mutation followed by a drop, and Undo is that
 * window's inverse mutation. The desk's whole share of a place is letting go.
 *
 * **[Held] carries the record's own summary DTOs**, exactly as `BuskPadDto` does, so every window
 * draws the ghost from the frame alone. A bare `{kind, id}` would push a fetch onto a surface that
 * must not have one — the ghost is frozen and hookless for the reason `padFace.ts` gives.
 *
 * **Project-scoped**, like [DeskSelection] and [BuskPageState] and unlike [WindowRegistry]: the ids
 * a [Held] carries belong to one project's rows, so `State`'s `projectChangedFlow` collector drops
 * it. Transient, never persisted, no table — the sync decision tree's third branch, so
 * `SyncCoverageTest` gains no row.
 *
 * Two things end a hold besides a place: the **timeout** ([DEFAULT_TIMEOUT]), armed on every
 * pick-up, because an item held for the rest of the night is a chip nobody meant to leave lit; and
 * [reconcile], run from the three list-changed listeners, because a record deleted while held must
 * leave every window's chip rather than sit there naming a row that has gone.
 *
 * ### Every hold has an id, and that is what a conditional drop matches on
 *
 * [Held.holdId] is a monotonic counter stamped by [pickUp]. It exists for a specific failure that
 * reference identity could not survive: **`MutableStateFlow` conflates by `equals`**, so assigning a
 * value equal to the current one keeps the *old instance* and emits nothing. Two pick-ups of the
 * same record from the same window inside one `nowMs()` tick produce `equals` [Held]s — and
 * `System.currentTimeMillis()` is ~15.6 ms granular on Windows, the packaged target — so the second
 * assignment would be a no-op while the second expiry job closed over an instance the flow never
 * stored. A reference check would then refuse to fire, and the hand would sit held with **no armed
 * timer** until something else cleared it: the safety net silently gone, in exactly the case this
 * class calls out as ordinary. A distinct [Held.holdId] per pick-up makes two holds unequal, so
 * nothing is conflated and the guard is a value comparison rather than a reference one.
 */
internal class HandState(
    private val scope: CoroutineScope,
    /**
     * Does this record still exist in the current project? Called by [reconcile] only. Injected
     * rather than read here for [DeskSelection]'s reason — this class owns the rule, `State` owns
     * the database.
     */
    private val resolves: (BuskPadKind, Int) -> Boolean,
    private val timeout: Duration = DEFAULT_TIMEOUT,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    /**
     * What the desk is holding.
     *
     * Exactly one of [template] / [look] / [cue] is set, matching [kind] — the `BuskPadDto` shape,
     * and for the same reason: the client already derives a record's face from the summary it holds
     * for the library row, and a name-and-swatch flattened here would freeze the parts of that face
     * which are live. The rule is **asserted**, through the same `buskPadKind` that decides it for a
     * pad row, rather than merely documented: the on-disk shape it mirrors has a database CHECK
     * constraint behind it, and a `Held` naming two records would serialize happily into a ghost
     * every window draws blank.
     *
     * [uuid] is the record's, as a string, because that is how every other DTO spells one on the
     * wire. [id] is what the placing window's mutation takes, since every place mutation addresses
     * records by int id within the project the hand is scoped to.
     *
     * [pickedUpOn] is who picked it up — the same [SelectionSource] `selection.state`'s `source`
     * carries, stamped by the handler from the socket's announced window (D7), never read from a
     * payload, and null for a socket that has announced nothing. A MIDI pick-up carries
     * [SelectionSource.SURFACE].
     *
     * [holdId], [pickedUpAtMs] and [expiresAtMs] are **[HandState]'s to write**, not the caller's,
     * the way [WindowRegistry.Window.id] and `user` are the registry's: a caller that could set its
     * own expiry could hold the desk's hand forever, and one that could set its own [holdId] could
     * make a guarded drop match a hold that was not its own. [pickUp] stamps all three, so a value
     * built anywhere else carries the defaults and matches nothing.
     */
    @Serializable
    data class Held(
        val kind: BuskPadKind,
        val id: Int,
        val uuid: String,
        val template: TemplateDto? = null,
        val look: LookDto? = null,
        val cue: BuskCueDto? = null,
        val pickedUpOn: SelectionSource? = null,
        val holdId: Long = 0,
        val pickedUpAtMs: Long = 0,
        val expiresAtMs: Long = 0,
    ) {
        init {
            require(buskPadKind(template, look, cue) == kind) {
                "a held record must carry exactly one summary, matching its kind ($kind)"
            }
        }
    }

    private val _held = MutableStateFlow<Held?>(null)

    /** The held record, or null for an empty hand. `StateFlow`, so a subscription is the snapshot. */
    val held: StateFlow<Held?> = _held.asStateFlow()

    private var expiryJob: Job? = null
    private var lastHoldId: Long = 0

    /**
     * Refuses a [pickUp] after [close]. Without it a frame still in flight during teardown would
     * arm a fresh five-minute [GlobalScope]-style job that captures this object and, through
     * [resolves], the whole `State` graph — the leak class `State`'s own
     * `FU-TEST-COREMIDI-INIT-DEADLOCK` comment was written about, and `RouteIntegrationTest` tears a
     * `State` down between every test.
     */
    @Volatile
    private var closed: Boolean = false

    /**
     * Take [record] into the hand, stamping its [Held.holdId] and the two times, and arming the
     * timeout. Returns the stamped [Held] — what the caller sees on the wire — or null once
     * [close] has run.
     *
     * **A second pick-up replaces; it is not an error.** The operator changed their mind, and there
     * is no gesture for "put the first one back" that is not just picking up the second. The
     * previous hold's expiry job is cancelled with it.
     */
    @Synchronized
    fun pickUp(record: Held): Held? {
        if (closed) return null
        val now = nowMs()
        val held = record.copy(holdId = ++lastHoldId, pickedUpAtMs = now, expiresAtMs = now + timeout.inWholeMilliseconds)
        expiryJob?.cancel()
        _held.value = held
        expiryJob = scope.launch {
            delay(timeout)
            dropIfHolding(held.holdId)
        }
        return held
    }

    /** Let go, whatever is held. Idempotent — an empty hand is the state a drop wants. */
    @Synchronized
    fun drop() {
        expiryJob?.cancel()
        expiryJob = null
        _held.value = null
    }

    /**
     * Let go only if [holdId] is still the hold; answer whether it did.
     *
     * The conditional drop, and the reason it is not `held.value == x` followed by [drop] at the
     * call site: those are two steps with a gap, and a pick-up landing in that gap would have the
     * unconditional drop clear an item the caller never touched. Every door that lets go of
     * *something in particular* — the timeout, a surface place, a window's guarded drop — comes
     * through here so the check and the clear are one step under this object's lock.
     */
    @Synchronized
    fun dropIfHolding(holdId: Long): Boolean {
        if (_held.value?.holdId != holdId) return false
        drop()
        return true
    }

    /**
     * Let go only if the hand holds the record [uuid] names; answer whether it did.
     *
     * What a window's `hand.drop` uses after placing. A place is two independent round-trips — the
     * window's own mutation, then the drop — and in the gap another window may have picked
     * something up; an unconditional drop would then clear an item this window had nothing to do
     * with, on the very two-screen case the hand exists for. Matching the *record* rather than the
     * [Held.holdId] is deliberate: the client knows what it is holding, not which hold it is, and
     * two holds of one record are the same item to the operator.
     */
    @Synchronized
    fun dropIfRecord(uuid: String): Boolean {
        val current = _held.value ?: return false
        if (current.uuid != uuid) return false
        drop()
        return true
    }

    /**
     * Drop the hand when what it holds no longer exists — run from the `lookListChanged` /
     * `templateListChanged` / `cueListChanged` listeners, which is to say from every delete.
     *
     * [kind] is the list that changed. A hold of another kind cannot be affected by it, so it
     * returns without a database read: these listeners fire on every create and edit too, and the
     * hand has no interest in a cue list moving while it holds a template.
     *
     * Identity, not contents: a record **edited** while held keeps its place in the hand and its
     * frozen face, exactly as a busk pad's face is frozen between reads. Only a record that has
     * gone takes the hand with it.
     */
    fun reconcile(kind: BuskPadKind) {
        val current = _held.value ?: return
        if (current.kind != kind) return
        if (!resolves(current.kind, current.id)) dropIfHolding(current.holdId)
    }

    /**
     * Let go and refuse every later pick-up. Called from `State.shutdown()`; there is no reopening.
     *
     * [drop] alone would not do: it cancels the job this hold armed, and a `hand.pickUp` still in
     * flight through a socket the teardown does not close would arm another.
     */
    @Synchronized
    fun close() {
        closed = true
        drop()
    }

    companion object {
        /**
         * How long the desk holds something nobody placed. Long enough to cross a room and short
         * enough that a chip left lit is a mistake the desk corrects itself (plan §3.5).
         */
        val DEFAULT_TIMEOUT: Duration = 5.minutes
    }
}
