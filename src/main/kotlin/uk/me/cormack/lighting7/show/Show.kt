package uk.me.cormack.lighting7.show

import uk.me.cormack.lighting7.telemetry.LiveSentryContext
import kotlinx.coroutines.*
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.core.eq
import uk.me.cormack.lighting7.dmx.ChannelChange
import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.dmx.LayeredParkSource
import uk.me.cormack.lighting7.dmx.OutputHold
import uk.me.cormack.lighting7.dmx.ParkManager
import uk.me.cormack.lighting7.dmx.ParkSource
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fx.*
import uk.me.cormack.lighting7.midi.GlobalScalerState
import uk.me.cormack.lighting7.models.*
import uk.me.cormack.lighting7.routes.registerUserEffect
import uk.me.cormack.lighting7.scripts.*
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.state.CommandOutput
import uk.me.cormack.lighting7.state.TriggerOutput
import uk.me.cormack.lighting7.state.optionalBoolean
import uk.me.cormack.lighting7.state.optionalString
import uk.me.cormack.lighting7.telemetry.ScriptCacheProbe
import uk.me.cormack.lighting7.telemetry.Tracing
import java.security.MessageDigest
import java.util.concurrent.locks.ReentrantLock
import kotlin.script.experimental.api.*
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost
import kotlin.time.measureTime

/**
 * Trailing debounce for writing live tempo changes back to their rows. Long enough that a
 * tap burst (4 taps ≈ 2 s) coalesces into one write, short enough that a crash loses at
 * most a moment's tapping.
 */
private const val SPEED_MASTER_WRITE_DEBOUNCE_MS = 750L

/**
 * How long a same-project reload takes to move a channel from where the rig was to where the reloaded
 * show puts it ([Show.resume]). Most channels do not move at all; this is for the ones a pulled cue,
 * an Update's re-address or a release changed.
 */
const val RESUME_FADE_MS = 2000L

@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
class Show(
    val state: State,
    val project: DaoProject,
    /**
     * What a same-project reload carries from the show it replaces ([ShowCarry]): the programmer —
     * values, layers, sideband, Blind — and the Locate bookkeeping that pairs with its LOCATE slots,
     * so a located fixture is still located. Null starts both empty. [resume] brings back the rest.
     */
    carried: ShowCarry? = null,
) {
    val fixtures = Fixtures()
    val fxScriptCompiler = FxScriptCompiler(state.scriptingHostConfiguration)
    val fxRegistry = FxRegistry().apply {
        // Load built-in effects from .fx.kts resource files. Compilation is the single biggest
        // cold-boot cost, so it (a) reports progress to the boot bar and (b) compiles in
        // parallel unless disabled via `fx.parallelCompile=false`.
        val fileLoader = FxFileLoader(fxScriptCompiler)
        val parallel = state.config.optionalBoolean("fx.parallelCompile", default = true)
        fileLoader.loadBuiltInEffects(this, parallel = parallel) { done, total ->
            // Only drive the boot bar on the *initial* boot. This constructor also runs on a
            // runtime project switch (ProjectManager.switchProject), where the previous show is
            // already started (isStarted == true) — reporting there would rewind the global boot
            // status from READY back to "compiling", freezing the loading bar on a live app.
            if (state.showOrNull?.isStarted != true) {
                state.bootProgress.updateFxCompile(done, total)
            }
        }
    }
    val programmerStore = carried?.programmerStore ?: ProgrammerStore()

    /**
     * Looks, flattened for per-fixture lookup. See [LookRegistry] for the two invalidation
     * triggers wired below — the patch/group one is easy to miss and silently resolves a Look
     * against stale group membership when absent.
     */
    val lookRegistry = LookRegistry(
        fixtures = { fixtures },
        loader = { uuid -> loadLookSnapshot(state.database, uuid) },
    ).also { registry ->
        fixtures.registerListener(object : FixturesChangeListener {
            // A group row in a Look expands to member fixtures, so any patch or group change
            // invalidates every expansion.
            override fun fixturesChanged() = registry.invalidateAll()
            override fun patchListChanged() = registry.invalidateAll()

            // Covers create and delete. A *contents* change goes through
            // `republishForLookEdit`, which invalidates the one Look directly.
            // `templateListChanged` is deliberately not handled here — a Look expansion holds no
            // template data; `templateRegistry` below has its own listener for that.
            override fun lookListChanged() = registry.invalidateAll()
        })
    }

    /**
     * Templates, cached by uuid.
     *
     * Note the listener set is **narrower** than `lookRegistry`'s, and deliberately: a template's
     * rows are intents resolved per head at cook, with no group expansion and no literals flattened
     * against the patch, so a repatch or a group edit cannot make a cached snapshot wrong. Only the
     * template's own list changing (create / rename / delete) has to drop it — a *contents* change
     * goes through `republishForTemplateEdit`, which invalidates the one entry.
     */
    val templateRegistry = TemplateRegistry(
        loader = { uuid -> loadTemplateSnapshot(state.database, uuid) },
    ).also { registry ->
        fixtures.registerListener(object : FixturesChangeListener {
            override fun templateListChanged() = registry.invalidateAll()
        })
    }

    val cueAssignmentResolver = CueAssignmentResolver()
    val layerResolver = LayerResolver(cueAssignmentResolver, programmerStore)
    val parkManager = ParkManager(
        state.database,
        project.id.value,
        // Releasing park must not move the output: push the parked value into the layers
        // below park before the override is dropped. Writing both the programmer's channel
        // sideband and the controller buffer makes an unpark settle exactly where a manual
        // channel write of the same value would — so a running effect resets to the parked
        // value rather than to 0. `fixtures` is empty at construction time and populated by
        // `start()`; the lambda resolves the controller per call, so there is no ordering
        // dependency here.
        unparkValueSink = { values ->
            for ((universe, channel, value) in values) {
                // Channel sideband with owner UNPARK and touched = false: the hand-down is
                // channel-shaped by nature (lifting one channel to a property entry would
                // freeze its sibling channels into the programmer) and it is not an
                // operator edit, so Record and the Update checklist (Session 3) must not
                // see it. A later deliberate property write absorbs it; Clear releases it.
                programmerStore.putChannel(ProgrammerOwner.UNPARK, universe, channel, value, touched = false)
                val controller = fixtures.controllerOrNull(Universe(0, universe)) ?: continue
                try {
                    // Preferred path: goes through the channel changer, so it also cancels any
                    // fade still running underneath the park.
                    controller.setValuesSuspend(listOf(channel to ChannelChange(value, 0)))
                } catch (_: IllegalStateException) {
                    // `ArtNetController` registers its per-channel changers from a coroutine in
                    // `init`, so a write in the window just after a patch rebuild finds none and
                    // throws. `handlePark` has no error handling above it — an escape here would
                    // tear down the operator's WebSocket. Write the buffer directly instead:
                    // a controller whose changers haven't started can have no fade to cancel.
                    controller.restoreState(mapOf(channel to value))
                }
            }
        },
    )
    /**
     * Every one-shot trigger and arm channel on the rig, held by the desk above composition (stage-view
     * plan session 9) — see [TriggerOutput]. Rebuilt on every fixture-register change.
     */
    val triggerOutput = TriggerOutput(fixtures).also { fixtures.registerListener(it) }

    /**
     * Every fixture command's channel on the rig, and the holds in flight (fixture optics plan
     * session 7) — see [CommandOutput]. Beside the trigger output, under park; its band guard is a
     * transmit modifier, attached in [start].
     */
    val commandOutput = CommandOutput(
        fixtures,
        parkManager,
        isBlind = { programmerStore.blind },
        venueRefusal = { state.venueHold.effectsRefusal() },
        onStarted = { state.rigSafety.commandStarted(it) },
        onEnded = { running, ended -> state.rigSafety.commandEnded(project.id.value, running, ended) },
    ).also { fixtures.registerListener(it) }

    /**
     * What the controllers consult at transmit time in place of the bare [parkManager]: park on top,
     * the command and trigger outputs under it (they never hold the same channel). Every
     * `DbFixtureLoader.loadFixtures` call passes this. A park at a firing level on a trigger or arm
     * channel, or one that would hold a fixture command, is passed over here whatever stored it
     * ([TriggerOutput.admitsPark], [CommandOutput.admitsPark]); [start] also drops the stored ones.
     */
    val outputSource: ParkSource = LayeredParkSource(
        parkManager,
        LayeredParkSource(commandOutput, triggerOutput),
        admitTop = { universe, channel, value ->
            triggerOutput.admitsPark(universe, channel, value) && commandOutput.admitsPark(universe, channel, value)
        },
    )

    /**
     * The show's hold (venue plan P5, session 6): a show on a venue whose pinned version is behind the
     * installed one on output sends zero on every channel but a parked one, while it composes and draws as
     * normal; one running against its older pin keeps the installed version's trigger and command
     * channels idle. Every `DbFixtureLoader.loadFixtures` call passes this, which judges it before it
     * builds a controller and hands [OutputHold] to each. The judging is [State.venueHold]'s.
     */
    val rigHold = RigHold(OutputHold()) { projectId -> state.venueHold.judge(projectId, this) }

    val fxEngine = FxEngine(
        fixtures = fixtures,
        speedMasters = SpeedMasterBank(),
        programmerStore = programmerStore,
        layerResolver = layerResolver,
        parkManager = parkManager,
    )

    /**
     * The programmer's ordered Look layers.
     *
     * Every dependency is supplied lazily: this is built before `fxEngine` finishes construction in
     * source order terms, and `state` is not fully wired until `Show` itself returns.
     */
    val programmerLayerStack = ProgrammerLayerStack(
        fixtures = { fixtures },
        lookRegistry = { lookRegistry },
        templateRegistry = { templateRegistry },
        engine = { fxEngine },
        store = programmerStore,
        state = { state },
    )

    /** The show's speed-master clocks; slot 0 is master 1 (the global tempo). */
    val speedMasterBank: SpeedMasterBank get() = fxEngine.speedMasters
    val locateManager = carried?.locateManager ?: LocateManager()
    val cueStackManager = CueStackManager(
        fxEngine,
        projectId = project.id.value,
        // The holds frame lives on the State, so a socket subscribed before a project switch keeps
        // following it; this show's registry publishes into it from its first (empty) frame.
        holdsSink = state.cueHoldsFrame,
        onHoldsChanged = { fixtures.cueHoldsChanged() },
    )

    /**
     * Global transmit-time scalers (Blackout, Grand Master) — Phase 3 of
     * [docs/plans/completed/control-surface-plan.md]. Attached to every registered DMX controller on
     * show start so toggles take effect immediately without a show-wide restart.
     *
     * The underlying state (blackout / Grand Master flags) is held in a project-scoped
     * [GlobalScalerStateHolder] owned by [State.scalerHolderFor], so operator intent
     * survives project switches within a session (Phase 9).
     */
    val globalScalerState = GlobalScalerState(fixtures, state.scalerHolderFor(project.id.value))
    private val scripts: MutableMap<String, Script> = mutableMapOf()
    private val scriptsLock = ReentrantLock()
    private val scriptingHost = BasicJvmScriptingHost(state.scriptingHostConfiguration)

    // Closed in [close]. `newFixedThreadPoolContext` starts its thread eagerly and does not stop
    // it when the context is collected, so a Show that is replaced (project switch) or discarded
    // (one per test) leaks a live thread until the JVM exits.
    private val runnerPool = newFixedThreadPoolContext(1, "lighting-running-pool")

    /**
     * True once [start] has completed — i.e. fixtures are loaded and the FX engine is running, so
     * show-dependent routes/sockets will serve correct data. Drives [State.isShowReady] / the
     * readiness gate. `@Volatile` because it is written on the background boot dispatcher and read
     * from Netty request threads with no other happens-before edge.
     */
    @Volatile
    var isStarted: Boolean = false
        private set

    /**
     * True from the moment a switch or reload starts tearing this show down. Its registry empties
     * as it goes, and a listener reading the current show's rig then would read an empty one — the
     * desk selection's prune would drop every target a reload is meant to keep. Such readers ask
     * this first.
     */
    @Volatile
    var isClosing: Boolean = false
        private set

    fun beginClose() {
        isClosing = true
    }

    /**
     * Drop every stored park the trigger output refuses — a firing level on a one-shot trigger or
     * its arm (stage-view plan session 9). The output already passes them over
     * ([TriggerOutput.admitsPark]); this keeps the park list honest about it. They can only be rows
     * stored before the Twin Shot's channels were triggers, or brought in by a sync import or a clone.
     */
    internal fun dropRefusedParks() {
        // Twice at most: forgetting a show's own refused re-park exposes the venue's park under it
        // (venue parks), which must be judged too; the second pass finds nothing new to expose.
        repeat(2) {
            var dropped = false
            for (park in parkManager.getAllParked()) {
                val why = triggerOutput.parkRefusal(park.universe, park.channel, park.value)
                    ?: commandOutput.parkRefusal(park.universe, park.channel, park.value)
                    ?: continue
                org.slf4j.LoggerFactory.getLogger("Show").warn("Dropped a stored park: {}", why)
                parkManager.forget(park.universe, park.channel)
                dropped = true
            }
            if (!dropped) return
        }
    }

    /**
     * @param seedValues each universe's channels to start from instead of zero — a same-project
     *   reload's carried frame ([ShowCarry.values]). Every controller seeded from it is also
     *   **frozen** on it, so nothing reaches the wire until [resume] has rebuilt what was running and
     *   crossfades from it; a [start] given seeds must be followed by [resume].
     */
    fun start(seedValues: Map<Universe, Map<Int, UByte>> = emptyMap()) {
        Tracing.span("show.fixtures", "load fixtures") {
            try {
                // Park state must be loaded before controllers are constructed: controllers read
                // it via [ParkSource] on their first transmit.
                parkManager.loadFromDatabase()
                DbFixtureLoader.loadFixtures(
                    project.id.value, fixtures, state.database,
                    parkSource = outputSource, hold = rigHold, seedValues = seedValues,
                )
                // Frozen on the carried frame from the very first frame (the loader seeded the
                // buffer before the transmit loop started, so even the bootstrap frame carries it):
                // what [resume] writes while it rebuilds the cues and the programmer must not reach
                // the rig a piece at a time.
                for (controller in fixtures.controllers) {
                    seedValues[controller.universe]?.let { controller.freeze(it) }
                }
                dropRefusedParks()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Attach the global scaler to every controller so Blackout / Grand Master toggles
        // from a control surface propagate at transmit time.
        globalScalerState.attach()
        // After the scalers, so the command band guard has the last word on a shared channel.
        commandOutput.attach()

        // Write live tempo changes back to their rows, so the stored bpm is "wherever the
        // tempo was last set" and the next boot/import starts there. Trailing-debounced:
        // taps arrive ~2/s and recompute BPM each time, so per-tap writes would be churn.
        // The capture is the bank's SYNCHRONOUS hook, not a changes-flow collector — a
        // collector can be cancelled at close() with an emission still buffered, losing the
        // very last tap; the hook lands in the pending map before setBpm/tap even returns,
        // so the final flush in [close] can't miss it.
        //
        // Installed BEFORE the first reloadSpeedMasters(): load's follower sweep emits a
        // Change when a follower's stored bpm disagrees with the derived value (stale row,
        // older export), and that boot-time correction must reach the row like any other.
        speedMasterBank.onChangeSync = { change ->
            change.uuid?.let { uuid ->
                pendingSpeedMasterWrites[uuid] = change
                speedMasterFlushSignal.trySend(Unit)
            }
        }
        speedMasterFlushJob = GlobalScope.launch(LiveSentryContext()) {
            for (signal in speedMasterFlushSignal) {
                delay(SPEED_MASTER_WRITE_DEBOUNCE_MS)
                flushSpeedMasterWrites()
            }
        }

        // Load (or lazily seed) this project's speed masters before the engine starts, so
        // every clock begins at its stored tempo rather than the 120 default.
        reloadSpeedMasters()

        // Start the FX engine after fixtures are loaded.
        //
        // GlobalScope is accepted here for now, deliberately: the engine's two loops live as
        // long as the show does, [fxEngine.stop] cancels them by hand, and it catches per pass
        // so neither loop can die on its own. The cost of the shortcut is that a Show which is
        // dropped without close() leaks its loops, and there is no parent to cancel the tree in
        // one move — the same bargain the other long-lived subsystems here have struck
        // (ArtNetController's transmit loop, the MIDI registry). If any of them grows a real
        // lifecycle owner, this should move onto it too.
        fxEngine.start(GlobalScope)

        // Load user-created FX definitions from the database into the registry
        loadUserFxDefinitions()

        // Pre-compile scripts used by cue triggers so the first live activation of a cue doesn't
        // pay the Kotlin-compiler cold start. This runs off the server-accept path (server-first
        // boot) and, after the first boot, loads from the on-disk compiled-script cache, so it is
        // cheap enough to always run — there is no longer a toggle for it.
        Tracing.span("show.prewarm", "cue trigger scripts") { prewarmCueScripts() }

        // Mark the show usable last: fixtures are loaded and the FX engine is running. Readers of
        // [isStarted] (the readiness gate) now see a fully-initialised show.
        isStarted = true
    }

    /**
     * Bring back what [carry] says the replaced show was running — the second half of a same-project
     * reload (a sync pull, a venue Update, Detach, Extract, a venue's Discard; `ProjectManager`), after
     * [start] was given [ShowCarry.values]. The rig never sees zero for it: the controllers start on
     * the carried frame, frozen there while this rebuilds, then every channel the rebuild moved
     * crossfades to its new value over [RESUME_FADE_MS].
     *
     * Rebuilt, in order: the effects the operator ran by hand (a cue's or a programmer layer's come
     * back with their owner; one on a target the reload removed is dropped); each stack's live entry,
     * GO'd again without its events (a GO into a cue, not this) — its auto-advance timer starting
     * again from now — and each armed standby re-armed; each loose hold taken again in press order,
     * its events unfired; the programmer's layers and values, from the
     * store [ShowCarry] handed over. Then every patched property is published from what now composes
     * it — so a property nothing drives any more (its cue changed, its stack's cue is gone) releases
     * to its base, as it would have had the change been made live — and every channel no fixture
     * covers, and no programmer channel holds, goes to zero.
     */
    fun resume(carry: ShowCarry) {
        val logger = org.slf4j.LoggerFactory.getLogger("Show")
        // Each step on its own: one that fails is logged and the rest still run, so a property
        // nothing drives is still released and a loose channel still zeroed. Whatever was rebuilt
        // then crossfades in, and the freeze is always lifted.
        fun step(what: String, block: () -> Unit) {
            runCatching(block).onFailure { logger.error("Reload could not {}", what, it) }
        }
        try {
            step("bring back the hand-run effects") {
                val effects = carry.effects.filter { targetExists(it.target) }
                if (effects.size < carry.effects.size) {
                    logger.info("Reload dropped {} effect(s) whose target is no longer patched", carry.effects.size - effects.size)
                }
                if (effects.isNotEmpty()) fxEngine.addEffects(effects)
            }

            for ((stackId, entryId) in carry.liveEntries) {
                runCatching { cueStackManager.activateEntry(state, stackId, entryId, fireEvents = false) }
                    .onFailure { logger.warn("Reload could not bring back entry {} on stack {}: {}", entryId, stackId, it.message) }
            }
            // After the GOs, which consume a stack's standby. An entry the reload removed is refused.
            for ((stackId, entryId) in carry.standbys) {
                runCatching { cueStackManager.runState.setStandby(state, stackId, entryId) }
                    .onFailure { logger.info("Reload could not re-arm entry {} on stack {}: {}", entryId, stackId, it.message) }
            }
            // The loose holds, after the stacks and in press order, so the newest is on top again.
            for (cueId in carry.looseCues) {
                runCatching { cueStackManager.holds.takeLooseCue(state, cueId, fireEvents = false) }
                    .onFailure { logger.warn("Reload could not bring back loose cue {}: {}", cueId, it.message) }
            }

            step("re-cook the programmer's layers") { programmerLayerStack.reinstate() }
            step("republish the programmer") { fxEngine.programmer.republishAll() }

            // Every patched property, from what composes it now. Effect-covered and parked keys are
            // skipped by the publish itself — an effect repaints its own on the next tick.
            step("republish the rig") {
                val keys = PropertyChannelWriter.channelKeyIndex(fixtures).propertyKeys.values.flatten().toSet()
                fxEngine.programmer.republishKeys(keys)
            }

            step("release the channels no fixture covers") {
                val mapped = fixtures.channelMappingSnapshot().mappings
                val sideband = programmerStore.channelEntries().mapTo(HashSet()) { it.universe to it.channel }
                for (controller in fixtures.controllers) {
                    val covered = mapped[controller.universe.universe].orEmpty()
                    val loose = (1..512).filter { channel ->
                        channel !in covered && (controller.universe.universe to channel) !in sideband
                    }
                    if (loose.isNotEmpty()) controller.restoreState(loose.associateWith { 0u.toUByte() })
                }
            }
        } finally {
            crossfadeFromCarried(carry.values)
        }
    }

    /**
     * Lift the freeze [start] put on the carried frame, ramping every channel whose buffer the rebuild
     * moved from its carried value to its new one. The buffer is put back to the carried value first,
     * so the ramp starts where the rig is.
     */
    private fun crossfadeFromCarried(carried: Map<Universe, Map<Int, UByte>>) {
        for (controller in fixtures.controllers) {
            val from = carried[controller.universe] ?: continue
            val to = controller.currentValues
            val moved = (1..512).mapNotNull { channel ->
                val target = to[channel] ?: 0u
                val start = from[channel] ?: 0u
                if (target != start) Triple(channel, start, target) else null
            }
            if (moved.isNotEmpty()) {
                controller.restoreState(moved.associate { (channel, start, _) -> channel to start })
                controller.setValues(moved.map { (channel, _, target) -> channel to ChannelChange(target, RESUME_FADE_MS) })
            }
            controller.freeze(null)
        }
    }

    private fun targetExists(target: FxTarget): Boolean {
        fun exists(ref: TargetRef) = runCatching {
            when (ref) {
                is TargetRef.Group -> fixtures.untypedGroup(ref.key)
                is TargetRef.Fixture -> fixtures.untypedGroupableFixture(ref.key)
            }
        }.isSuccess
        // A selection stays while anything it names is still patched; the expansion skips the rest.
        (target.targetRef as? FxTargetRef.SelectionRef)?.let { return it.items.any(::exists) }
        return runCatching {
            if (target.isGroupTarget) fixtures.untypedGroup(target.targetKey) else fixtures.untypedGroupableFixture(target.targetKey)
        }.isSuccess
    }

    fun close() {
        globalScalerState.detach()
        // A hold in flight ends with the show: its caller hears it was interrupted.
        commandOutput.close()
        // Detach the sync hook first so nothing new lands mid-teardown, then flush what's
        // pending — a tempo tapped in the last debounce window must not be lost to a
        // project switch, and the hook (unlike a flow collector) guarantees every change
        // is already in the pending map by the time this runs.
        speedMasterBank.onChangeSync = null
        speedMasterFlushJob?.cancel()
        speedMasterFlushJob = null
        flushSpeedMasterWrites()
        // The holds go with the show, and the shared `cueHolds.state` frame with them, unless the
        // next show has already written its own (`CueHolds.close`).
        cueStackManager.holds.close()
        fxEngine.stop()
        // Stop transmitting last, after the FX engine has stopped writing. `State.shutdown()`
        // calls this directly rather than going through `Fixtures.register(removeUnused)`,
        // so without this the transmit threads and their sockets outlive the show.
        fixtures.closeControllers()
        // After the controllers, so nothing is still dispatching onto it. See its declaration.
        runCatching { runnerPool.close() }
    }

    private val pendingSpeedMasterWrites = java.util.concurrent.ConcurrentHashMap<java.util.UUID, SpeedMasterBank.Change>()
    private val speedMasterFlushSignal = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    private var speedMasterFlushJob: Job? = null

    private fun flushSpeedMasterWrites() {
        if (pendingSpeedMasterWrites.isEmpty()) return
        val writes = pendingSpeedMasterWrites.keys.toList()
            .mapNotNull { uuid -> pendingSpeedMasterWrites.remove(uuid) }
        if (writes.isEmpty()) return
        try {
            transaction(state.database) {
                for (change in writes) {
                    val uuid = change.uuid ?: continue
                    val master = DaoSpeedMaster
                        .find { (DaoSpeedMasters.project eq project.id) and (DaoSpeedMasters.uuid eq uuid) }
                        .firstOrNull() ?: continue
                    master.bpm = change.bpm
                    master.source = change.source.name
                }
            }
        } catch (e: Exception) {
            System.err.println("Failed to persist speed-master tempo change: ${e.message}")
        }
    }

    /**
     * (Re-)read this show's speed masters from the DB — at start, and after any CRUD write.
     * Surviving clocks keep their live tempo and tick counter (see [SpeedMasterBank.load]);
     * a typed-BPM retune is applied separately via [setSpeedMasterBpmIfCurrent].
     */
    fun reloadSpeedMasters() {
        val snapshots = transaction(state.database) {
            ensureDefaultSpeedMasters(project).map {
                SpeedMasterSnapshot(
                    uuid = it.uuid,
                    index = it.masterIndex,
                    name = it.name,
                    bpm = it.bpm,
                    source = it.sourceEnum,
                    usage = it.usageCategory,
                    followNum = it.followRatio?.first,
                    followDen = it.followRatio?.second,
                    followTargetUuid = it.followTarget,
                )
            }
        }
        speedMasterBank.load(snapshots)
    }

    /**
     * Retune a live clock after a REST bpm write, but only when the write was for *this*
     * show's project — a bpm typed into a non-current project must land in its row only.
     */
    fun setSpeedMasterBpmIfCurrent(projectId: Int, masterUuid: java.util.UUID, bpm: Double) {
        if (projectId != project.id.value) return
        // Outcome deliberately dropped: the REST route refuses a bpm on a follower before it
        // gets here, and an unknown uuid means the master was deleted between the row write
        // and this retune — the stored default still landed, which is all that write meant.
        speedMasterBank.setBpm(masterUuid, bpm, SpeedMasterSource.MANUAL)
    }

    /**
     * Load user-created FX definitions from the database and register them in the FxRegistry.
     * This restores user effects that were created via the API across application restarts.
     */
    private fun loadUserFxDefinitions() {
        try {
            val definitions = transaction(state.database) {
                DaoFxDefinition.find { DaoFxDefinitions.project eq project.id }.toList()
            }

            if (definitions.isEmpty()) return

            var loaded = 0
            val elapsed = measureTime {
                for (definition in definitions) {
                    val result = registerUserEffect(state, definition)
                    if (result.success) {
                        loaded++
                    } else {
                        val effectId = transaction(state.database) { definition.effectId }
                        System.err.println("Failed to load user FX definition '$effectId':")
                        result.diagnostics.forEach { d ->
                            System.err.println("  ${d.severity}: ${d.message} ${d.location ?: ""}")
                        }
                    }
                }
            }
            println("Loaded $loaded/${definitions.size} user FX definition(s) in $elapsed")
        } catch (e: Exception) {
            System.err.println("Failed to load user FX definitions: ${e.message}")
        }
    }

    /**
     * Pre-compile all FX_APPLICATION scripts referenced by cue triggers in this project.
     * This avoids the Kotlin compiler cold-start when a cue is first activated.
     */
    private fun prewarmCueScripts() {
        try {
            val scriptBodies = transaction(state.database) {
                project.cues.flatMap { cue ->
                    cue.triggers.map { trigger ->
                        val script = trigger.script
                        Pair("cue-trigger-${cue.id.value}", script.script)
                    }
                }.distinctBy { it.second } // deduplicate by script body
            }

            if (scriptBodies.isNotEmpty()) {
                val elapsed = measureTime {
                    for ((name, body) in scriptBodies) {
                        script(name, body, ScriptType.FX_APPLICATION)
                    }
                }
                println("Pre-warmed ${scriptBodies.size} cue trigger script(s) in $elapsed")
            }
        } catch (e: Exception) {
            System.err.println("Failed to pre-warm cue scripts: ${e.message}")
        }
    }

    fun script(scriptName: String, literalScript: String, scriptType: ScriptType = ScriptType.GENERAL): Script {
        val scriptKey = "$scriptName-$scriptType-${literalScript.cacheKey()}"

        return scriptsLock.run {
            scripts.getOrPut(scriptKey) {
                Tracing.transaction("script.compile", "script.compile") { span ->
                    span?.setData("script.type", scriptType.name)
                    ScriptCacheProbe.take()
                    Script(this@Show, scriptName, literalScript, scriptType).also {
                        span?.setData("cache", ScriptCacheProbe.tag(ScriptCacheProbe.take()))
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun String.cacheKey(): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(this.toByteArray()).toHexString()
    }

    fun scriptExists(scriptName: String): Boolean {
        return transaction(state.database) {
            DaoScript.find {
                (DaoScripts.name eq scriptName) and
                (DaoScripts.project eq project.id)
            }.firstOrNull() != null
        }
    }

    fun evalScriptByName(scriptName: String, step: Int = 0): ScriptResult? {
        val scriptData = transaction(state.database) {
            DaoScript.find {
                (DaoScripts.name eq scriptName) and
                (DaoScripts.project eq project.id)
            }.firstOrNull()
        } ?: return null

        val script = script(scriptName, scriptData.script, scriptData.scriptType)

        val scriptRunner = ScriptRunner(
            this,
            script,
            step = step,
            scriptId = scriptData.id.value,
        )

        return scriptRunner.result()
    }

    fun compileLiteralScript(literalScript: String, scriptType: ScriptType = ScriptType.GENERAL): ScriptResult {
        return script("", literalScript, scriptType).compileStatus
    }

    fun runLiteralScript(literalScript: String, scriptName: String = "", step: Int = 0, scriptType: ScriptType = ScriptType.GENERAL, scriptId: Int? = null): ScriptResult {
        val script = script(scriptName, literalScript, scriptType)
        val scriptRunner = ScriptRunner(this, script, step = step, scriptId = scriptId)

        return scriptRunner.result()
    }

    class Script(
        val show: Show,
        val scriptName: String,
        val literalScript: String,
        val scriptType: ScriptType = ScriptType.GENERAL,
    ) {
        val compiledResult: ResultWithDiagnostics<CompiledScript>
        val compileStatus: ScriptResult

        /** Lines the wrapper prepended; diagnostics are shifted back by this. */
        val lineOffset: Int

        init {
            // Wrapping and the template `when` both live in ScriptSourceWrapper now, so the
            // editor's /highlight route and this compile path cannot drift apart — and the
            // offset it reports is what finally maps diagnostics back to the user's own lines.
            val wrapped = ScriptSourceWrapper.wrap(literalScript, scriptType)
            val compilationConfiguration = ScriptSourceWrapper.compilationConfiguration(scriptType)

            val (compiledResult, compileStatus) = runBlocking {
                val compiledResult = show.scriptingHost.compiler(wrapped.text.toScriptSource(), compilationConfiguration)
                val compileStatus = ScriptResult(compiledResult, lineOffset = wrapped.lineOffset)
                Pair(compiledResult, compileStatus)
            }

            this.compiledResult = compiledResult
            this.compileStatus = compileStatus
            this.lineOffset = wrapped.lineOffset
        }
    }

    class ScriptRunner(
        val show: Show,
        script: Script,
        step: Int = 0,
        scriptId: Int? = null,
    ) {
        var result: ScriptResult? = null
        val job: Job

        init {
            val compiledResult = script.compiledResult
            val compiledScript = compiledResult.valueOrThrow()

            job = CoroutineScope(show.runnerPool + LiveSentryContext()).launch {
                when (script.scriptType) {
                    ScriptType.GENERAL -> {
                        val transaction = ControllerTransaction(show.fixtures.controllers)
                        val fixturesWithTransaction = show.fixtures.withTransaction(transaction)

                        val runResult = show.scriptingHost.evaluator(compiledScript, ScriptEvaluationConfiguration {
                            providedProperties(Pair("show", show))
                            providedProperties(Pair("fixtures", fixturesWithTransaction))
                            providedProperties(Pair("fxEngine", show.fxEngine))
                            providedProperties(Pair("scriptName", script.scriptName))
                            providedProperties(Pair("step", step))
                            providedProperties(Pair("coroutineScope", this@launch))
                        })

                        val actualChannelChanges = transaction.apply()

                        val channelChanges = if (fixturesWithTransaction.customChangedChannels != null) {
                            fixturesWithTransaction.customChangedChannels
                        } else {
                            actualChannelChanges
                        }

                        result = ScriptResult(compiledResult, runResult, channelChanges, script.lineOffset)
                    }

                    ScriptType.FX_DEFINITION -> {
                        val runResult = show.scriptingHost.evaluator(compiledScript, ScriptEvaluationConfiguration {
                            providedProperties(Pair("show", show))
                            providedProperties(Pair("scriptName", script.scriptName))
                            providedProperties(Pair("scriptId", scriptId))
                        })

                        result = ScriptResult(compiledResult, runResult, null, script.lineOffset)
                    }

                    ScriptType.FX_APPLICATION -> {
                        val runResult = show.scriptingHost.evaluator(compiledScript, ScriptEvaluationConfiguration {
                            providedProperties(Pair("show", show))
                            providedProperties(Pair("fxEngine", show.fxEngine))
                            providedProperties(Pair("scriptName", script.scriptName))
                            providedProperties(Pair("step", step))
                        })

                        result = ScriptResult(compiledResult, runResult, null, script.lineOffset)
                    }

                    ScriptType.FX_CALC -> {
                        val runResult = show.scriptingHost.evaluator(compiledScript, ScriptEvaluationConfiguration {
                            providedProperties(Pair("phase", 0.5))
                            providedProperties(Pair("context", uk.me.cormack.lighting7.fx.EffectContext.SINGLE))
                            providedProperties(Pair("params", uk.me.cormack.lighting7.fx.TypedParams(emptyMap(), emptyList())))
                        })
                        result = ScriptResult(compiledResult, runResult, null, script.lineOffset)
                    }

                    ScriptType.FX_CALC_STATEFUL -> {
                        val runResult = show.scriptingHost.evaluator(compiledScript, ScriptEvaluationConfiguration {
                            providedProperties(Pair("tick", uk.me.cormack.lighting7.fx.MasterClock.ClockTick(0L, 0L, 0, 0.0, 0L)))
                            providedProperties(Pair("deltaMs", 0L))
                            providedProperties(Pair("context", uk.me.cormack.lighting7.fx.EffectContext.SINGLE))
                            providedProperties(Pair("params", uk.me.cormack.lighting7.fx.TypedParams(emptyMap(), emptyList())))
                            providedProperties(Pair("state", mutableMapOf<String, Any>()))
                        })
                        result = ScriptResult(compiledResult, runResult, null, script.lineOffset)
                    }

                    ScriptType.FX_CALC_COMPOSITE -> {
                        val runResult = show.scriptingHost.evaluator(compiledScript, ScriptEvaluationConfiguration {
                            providedProperties(Pair("phase", 0.5))
                            providedProperties(Pair("context", uk.me.cormack.lighting7.fx.EffectContext.SINGLE))
                            providedProperties(Pair("params", uk.me.cormack.lighting7.fx.TypedParams(emptyMap(), emptyList())))
                        })
                        result = ScriptResult(compiledResult, runResult, null, script.lineOffset)
                    }
                }
            }
        }

        fun stop() {
            if (job.isActive) {
                job.cancel()
            }
        }

        fun result(): ScriptResult {
            runBlocking {
                job.join()
            }

            return checkNotNull(result)
        }
    }
}


data class ScriptResult(
    val compileResult: ResultWithDiagnostics<CompiledScript>,
    val runResult: ResultWithDiagnostics<EvaluationResult>? = null,
    val channelChanges: Map<Universe, Map<Int, UByte>>? = null,
    /**
     * Lines [ScriptSourceWrapper] prepended before compiling. Diagnostics arrive against the
     * wrapped text, so anything rendering them to a user must subtract this first.
     */
    val lineOffset: Int = 0,
)
