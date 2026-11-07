package uk.me.cormack.lighting7.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import uk.me.cormack.lighting7.fx.MasterClock
import uk.me.cormack.lighting7.fx.SpeedMasterBank
import uk.me.cormack.lighting7.fx.TapTempo
import java.util.UUID

/**
 * The programmer's tempos (fx-speed plan D8–D11): the speed masters the operator's hands hold at a
 * tempo of their own, over the live tempo a GO, a press or the rail gave each. One per desk like the
 * programmer, and runtime only. The bank runs them ([SpeedMasterBank.changeHolds]); Record writes them
 * into a cue's *Tempo on GO* (`routes/programmerTempoRecord.kt`).
 *
 * **Project-scoped like [ProgrammerScenery]**: [attach]ed to the current show at start and again by
 * State's project collector, which drops every hold — a master uuid belongs to one project. A
 * carried reload keeps them, less any whose master the reload removed. Clear releases them all.
 *
 * **Blind stages** (D11): what [Snapshot.holds] says is what the programmer holds and Record takes;
 * [Snapshot.landed] is what the bank runs. Entering Blind changes nothing on stage; a change made in
 * Blind waits in [Snapshot.holds] until leaving Blind lands it. A rail tap on a master whose hold is
 * only staged moves its live tempo (the bank does not hold it), and one on a landed hold moves it,
 * and the staged hold with it unless Blind has already changed that one; on a hold released in Blind
 * that still runs it moves the live tempo too, so leaving Blind lands what the tap set.
 *
 * **The bank is the truth for what runs**: a push hands it only the masters whose hold changed, so a
 * hold it has just moved on its own is never put back, and its report of such a move is read back
 * from the bank rather than trusted.
 *
 * Written by `programmer.setTempo` / `.tapTempo` / `.holdTempo` / `.clearTempo` and MCP's `set_bpm`
 * with `hold`; streamed as `programmer.tempoState`, backed by [flow].
 */
class ProgrammerTempo(
    /** The live show's bank, or null while no show is loaded. */
    private val bankOf: () -> SpeedMasterBank?,
    /** Whether the live programmer is blind. */
    private val isBlind: () -> Boolean,
) {
    /**
     * What the programmer holds: each master's tempo in the order first held ([holds]), what the bank
     * runs ([landed], equal to [holds] unless the programmer is blind), and what the last Include —
     * or a Record or Update that wrote the holds back — left the source saying ([baseline], null
     * until one has).
     */
    data class Snapshot(
        val projectId: Int?,
        val holds: Map<UUID, Double>,
        val landed: Map<UUID, Double> = holds,
        val baseline: Map<UUID, Double>? = null,
    ) {
        /** How many held tempos Update would write that the source does not already say. Null with no baseline. */
        val changedSinceInclude: Int?
            get() = baseline?.let { base -> holds.count { (uuid, bpm) -> base[uuid] != bpm } }
    }

    /** What a write did. */
    sealed interface Outcome {
        data object Applied : Outcome
        data class Refused(val message: String) : Outcome
    }

    private val lock = Any()
    private val _flow = MutableStateFlow(Snapshot(null, emptyMap()))
    val flow: StateFlow<Snapshot> = _flow.asStateFlow()

    /** The window's taps on each master, counted by the bank's own rule. */
    private val taps = HashMap<UUID, TapTempo>()

    /** Called before an operator's write changes what the programmer holds, as [ProgrammerScenery.beforeMutation]. */
    @Volatile
    var beforeMutation: () -> Unit = {}

    fun snapshot(): Snapshot = _flow.value

    /**
     * The current project changed, or the same one was reloaded ([reload]): bind the live show's
     * bank. A reload keeps the holds, less any whose master is gone, and lands them on the new bank.
     */
    fun attach(projectId: Int?, reload: Boolean = false) {
        val bank = bankOf()
        bank?.onHoldChanged = { uuid, _ -> onBankHoldChanged(uuid) }
        synchronized(lock) {
            val current = _flow.value
            taps.clear()
            if (reload && projectId != null && current.projectId == projectId && bank != null) {
                val known = knownMasters(bank)
                _flow.value = current.copy(
                    holds = current.holds.filterKeys { it in known },
                    landed = current.landed.filterKeys { it in known },
                )
            } else {
                _flow.value = Snapshot(projectId, emptyMap())
            }
            // The new bank runs nothing yet.
            push(bank, from = emptyMap())
        }
    }

    /** `programmer.setTempo`: hold [uuid] at [bpm], or move its hold. */
    fun set(uuid: UUID, bpm: Double): Outcome {
        if (!bpm.isFinite() || bpm < MasterClock.MIN_BPM || bpm > MasterClock.MAX_BPM) {
            return Outcome.Refused("bpm must be between ${MasterClock.MIN_BPM.toInt()} and ${MasterClock.MAX_BPM.toInt()}")
        }
        val bank = bankOf() ?: return Outcome.Refused("No project is loaded")
        refusalFor(masterOf(bank, uuid))?.let { return it }
        return write { holds -> holds[uuid] = bpm }
    }

    /**
     * `programmer.tapTempo`: one tap on [uuid]. The first on a master the programmer does not hold
     * holds it at the tempo it is running at, so the hold shows at once; each later tap moves it.
     */
    fun tap(uuid: UUID, now: Long = System.currentTimeMillis()): Outcome {
        val bank = bankOf() ?: return Outcome.Refused("No project is loaded")
        val master = masterOf(bank, uuid)
        refusalFor(master)?.let { return it }
        val estimate = synchronized(lock) { taps.getOrPut(uuid) { TapTempo() }.tap(now) }
            ?.coerceIn(MasterClock.MIN_BPM, MasterClock.MAX_BPM)
        if (estimate == null && uuid in _flow.value.holds) return Outcome.Applied
        val bpm = estimate ?: master!!.bpm
        return write { holds -> holds[uuid] = bpm }
    }

    /** `programmer.holdTempo` (*Capture*): hold [uuid] at the tempo it is running at; nothing if held. */
    fun capture(uuid: UUID): Outcome {
        val bank = bankOf() ?: return Outcome.Refused("No project is loaded")
        val master = masterOf(bank, uuid)
        refusalFor(master)?.let { return it }
        val running = master!!.bpm
        return write { holds -> holds.putIfAbsent(uuid, running) }
    }

    /** `programmer.clearTempo`: let [uuid] go, or every hold when it is null. Answers how many were released. */
    fun release(uuid: UUID?): Int {
        val gone = if (uuid == null) _flow.value.holds.keys.toList() else listOf(uuid).filter { it in _flow.value.holds }
        if (gone.isEmpty()) return 0
        write { holds -> gone.forEach { holds.remove(it) } }
        return gone.size
    }

    /** Clear: every hold let go, so each master lands its live tempo. */
    fun clear(): Int = release(null)

    /**
     * Include (D10): hold the cue's own tempos, each replacing what the programmer held on that
     * master. Masters the cue does not name keep what they hold. [projectId] guards an Include read
     * from a project this has left. Answers how many were held.
     */
    fun include(projectId: Int, rows: Map<UUID, Double>): Int {
        val bank = bankOf() ?: return 0
        val known = knownMasters(bank)
        val usable = rows.filterKeys { it in known }
        if (_flow.value.projectId != projectId || usable.isEmpty()) return 0
        write { holds -> holds.putAll(usable) }
        return usable.size
    }

    /** The include target moved to a cue whose own tempos are [rows]: Update compares against them now. */
    fun includedBaseline(projectId: Int, rows: Map<UUID, Double>): Unit = synchronized(lock) {
        val current = _flow.value
        if (current.projectId != projectId) return
        _flow.value = current.copy(baseline = rows)
    }

    /**
     * The include target moved to a Look, which holds no tempo and whose Update writes none: no held
     * tempo is "changed since Include" against it.
     */
    fun forgetBaseline(projectId: Int): Unit = synchronized(lock) {
        val current = _flow.value
        if (current.projectId != projectId) return
        _flow.value = current.copy(baseline = null)
    }

    /**
     * The source now says what the programmer holds — Record or Update wrote the holds into it — or,
     * with [written] false, none of it.
     */
    fun rebaseline(projectId: Int, written: Boolean): Unit = synchronized(lock) {
        val current = _flow.value
        if (current.projectId != projectId) return
        _flow.value = current.copy(baseline = if (written) LinkedHashMap(current.holds) else emptyMap())
    }

    /** Put the holds back as [snapshot] has them, for a programmer step's undo. Answers whether anything changed. */
    fun restore(snapshot: Snapshot): Boolean = synchronized(lock) {
        val current = _flow.value
        if (snapshot.projectId == null || current.projectId != snapshot.projectId || current.holds == snapshot.holds) return false
        val bank = bankOf()
        val known = bank?.let(::knownMasters).orEmpty()
        _flow.value = current.copy(holds = LinkedHashMap(snapshot.holds.filterKeys { it in known }))
        push(bank)
        true
    }

    /** The programmer went into or out of Blind: leaving it lands every staged hold. */
    fun onBlindChanged(): Unit = synchronized(lock) { push(bankOf()) }

    private fun masterOf(bank: SpeedMasterBank, uuid: UUID): SpeedMasterBank.MasterState? =
        bank.masterStates().firstOrNull { it.uuid == uuid }

    private fun refusalFor(master: SpeedMasterBank.MasterState?): Outcome.Refused? = when {
        master == null -> Outcome.Refused("No such speed master")
        master.followNum != null ->
            Outcome.Refused("${master.name} follows another master — unlink it in the speed-master sheet to hold its tempo")
        else -> null
    }

    /** The masters a hold may name: the bank's own, less its followers. */
    private fun knownMasters(bank: SpeedMasterBank): Set<UUID> =
        bank.masterStates().mapNotNullTo(HashSet()) { m -> m.uuid?.takeIf { m.followNum == null } }

    /** Apply [change] to the holds; a write that changes nothing takes no programmer step. */
    private fun write(change: (MutableMap<UUID, Double>) -> Unit): Outcome {
        fun next(current: Snapshot) = LinkedHashMap(current.holds).also(change)
        if (next(_flow.value) == _flow.value.holds) return Outcome.Applied
        beforeMutation()
        synchronized(lock) {
            val current = _flow.value
            val holds = next(current)
            if (holds == current.holds) return Outcome.Applied
            _flow.value = current.copy(holds = holds)
            push(bankOf())
        }
        return Outcome.Applied
    }

    /**
     * Hand the bank the holds whose tempo changed since [from] — what it was last given — and only
     * those: the holds, or while Blind what it already runs. Caller holds [lock].
     */
    private fun push(bank: SpeedMasterBank?, from: Map<UUID, Double> = _flow.value.landed) {
        val current = _flow.value
        val desired = if (isBlind()) current.landed else current.holds
        val changes = (desired.keys + from.keys).filter { desired[it] != from[it] }.associateWith { desired[it] }
        val refused = if (changes.isEmpty() || bank == null) emptySet() else bank.changeHolds(changes)
        val landed = if (refused.isEmpty()) desired else desired.filterKeys { it !in refused }
        val holds = if (refused.isEmpty()) current.holds else LinkedHashMap(current.holds.filterKeys { it !in refused })
        if (landed != current.landed || holds != current.holds) _flow.value = current.copy(holds = holds, landed = landed)
    }

    /**
     * The bank moved a hold itself — a rail or surface tap, a typed tempo — or dropped a master's,
     * deleted or linked as a follower. What it runs now is read back from it, since a push may have
     * landed between its write and this report.
     */
    private fun onBankHoldChanged(uuid: UUID): Unit = synchronized(lock) {
        val current = _flow.value
        if (uuid !in current.landed && uuid !in current.holds) return
        val bank = bankOf()
        val actual = bank?.holdOf(uuid)
        val landed = LinkedHashMap(current.landed)
        val holds = LinkedHashMap(current.holds)
        if (actual == null) {
            landed.remove(uuid)
            holds.remove(uuid)
        } else {
            val was = landed.put(uuid, actual)
            when {
                holds[uuid] == was -> holds[uuid] = actual
                uuid !in holds -> bank.setLiveBpm(uuid, actual, uk.me.cormack.lighting7.models.SpeedMasterSource.TAP)
            }
        }
        if (landed != current.landed || holds != current.holds) _flow.value = current.copy(holds = holds, landed = landed)
    }
}
