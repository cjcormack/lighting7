package uk.me.cormack.lighting7.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.fixture.FixtureTriggers
import uk.me.cormack.lighting7.models.DaoEffectTubeStates
import uk.me.cormack.lighting7.models.DaoFixturePatch
import uk.me.cormack.lighting7.models.DaoFixturePatches
import uk.me.cormack.lighting7.models.nowUtc
import uk.me.cormack.lighting7.show.FixturesChangeListener
import uk.me.cormack.lighting7.show.Show
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

private val logger = LoggerFactory.getLogger("EffectsService")

/**
 * The desk's one-shot effects (stage-view plan session 9, D15, D16): the **arm**, every **fire**,
 * the **tubes** that are spent, and the cue events a GO schedules.
 *
 * - **Arming is desk-wide and lapses.** One arm for every window, every surface and every cannon,
 *   held for [DEFAULT_ARM] unless the arm asks for longer, and dropped early on a disarm, a project
 *   switch or any stack stop. It is runtime only: a restart comes up disarmed. While armed (and not
 *   rehearsing) it holds every cannon's arm channel high through the show's [TriggerOutput].
 * - **A fire is a pulse.** The trigger channel goes to [FixtureTriggers.FIRE_LEVEL] for
 *   [FixtureTriggers.PULSE] and back to idle, timed here, written above composition through the
 *   trigger output. It needs the arm; a spent tube warns and sends nothing.
 * - **Rehearsal.** While the programmer is blind, or when the caller asks (a window whose vis source
 *   is the programmer), a fire is *rehearsed*: it is announced to every window — so the confetti
 *   still flies in every Stage view — and nothing reaches the wire, no arm is needed and no tube is
 *   spent. That is how a cue event is plotted before the night.
 * - **Cue events fire on GO into their cue only**, each at its offset; unarmed at GO they are
 *   skipped and announced, never queued. An event armed at GO whose arm has dropped by its offset
 *   — a disarm, a lapse, a switch, even if armed again since — is skipped too: the arm it was
 *   scheduled under is the one it needs. Extending a held arm is not a drop.
 *
 * **Nothing here blocks a GO or runs inside a caller's transaction** (the pool is one SQLite
 * connection). The GO hook only schedules; a fire touches memory and the trigger output, and the
 * spent tube is written by [worker], one thread, off every caller's path. Tube state is read into
 * memory when a show starts.
 *
 * Published as `effects.armed` ([armed], a `StateFlow`, so the subscription is the connect snapshot),
 * `effects.fired` ([fired]) and `effects.skipped` ([skipped]) — `plugins/EffectsSocket.kt`.
 */
class EffectsService(private val state: State) {

    /** The arm and the spent tubes, as `effects.armed` carries them. */
    data class ArmState(
        /** Wall clock, millis, when the arm lapses; null when disarmed. */
        val armedUntilMs: Long? = null,
        /** The programmer is blind: every fire is rehearsed. */
        val rehearsal: Boolean = false,
        val spent: List<SpentTube> = emptyList(),
        val projectId: Int? = null,
    ) {
        val armed: Boolean get() = armedUntilMs != null
    }

    data class SpentTube(val fixtureKey: String, val trigger: String, val spentAt: Instant)

    /** Who asked for a fire. */
    enum class Source { CUE, PANEL, SURFACE }

    /** A fire that happened — for real or rehearsed. */
    data class Fired(
        val fixtureKey: String,
        val fixtureName: String,
        val trigger: String,
        val label: String,
        val at: Instant,
        val rehearsed: Boolean,
        val source: Source,
        val cueId: Int? = null,
    )

    /** Fires that did not happen, announced so the operator knows. */
    data class Skipped(
        val reason: SkipReason,
        val message: String,
        val tubes: List<Pair<String, String>>,
        val source: Source,
        val cueId: Int? = null,
        val cueLabel: String? = null,
    )

    enum class SkipReason { UNARMED, SPENT, ARM_DROPPED, UNKNOWN_TRIGGER }

    /** What a request to fire one tube came to. */
    sealed interface FireOutcome {
        data class Done(val fired: Fired) : FireOutcome
        data object Unarmed : FireOutcome
        data class Spent(val spentAt: Instant) : FireOutcome
        data class Unknown(val message: String) : FireOutcome
    }

    /** One event as a GO hands it over: which tube, and when after GO. */
    data class CueEventFire(val fixtureKey: String, val trigger: String, val offsetMs: Long)

    private val lock = Any()
    private val _armed = MutableStateFlow(ArmState())
    val armed: StateFlow<ArmState> = _armed.asStateFlow()

    private val _fired = MutableSharedFlow<Fired>(extraBufferCapacity = 64)
    val fired: SharedFlow<Fired> = _fired.asSharedFlow()

    private val _skipped = MutableSharedFlow<Skipped>(extraBufferCapacity = 64)
    val skipped: SharedFlow<Skipped> = _skipped.asSharedFlow()

    @Volatile
    private var show: Show? = null

    /** The show's lifetime: pulses, the lapse and pending events all die with it on a switch. */
    private var scope = newScope()
    private var lapseJob: Job? = null

    /**
     * Bumped when an arm **begins** (disarmed → armed) and on every disarm, so an event can tell
     * whether the arm it was scheduled under still holds. Extending a held arm leaves it alone.
     */
    @Volatile
    private var armEpoch = 0L

    /** Bumped on every arm, extension included: a lapse job disarms only if no arm has come since it. */
    private var lapseGeneration = 0L

    /** Spent tubes by (patch uuid, trigger). */
    private val spent = ConcurrentHashMap<Pair<UUID, String>, Instant>()

    /** Patch key → uuid for the current project, so a fire by key reaches its uuid-keyed tube state. */
    @Volatile
    private var patchUuids: Map<String, UUID> = emptyMap()

    /** The show's stored tube state has been read; until then no real fire is allowed (fail closed). */
    @Volatile
    private var tubesLoaded = false

    private val listener = object : FixturesChangeListener {
        override fun fixturesChanged() = refreshPatches()
        override fun patchListChanged() = refreshPatches()
    }

    private val worker: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "effects-tubes").apply { isDaemon = true }
    }

    // ─── Lifecycle ───────────────────────────────────────────────────────────────────────────

    /**
     * Follow [next], disarmed: called at start and on every project switch. The old show's pulses,
     * lapse and pending events go with it, and its tube state is read afresh.
     */
    fun attach(next: Show) {
        val previous: Show?
        synchronized(lock) {
            previous = show
            scope.cancel()
            scope = newScope()
            lapseJob = null
            armEpoch++
            show = next
            spent.clear()
            patchUuids = emptyMap()
            tubesLoaded = false
        }
        previous?.fixtures?.unregisterListener(listener)
        previous?.triggerOutput?.let { it.setArmHigh(false); it.endAllPulses() }
        next.fixtures.registerListener(listener)
        next.triggerOutput.setArmHigh(false)
        publish()
        submit { loadTubes(next) }
    }

    fun close() {
        synchronized(lock) {
            scope.cancel()
            show?.fixtures?.unregisterListener(listener)
            show = null
        }
        worker.shutdownNow()
    }

    // ─── The arm ─────────────────────────────────────────────────────────────────────────────

    /** Arm the desk for [seconds] (default [DEFAULT_ARM]); re-arming restarts the clock. */
    fun arm(seconds: Long? = null): ArmState {
        val length = Duration.ofSeconds((seconds ?: DEFAULT_ARM.seconds).coerceIn(MIN_ARM.seconds, MAX_ARM.seconds))
        val current = show ?: return _armed.value
        synchronized(lock) {
            if (!_armed.value.armed) armEpoch++
            lapseJob?.cancel()
            val generation = ++lapseGeneration
            val until = System.currentTimeMillis() + length.toMillis()
            // Cancellation is cooperative, so a job already past its delay may still run: it
            // disarms only if no arm has come since it was scheduled.
            lapseJob = scope.launch {
                delay(length.toMillis())
                disarm("lapsed", lapseGeneration = generation)
            }
            _armed.value = _armed.value.copy(armedUntilMs = until)
        }
        current.triggerOutput.setArmHigh(!blind(current))
        logger.info("Desk armed for {} s", length.seconds)
        publish()
        return _armed.value
    }

    /**
     * Drop the arm, if held. [why] is for the log: a disarm, a lapse, a stack stop, a switch.
     * [lapseGeneration] is the lapse job's: a stale one, outrun by a later arm, does nothing.
     */
    fun disarm(why: String, lapseGeneration: Long? = null): ArmState {
        val wasArmed: Boolean
        synchronized(lock) {
            if (lapseGeneration != null && lapseGeneration != this.lapseGeneration) return _armed.value
            wasArmed = _armed.value.armed
            armEpoch++
            lapseJob?.cancel()
            lapseJob = null
            _armed.value = _armed.value.copy(armedUntilMs = null)
        }
        show?.triggerOutput?.let { it.setArmHigh(false); it.endAllPulses() }
        if (wasArmed) logger.info("Desk disarmed ({})", why)
        publish()
        return _armed.value
    }

    /** `CueStackManager.deactivateStack`: any stack stopping drops the arm. */
    fun onStackStopped(stackId: Int) {
        if (_armed.value.armed) disarm("stack $stackId stopped")
    }

    /** The programmer went blind or came back: arm channels follow, and fires rehearse or not. */
    fun onBlindChanged() {
        val current = show ?: return
        current.triggerOutput.setArmHigh(_armed.value.armed && !blind(current))
        publish()
    }

    private fun blind(show: Show): Boolean = show.programmerStore.blind

    // ─── Firing ──────────────────────────────────────────────────────────────────────────────

    /**
     * Fire one tube now. [rehearse] asks for a rehearsal whatever the desk's state — a window whose
     * vis source is the programmer; a blind programmer rehearses every fire anyway.
     */
    fun fire(fixtureKey: String, trigger: String, source: Source, rehearse: Boolean = false, cueId: Int? = null): FireOutcome {
        val current = show ?: return FireOutcome.Unknown("The show is not running")
        val resolved = current.triggerOutput.triggersOf(fixtureKey).firstOrNull {
            it.name.equals(trigger, ignoreCase = true) || it.spec.label.equals(trigger, ignoreCase = true)
        } ?: return FireOutcome.Unknown(unknownTrigger(current, fixtureKey, trigger))
        val fixtureName = runCatching { current.fixtures.untypedFixture(fixtureKey).fixtureName }.getOrDefault(fixtureKey)
        val rehearsal = rehearse || blind(current)
        val at = nowUtc()
        if (!rehearsal) {
            if (!_armed.value.armed) return FireOutcome.Unarmed
            // A tube whose state the desk has not read fails closed rather than firing unrecorded:
            // the show's stored state is read at start, and a cannon patched since is looked up here.
            if (!tubesLoaded) return FireOutcome.Unknown("The desk is still reading the cannons' tube state — try again in a moment")
            val known = patchUuids[fixtureKey] ?: lookUpPatch(current, fixtureKey)
                ?: return FireOutcome.Unknown("'$fixtureKey' is not a patched fixture in this project")
            // The check and the mark are one step, so two fires at once cannot both find the tube loaded.
            val uuid = synchronized(lock) {
                if (!_armed.value.armed) return FireOutcome.Unarmed
                spent[known to resolved.name]?.let { return FireOutcome.Spent(it) }
                if (!current.triggerOutput.startPulse(fixtureKey, resolved.name)) {
                    return FireOutcome.Unknown(unknownTrigger(current, fixtureKey, trigger))
                }
                spent[known to resolved.name] = at
                known
            }
            scope.launch {
                delay(FixtureTriggers.PULSE.toMillis())
                current.triggerOutput.endPulse(fixtureKey, resolved.name)
            }
            submit { writeSpent(uuid, resolved.name, at) }
            logger.info("Fired '{}' on '{}' ({})", resolved.name, fixtureKey, source)
            publish()
        } else {
            logger.info("Rehearsed '{}' on '{}' ({})", resolved.name, fixtureKey, source)
        }
        val fired = Fired(fixtureKey, fixtureName, resolved.name, resolved.spec.label, at, rehearsal, source, cueId)
        _fired.tryEmit(fired)
        return FireOutcome.Done(fired)
    }

    /** A MIDI `FireTrigger`: needs the arm, like every fire, and announces its refusal since no one is looking at a reply. */
    fun fireFromSurface(fixtureKey: String, trigger: String) {
        when (val outcome = fire(fixtureKey, trigger, Source.SURFACE)) {
            is FireOutcome.Done -> Unit
            FireOutcome.Unarmed -> announce(SkipReason.UNARMED, "Not armed: the surface's fire on '$fixtureKey' did nothing", listOf(fixtureKey to trigger), Source.SURFACE)
            is FireOutcome.Spent -> announce(SkipReason.SPENT, "'$fixtureKey' $trigger is spent — reload it first", listOf(fixtureKey to trigger), Source.SURFACE)
            is FireOutcome.Unknown -> announce(SkipReason.UNKNOWN_TRIGGER, outcome.message, listOf(fixtureKey to trigger), Source.SURFACE)
        }
    }

    /** Mark a tube — or, with [trigger] null, every tube of [fixtureKey] — loaded again. */
    fun reload(fixtureKey: String, trigger: String?): ArmState {
        val current = show ?: return _armed.value
        val uuid = patchUuids[fixtureKey] ?: return _armed.value
        val names = current.triggerOutput.triggersOf(fixtureKey)
            .filter { trigger == null || it.name.equals(trigger, ignoreCase = true) || it.spec.label.equals(trigger, ignoreCase = true) }
            .map { it.name }
        if (names.isEmpty()) return _armed.value
        names.forEach { spent.remove(uuid to it) }
        submit { clearSpent(uuid, names) }
        logger.info("Reloaded {} on '{}'", names.joinToString(), fixtureKey)
        publish()
        return _armed.value
    }

    // ─── Cue events ──────────────────────────────────────────────────────────────────────────

    /**
     * GO into [cueId]: schedule its [events]. Returns at once — this runs on every GO path, inside
     * `activateCueInStack` and the AI's `applyCue`, and must neither block nor fail them.
     *
     * Rehearsal and the arm are judged **now**: blind at GO rehearses them all; unarmed at GO skips
     * them all with one announcement, and arming a moment later does not bring them back.
     */
    fun onCueGo(cueId: Int, cueLabel: String, events: List<CueEventFire>) {
        if (events.isEmpty()) return
        val current = show ?: return
        val rehearsal = blind(current)
        if (!rehearsal && !_armed.value.armed) {
            logger.warn("Cue {} went unarmed: skipped {} event(s)", cueLabel, events.size)
            announce(
                SkipReason.UNARMED,
                "Not armed: $cueLabel's ${events.size} event${if (events.size == 1) "" else "s"} skipped",
                events.map { it.fixtureKey to it.trigger }, Source.CUE, cueId, cueLabel,
            )
            return
        }
        val epoch = armEpoch
        val jobScope = synchronized(lock) { scope }
        for (event in events.sortedBy { it.offsetMs }) {
            jobScope.launch {
                if (event.offsetMs > 0) delay(event.offsetMs)
                fireEvent(cueId, cueLabel, event, rehearsal, epoch)
            }
        }
    }

    private fun fireEvent(cueId: Int, cueLabel: String, event: CueEventFire, rehearsal: Boolean, epoch: Long) {
        if (!rehearsal && (armEpoch != epoch || !_armed.value.armed)) {
            logger.warn("Cue {}: the arm dropped before '{}' {} fired; skipped", cueLabel, event.fixtureKey, event.trigger)
            announce(SkipReason.ARM_DROPPED, "The arm dropped before $cueLabel's event on '${event.fixtureKey}' fired", listOf(event.fixtureKey to event.trigger), Source.CUE, cueId, cueLabel)
            return
        }
        when (val outcome = fire(event.fixtureKey, event.trigger, Source.CUE, rehearse = rehearsal, cueId = cueId)) {
            is FireOutcome.Done -> Unit
            // The arm can only have lapsed in the instant between the check above and the fire.
            FireOutcome.Unarmed -> announce(SkipReason.ARM_DROPPED, "The arm dropped before $cueLabel's event on '${event.fixtureKey}' fired", listOf(event.fixtureKey to event.trigger), Source.CUE, cueId, cueLabel)
            is FireOutcome.Spent -> {
                logger.warn("Cue {}: '{}' {} is spent; skipped", cueLabel, event.fixtureKey, event.trigger)
                announce(SkipReason.SPENT, "$cueLabel: '${event.fixtureKey}' ${event.trigger} is spent — nothing sent", listOf(event.fixtureKey to event.trigger), Source.CUE, cueId, cueLabel)
            }
            is FireOutcome.Unknown -> {
                logger.warn("Cue {}: {}", cueLabel, outcome.message)
                announce(SkipReason.UNKNOWN_TRIGGER, "$cueLabel: ${outcome.message}", listOf(event.fixtureKey to event.trigger), Source.CUE, cueId, cueLabel)
            }
        }
    }

    private fun announce(reason: SkipReason, message: String, tubes: List<Pair<String, String>>, source: Source, cueId: Int? = null, cueLabel: String? = null) {
        _skipped.tryEmit(Skipped(reason, message, tubes, source, cueId, cueLabel))
    }

    private fun unknownTrigger(show: Show, fixtureKey: String, trigger: String): String {
        val triggers = show.triggerOutput.triggersOf(fixtureKey)
        return if (triggers.isEmpty()) "'$fixtureKey' has no one-shot trigger"
        else "'$fixtureKey' has no trigger '$trigger' (it has ${triggers.joinToString { "${it.name} (${it.spec.label})" }})"
    }

    // ─── Publishing ──────────────────────────────────────────────────────────────────────────

    /** Snapshot and store under one lock, so a slower publish can never put back an older spent list. */
    private fun publish() {
        synchronized(lock) {
            val current = show
            val byUuid = patchUuids.entries.associate { (k, v) -> v to k }
            val tubes = spent.entries.mapNotNull { (key, at) ->
                val fixtureKey = byUuid[key.first] ?: return@mapNotNull null
                SpentTube(fixtureKey, key.second, at)
            }.sortedWith(compareBy({ it.fixtureKey }, { it.trigger }))
            _armed.value = _armed.value.copy(
                rehearsal = current?.let { blind(it) } ?: false,
                spent = tubes,
                projectId = current?.project?.id?.value,
            )
        }
    }

    // ─── Tube state (the worker) ─────────────────────────────────────────────────────────────

    private fun refreshPatches() {
        val current = show ?: return
        submit {
            patchUuids = readPatchUuids(current)
            publish()
        }
    }

    private fun loadTubes(target: Show) {
        val uuids = readPatchUuids(target)
        val rows = transaction(state.database) {
            DaoEffectTubeStates.selectAll().where { DaoEffectTubeStates.patchUuid inList uuids.values.toList() }
                .map { (it[DaoEffectTubeStates.patchUuid] to it[DaoEffectTubeStates.trigger]) to it[DaoEffectTubeStates.spentAt] }
        }
        if (show !== target) return
        patchUuids = uuids
        rows.forEach { (k, at) -> spent.putIfAbsent(k, at) }
        tubesLoaded = true
        publish()
    }

    /**
     * A cannon patched since the show's tube state was read, which the worker's refresh has not
     * reached yet: its uuid and any spent row it has, read now so a fire neither waits nor goes
     * unrecorded. Off every transaction — a fire never runs inside one.
     */
    private fun lookUpPatch(target: Show, fixtureKey: String): UUID? {
        val (uuid, rows) = transaction(state.database) {
            val uuid = DaoFixturePatch.find { (DaoFixturePatches.project eq target.project.id) and (DaoFixturePatches.key eq fixtureKey) }
                .firstOrNull()?.uuid ?: return@transaction null to emptyList()
            uuid to DaoEffectTubeStates.selectAll().where { DaoEffectTubeStates.patchUuid eq uuid }
                .map { (it[DaoEffectTubeStates.patchUuid] to it[DaoEffectTubeStates.trigger]) to it[DaoEffectTubeStates.spentAt] }
        }
        if (uuid == null || show !== target) return null
        rows.forEach { (k, at) -> spent.putIfAbsent(k, at) }
        patchUuids = patchUuids + (fixtureKey to uuid)
        return uuid
    }

    private fun readPatchUuids(target: Show): Map<String, UUID> = transaction(state.database) {
        DaoFixturePatch.find { DaoFixturePatches.project eq target.project.id }
            .filter { FixtureTriggers.reservedNamesForTypeKey(it.fixtureTypeKey).isNotEmpty() }
            .associate { it.key to it.uuid }
    }

    private fun writeSpent(uuid: UUID, trigger: String, at: Instant) {
        transaction(state.database) {
            DaoEffectTubeStates.deleteWhere { (DaoEffectTubeStates.patchUuid eq uuid) and (DaoEffectTubeStates.trigger eq trigger) }
            DaoEffectTubeStates.insert {
                it[patchUuid] = uuid
                it[DaoEffectTubeStates.trigger] = trigger
                it[spentAt] = at
            }
        }
    }

    private fun clearSpent(uuid: UUID, triggers: List<String>) {
        transaction(state.database) {
            DaoEffectTubeStates.deleteWhere { (DaoEffectTubeStates.patchUuid eq uuid) and (DaoEffectTubeStates.trigger inList triggers) }
        }
    }

    /** Queue [work] on the tube worker; never throws into the caller, as a fire must not fail on a write. */
    private fun submit(work: () -> Unit) {
        try {
            worker.execute {
                try {
                    work()
                } catch (e: Exception) {
                    logger.error("Tube state update failed", e)
                }
            }
        } catch (_: RejectedExecutionException) {
            // Shutting down: the write is lost with the process, which is what a restart means anyway.
        }
    }

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    companion object {
        /** How long an arm holds unless asked for longer: the record's 60 s. */
        val DEFAULT_ARM: Duration = Duration.ofSeconds(60)
        val MIN_ARM: Duration = Duration.ofSeconds(5)
        val MAX_ARM: Duration = Duration.ofMinutes(10)
    }
}
