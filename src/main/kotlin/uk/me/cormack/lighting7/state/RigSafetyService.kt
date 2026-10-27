package uk.me.cormack.lighting7.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.fixture.CommandRole
import uk.me.cormack.lighting7.fixture.CommandSpec
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fixture.FixtureCommands
import uk.me.cormack.lighting7.fixture.group.FixtureElement
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.show.Show
import uk.me.cormack.lighting7.state.BulkCommandRun.BulkUnit
import uk.me.cormack.lighting7.state.BulkCommandRun.Purpose
import uk.me.cormack.lighting7.state.BulkCommandRun.Start
import uk.me.cormack.lighting7.telemetry.LiveSentryContext
import uk.me.cormack.lighting7.telemetry.reportingExceptionHandler
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private val logger = LoggerFactory.getLogger("RigSafetyService")

/**
 * The end of the night (venue-visit plan S6, D9, D14, D18, D19): bulk fixture commands, *Make rig
 * safe*, its *Undo*, and the *Ready to switch off* state every window shows. Desk-owned, attached to
 * each show as it starts, like [EffectsService].
 *
 * - **A bulk run** ([runRole]) is one command role over the selection or the whole rig, through the
 *   show's [CommandOutput] unit by unit ([BulkCommandRun]), so `COMMAND_BUSY`, a park and the venue
 *   hold are met per unit and reported. A strike is staggered and waits out each lamp's restrike
 *   time. One run at a time on the desk.
 * - **Make rig safe** ([makeSafe]) is a lamp-off run over every lamped unit (or the selection's),
 *   then *Make rig safe*'s blackout ([uk.me.cormack.lighting7.midi.GlobalScalerStateHolder.safeBlackout],
 *   runtime only), then *Ready to switch off* — but only when every lamped unit on the rig is known
 *   off ([lampsKnownOff]), and only once the last of them has cooled
 *   ([uk.me.cormack.lighting7.fixture.FixtureCommand.coolDownMs]): until then the rig is *cooling*,
 *   counted down on every window.
 * - **Ready lasts only while it is true** (D14): it clears on anything that sends output again — a
 *   change of what the programmer, a cue, an effect or a park contributes (the provenance revision,
 *   which no crossfade tick moves), any command but a lamp-off, the blackout released, or another
 *   show.
 * - **Undo** ([undo]) strikes, staggered, the lamps *Make rig safe* turned off, then releases its
 *   blackout. Which units is remembered **per project** for as long as the desk runs, so switching
 *   away and back still offers it; a unit Undo struck is forgotten.
 * - **The restrike ledger** records when each unit's lamp went off — from any door, a single
 *   command included — so a strike can wait out its restrike time and the panel can warn. A strike
 *   marks its lamp **struck** as it starts, and only a lamp-off that runs its time clears the mark:
 *   a struck lamp is not known off, however its strike ended, but keeps its restrike time.
 *
 * Published as `rigSafety.state` ([snapshot], `plugins/RigSafetySocket.kt`); every operation is REST
 * (`routes/projectRigSafety.kt`), so the socket gains no operation.
 */
class RigSafetyService(private val state: State) {
    /** Wall clock, millis. A test seam: a route test reaches past a lamp's restrike time without waiting it out. */
    @Volatile internal var clock: () -> Long = System::currentTimeMillis

    /** A lamp-off's cool-down. A test seam, as `CommandOutput.holdMsFor` is for a hold. */
    @Volatile internal var coolDownMsFor: (CommandSpec) -> Long = { it.coolDownMs }

    /** One run, as the frame carries it. */
    data class RunSnapshot(
        val id: String,
        val role: CommandRole,
        val purpose: Purpose,
        val startedAtMs: Long,
        val units: List<BulkCommandRun.UnitState>,
        val withoutCommand: Int,
        /**
         * Its end has been acted on — the blackout, *Undo*'s lamps, what is not off — not merely its
         * last hold ended, so no frame shows a finished run without them.
         */
        val finished: Boolean,
        val cancelled: Boolean,
    )

    /** One unit whose lamp went off on this desk, when it can be struck again, and when its mains can be cut. */
    data class LampOff(val fixtureKey: String, val offAtMs: Long, val restrikeAtMs: Long, val coolAtMs: Long)

    data class Snapshot(
        val projectId: Int? = null,
        /**
         * The rig is lamped off and blacked out by *Make rig safe*, nothing has sent output since, and
         * every lamp on it has cooled.
         */
        val ready: Boolean = false,
        val readySinceMs: Long? = null,
        /** Made safe, but a lamp is still cooling: when the last one has, and the rig is ready. */
        val coolingUntilMs: Long? = null,
        /** *Make rig safe*'s blackout holds on this project. */
        val blackout: Boolean = false,
        /** The units *Undo* would strike, by key and name. */
        val undo: List<Pair<String, String>> = emptyList(),
        /** Lamped units on the rig not known to be off after the last *Make rig safe*, by name. */
        val notOff: List<String> = emptyList(),
        val run: RunSnapshot? = null,
        val lampsOff: List<LampOff> = emptyList(),
    )

    /** What asking for a run came to. */
    sealed interface Outcome {
        data class Started(val run: RunSnapshot) : Outcome
        /** *Release*, or an *Undo* with nothing to strike: the blackout went, nothing ran. */
        data object Released : Outcome
        data class Refused(val conflict: Boolean, val code: String, val message: String) : Outcome
    }

    private data class Undo(val units: LinkedHashMap<String, String> = LinkedHashMap())

    private val lock = Any()
    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    @Volatile private var show: Show? = null
    private var scope = newScope()
    private var run: BulkCommandRun? = null

    /** The run in flight, until its end has been acted on — what refuses a second run, *Undo* and *Release*. */
    private var active: BulkCommandRun? = null
    /** Made safe and nothing sent since — ready once [coolUntilMs] has passed. */
    private var safe = false
    private var safeSinceMs: Long? = null
    private var coolUntilMs: Long? = null
    private var safeRevision = 0L
    private var notOff: List<String> = emptyList()
    private val undoByProject = ConcurrentHashMap<Int, Undo>()

    /** (project id, fixture key) → when its lamp went off, and its restrike time. */
    private val lampOffs = ConcurrentHashMap<Pair<Int, String>, LampOff>()

    /** (project id, fixture key) → the strike that marked the lamp struck: its last strike since a lamp-off. */
    private val struck = ConcurrentHashMap<Pair<Int, String>, CommandOutput.Running>()

    /**
     * Each lamp-off in flight → the strike that had marked its lamp when it started, the one mark it
     * clears: a strike can start between a hold leaving [CommandOutput] and its end hook.
     */
    private val clears = ConcurrentHashMap<CommandOutput.Running, CommandOutput.Running>()

    // ─── Lifecycle ───────────────────────────────────────────────────────────────────────────

    /**
     * Follow [next]: at start and on every project switch. A run in flight belongs to the show being
     * left and goes with it; *Ready to switch off* is cleared — another show sends its own output.
     */
    fun attach(next: Show) {
        synchronized(lock) {
            scope.cancel()
            scope = newScope()
            show = next
            run = null
            active = null
            safe = false
            safeSinceMs = null
            coolUntilMs = null
            notOff = emptyList()
        }
        val provenance = next.fxEngine.provenance
        scope.launch {
            provenance.flow.collect { update ->
                val stale = synchronized(lock) { safe && update.programmerRevision > safeRevision }
                if (stale) outputResumed("the show's output changed")
            }
        }
        publish()
    }

    fun close() {
        synchronized(lock) {
            scope.cancel()
            show = null
        }
    }

    // ─── Operations ──────────────────────────────────────────────────────────────────────────

    /** Run [role]'s command over [targets] — null for the whole rig. */
    fun runRole(role: CommandRole, targets: List<TargetRef>?): Outcome {
        if (role == CommandRole.OTHER) return Outcome.Refused(false, CODE_ROLE_UNKNOWN, "Name a role: LAMP_ON, LAMP_OFF or RESET")
        val current = show ?: return closing()
        refusal(current)?.let { return it }
        val (units, without) = unitsFor(current, targets, role)
        if (units.isEmpty()) {
            return Outcome.Refused(
                true, CODE_NO_UNITS,
                if (targets == null) "No unit on the rig has a ${roleWords(role)} command"
                else "No unit in the selection has a ${roleWords(role)} command",
            )
        }
        return begin(current, role, Purpose.BULK, units, without) { }
    }

    /**
     * *Make rig safe*: every lamped unit off — [targets]' when given — then *Make rig safe*'s
     * blackout, then *Ready to switch off* when every lamped unit on the rig is known off — by this
     * run or an earlier lamp-off, and not struck since — and once the last of those lamps has cooled.
     * *Undo* remembers only the lamps this run turned off, never one that was already off.
     */
    fun makeSafe(targets: List<TargetRef>?): Outcome {
        val current = show ?: return closing()
        refusal(current)?.let { return it }
        val projectId = current.project.id.value
        val (units, without) = unitsFor(current, targets, CommandRole.LAMP_OFF)
        val alreadyOff = lampsKnownOff(projectId)
        return begin(current, CommandRole.LAMP_OFF, Purpose.MAKE_SAFE, units, without) { finished ->
            if (finished.cancelled) return@begin
            val off = finished.snapshot().filter { it.status == BulkCommandRun.Status.DONE }
            val lamped = unitsFor(current, null, CommandRole.LAMP_OFF).first
            state.scalerHolderFor(projectId).setSafeBlackout(true)
            val now = clock()
            val (cooling, unknown) = synchronized(lock) {
                // Read under the lock: a strike that starts after this finds `safe` set and clears it.
                val knownOff = lampsKnownOff(projectId)
                val coolAt = lamped.filter { it.fixtureKey in knownOff }.maxOfOrNull { lampOffs[projectId to it.fixtureKey]?.coolAtMs ?: 0L }
                val undo = undoByProject.getOrPut(projectId) { Undo() }
                off.filter { it.unit.fixtureKey !in alreadyOff }.forEach { undo.units[it.unit.fixtureKey] = it.unit.fixtureName }
                notOff = lamped.filter { it.fixtureKey !in knownOff }.map { it.fixtureName }
                if (show !== current) {
                    // Another show is attached and sends its own output: leave its state alone.
                } else if (notOff.isEmpty()) {
                    safe = true
                    safeSinceMs = now
                    coolUntilMs = coolAt?.takeIf { it > now }
                    safeRevision = current.fxEngine.provenance.currentProgrammerRevision
                } else {
                    safe = false
                    safeSinceMs = null
                    coolUntilMs = null
                }
                coolUntilMs to notOff.size
            }
            cooling?.let { until ->
                scope.launch {
                    // Re-checked against [clock] rather than trusting one long delay: the wall clock can slew.
                    while (true) {
                        val left = until - clock()
                        if (left <= 0) break
                        delay(left.coerceAtMost(COOLING_RECHECK_MS))
                    }
                    publish()
                }
            }
            logger.info("Rig made safe: {} lamps off by this run, {} on the rig not known off, blacked out", off.size, unknown)
        }
    }

    /** The units of [projectId] whose lamp the restrike ledger holds as off and no strike has started on since. */
    private fun lampsKnownOff(projectId: Int): Set<String> =
        lampOffs.keys.filter { it.first == projectId && !struck.containsKey(it) }.mapTo(HashSet()) { it.second }

    /** *Undo*: strike, staggered, the lamps *Make rig safe* turned off here, then release its blackout. */
    fun undo(): Outcome {
        val current = show ?: return closing()
        val projectId = current.project.id.value
        val remembered = synchronized(lock) { undoByProject[projectId]?.units?.keys?.toList().orEmpty() }
        if (remembered.isEmpty()) {
            if (!state.scalerHolderFor(projectId).safeBlackout.value) {
                return Outcome.Refused(true, CODE_NOTHING_TO_UNDO, "Make rig safe has nothing to undo on this show")
            }
            return releaseBlackout()
        }
        refusal(current)?.let { return it }
        val (units, _) = unitsFor(current, remembered.map { TargetRef.Fixture(it) }, CommandRole.LAMP_ON)
        val outcome = begin(current, CommandRole.LAMP_ON, Purpose.UNDO, units, 0) { finished ->
            if (finished.cancelled) return@begin
            val lit = finished.snapshot().filter { it.status == BulkCommandRun.Status.DONE }.map { it.unit.fixtureKey }
            synchronized(lock) { undoByProject[projectId]?.units?.keys?.removeAll(lit.toSet()) }
            releaseBlackout(projectId)
        }
        if (outcome is Outcome.Started) {
            // A unit repatched away, or without a strike now, has nothing to undo.
            val keep = units.map { it.fixtureKey }.toSet()
            synchronized(lock) { undoByProject[projectId]?.units?.keys?.retainAll(keep) }
            // The operator has asked for the show back, whether or not the first strike waits.
            outputResumed("Undo strikes the lamps")
        }
        return outcome
    }

    /**
     * Release *Make rig safe*'s blackout on the current show, keeping what *Undo* would strike. Refused
     * while a run is in flight: its end sets or releases the blackout itself.
     */
    fun releaseBlackout(): Outcome {
        val current = show ?: return closing()
        synchronized(lock) { if (active != null) return busy() }
        releaseBlackout(current.project.id.value)
        return Outcome.Released
    }

    private fun releaseBlackout(projectId: Int) {
        state.scalerHolderFor(projectId).setSafeBlackout(false)
        outputResumed("the blackout was released")
        publish()
    }

    /**
     * Stop the run in flight: what has not started is cancelled, a hold running finishes. A run whose
     * last hold has ended but whose end is still being acted on has nothing left to stop.
     */
    fun cancel(): Boolean {
        val current = synchronized(lock) { active } ?: return false
        if (!current.finished) current.cancel()
        return true
    }

    // ─── Hooks ───────────────────────────────────────────────────────────────────────────────

    /** Every hold the show starts, whichever door: any but a lamp-off sends output, and a strike marks its lamp struck. */
    fun commandStarted(projectId: Int, running: CommandOutput.Running) {
        val role = running.command.spec.role
        val key = projectId to running.fixtureKey
        when (role) {
            CommandRole.LAMP_ON -> struck[key] = running
            CommandRole.LAMP_OFF -> struck[key]?.let { clears[running] = it }
            else -> {}
        }
        if (role != CommandRole.LAMP_OFF) outputResumed("'${running.command.spec.label}' ran on '${running.fixtureName}'")
    }

    /**
     * Every hold the show ends: a lamp-off that ran its time is the restrike ledger's, a strike clears it.
     * A lamp-off sent to a lamp the ledger already holds as off keeps the time it went off, unless the
     * lamp was struck since.
     */
    fun commandEnded(projectId: Int, running: CommandOutput.Running, ended: CommandOutput.Ended) {
        val marked = clears.remove(running)
        if (ended != CommandOutput.Ended.COMPLETED) return
        val key = projectId to running.fixtureKey
        when (running.command.spec.role) {
            CommandRole.LAMP_OFF -> {
                val restrike = runCatching {
                    show?.let { s -> FixtureCommands.of(s.fixtures.untypedFixture(running.fixtureKey)) }
                        ?.firstOrNull { it.spec.role == CommandRole.LAMP_ON }?.spec?.restrikeMs
                }.getOrNull() ?: 0L
                val now = clock()
                val off = LampOff(running.fixtureKey, now, now + restrike, now + coolDownMsFor(running.command.spec))
                if (marked != null && struck.remove(key, marked)) lampOffs[key] = off else lampOffs.putIfAbsent(key, off)
            }
            CommandRole.LAMP_ON -> lampOffs.remove(key)
            else -> return
        }
        publish()
    }

    // ─── Internals ───────────────────────────────────────────────────────────────────────────

    private fun begin(
        current: Show,
        role: CommandRole,
        purpose: Purpose,
        units: List<BulkUnit>,
        without: Int,
        onFinished: (BulkCommandRun) -> Unit,
    ): Outcome {
        val projectId = current.project.id.value
        val created: BulkCommandRun
        synchronized(lock) {
            if (active != null) return busy()
            created = BulkCommandRun(
                id = UUID.randomUUID().toString(),
                role = role,
                purpose = purpose,
                units = units,
                withoutCommand = without,
                startedAtMs = clock(),
                clock = { clock() },
                readyAt = { key -> lampOffs[projectId to key]?.restrikeAtMs },
                start = { unit -> start(current, unit) },
                onChange = ::publish,
            )
            run = created
            active = created
            if (purpose == Purpose.MAKE_SAFE) notOff = emptyList()
        }
        logger.info("Bulk {} ({}) over {} units", role, purpose, units.size)
        scope.launch {
            try {
                created.execute()
                runCatching { onFinished(created) }.onFailure { logger.warn("Bulk run end failed", it) }
            } finally {
                // Only once its end has been acted on may another run start: a late end would land on it.
                synchronized(lock) { if (active === created) active = null }
                publish()
            }
        }
        publish()
        return Outcome.Started(runSnapshot(created))
    }

    private fun start(current: Show, unit: BulkUnit): Start =
        when (val outcome = current.commandOutput.run(unit.fixtureKey, unit.command)) {
            is CommandOutput.Outcome.Started -> {
                val done = outcome.running.done
                Start.Started(outcome.running.endsAt.toEpochMilli(), scope.async { done.await() == CommandOutput.Ended.COMPLETED })
            }
            is CommandOutput.Outcome.Busy -> Start.Refused("COMMAND_BUSY", "Running '${outcome.running.command.spec.label}'")
            is CommandOutput.Outcome.Parked -> Start.Refused("COMMAND_PARKED", outcome.message)
            is CommandOutput.Outcome.Unknown -> Start.Refused("COMMAND_UNKNOWN", outcome.message)
            CommandOutput.Outcome.Blind -> Start.Refused("COMMAND_BLIND", "The programmer went blind")
            is CommandOutput.Outcome.VenueHeld -> Start.Refused(VenueHoldService.CODE_VENUE_HOLD, outcome.message)
        }

    /** Why no command can run on [current] at all — Blind, the venue hold — or null. */
    private fun refusal(current: Show): Outcome.Refused? {
        if (current.programmerStore.blind) {
            return Outcome.Refused(true, "COMMAND_BLIND", "The programmer is blind: a command would reach the rig, so it is refused until Blind is off")
        }
        state.venueHold.effectsRefusal()?.let { return Outcome.Refused(true, VenueHoldService.CODE_VENUE_HOLD, it) }
        return null
    }

    /**
     * The units [targets] name (null: every unit on the rig) that have a command of [role], in order,
     * and how many named have none. A head or a group member that is a head is its fixture.
     */
    private fun unitsFor(current: Show, targets: List<TargetRef>?, role: CommandRole): Pair<List<BulkUnit>, Int> {
        val fixtures = current.fixtures
        val keys = LinkedHashSet<String>()
        if (targets == null) {
            fixtures.fixtures.forEach { keys += it.key }
        } else {
            for (target in targets) {
                when (target) {
                    is TargetRef.Fixture -> runCatching { fixtures.untypedGroupableFixture(target.key) }.getOrNull()?.let { keys += ownerKey(it) }
                    is TargetRef.Group -> runCatching { fixtures.untypedGroup(target.key).fixtures }.getOrNull()?.forEach { keys += ownerKey(it) }
                }
            }
        }
        var without = 0
        val units = keys.mapNotNull { key ->
            val fixture = runCatching { fixtures.untypedFixture(key) }.getOrNull() ?: return@mapNotNull null
            val command = FixtureCommands.of(fixture).firstOrNull { it.spec.role == role }
            if (command == null) {
                without++
                return@mapNotNull null
            }
            BulkUnit(fixture.key, fixture.fixtureName, command.name, command.spec.restrikeMs, command.spec.strikeSpacingMs)
        }
        return units to (if (targets == null) 0 else without)
    }

    private fun ownerKey(member: Any): String = when (member) {
        is FixtureElement<*> -> member.parentFixture.key
        is Fixture -> member.key
        else -> member.toString()
    }

    private fun outputResumed(why: String) {
        val was = synchronized(lock) {
            val was = safe
            safe = false
            safeSinceMs = null
            coolUntilMs = null
            was
        }
        if (was) {
            logger.info("No longer ready to switch off: {}", why)
            publish()
        }
    }

    private fun publish() {
        val current = show
        val projectId = current?.project?.id?.value
        // Read and assigned under the lock: two publishes from two threads must not land out of order.
        // The show built this project's holder, so the read touches no database.
        synchronized(lock) {
            val blackout = projectId?.let { state.scalerHolderFor(it).safeBlackout.value } ?: false
            val cooling = coolUntilMs?.takeIf { safe && clock() < it }
            val ready = safe && cooling == null
            _snapshot.value = Snapshot(
                projectId = projectId,
                ready = ready,
                readySinceMs = if (ready) maxOf(safeSinceMs ?: 0L, coolUntilMs ?: 0L) else null,
                coolingUntilMs = cooling,
                blackout = blackout,
                undo = projectId?.let { undoByProject[it]?.units?.map { (k, n) -> k to n } }.orEmpty(),
                notOff = notOff,
                run = run?.let { runSnapshot(it, finished = it.finished && active !== it) },
                lampsOff = lampOffs.filterKeys { it.first == projectId }.values.sortedBy { it.offAtMs },
            )
        }
    }

    private fun runSnapshot(run: BulkCommandRun, finished: Boolean = false) = RunSnapshot(
        id = run.id,
        role = run.role,
        purpose = run.purpose,
        startedAtMs = run.startedAtMs,
        units = run.snapshot(),
        withoutCommand = run.withoutCommand,
        finished = finished,
        cancelled = run.cancelled,
    )

    private fun busy() = Outcome.Refused(true, CODE_RUN_BUSY, "Another bulk command is running — wait for it, or cancel it")

    private fun closing() = Outcome.Refused(true, "COMMAND_UNKNOWN", "The show is closing")

    private fun roleWords(role: CommandRole) = when (role) {
        CommandRole.LAMP_ON -> "lamp-on"
        CommandRole.LAMP_OFF -> "lamp-off"
        CommandRole.RESET -> "reset"
        CommandRole.OTHER -> "matching"
    }

    private fun newScope() =
        CoroutineScope(SupervisorJob() + Dispatchers.Default + LiveSentryContext() + reportingExceptionHandler(RigSafetyService::class.java))

    companion object {
        const val CODE_RUN_BUSY = "COMMAND_RUN_BUSY"
        const val CODE_NO_UNITS = "COMMAND_NO_UNITS"
        const val CODE_ROLE_UNKNOWN = "COMMAND_ROLE_UNKNOWN"
        const val CODE_NOTHING_TO_UNDO = "RIG_SAFE_NOTHING_TO_UNDO"

        /** The longest the cooling-to-ready check sleeps before reading the clock again. */
        internal const val COOLING_RECHECK_MS = 1_000L
    }
}
