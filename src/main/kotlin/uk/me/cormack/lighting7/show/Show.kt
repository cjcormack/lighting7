package uk.me.cormack.lighting7.show

import kotlinx.coroutines.*
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.core.eq
import uk.me.cormack.lighting7.dmx.ChannelChange
import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.dmx.ParkManager
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fx.*
import uk.me.cormack.lighting7.midi.GlobalScalerState
import uk.me.cormack.lighting7.models.*
import uk.me.cormack.lighting7.routes.registerUserEffect
import uk.me.cormack.lighting7.scripts.*
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.state.optionalBoolean
import uk.me.cormack.lighting7.state.optionalString
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

@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
class Show(
    val state: State,
    val project: DaoProject,
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
    val programmerStore = ProgrammerStore()

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
    val locateManager = LocateManager()
    val cueStackManager = CueStackManager(fxEngine)

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

    fun start() {
        try {
            // Park state must be loaded before controllers are constructed: controllers read
            // it via [ParkSource] on their first transmit.
            parkManager.loadFromDatabase()
            DbFixtureLoader.loadFixtures(project.id.value, fixtures, state.database, parkSource = parkManager)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Attach the global scaler to every controller so Blackout / Grand Master toggles
        // from a control surface propagate at transmit time.
        globalScalerState.attach()

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

        // Write live tempo changes back to their rows, so the stored bpm is "wherever the
        // tempo was last set" and the next boot/import starts there. Trailing-debounced:
        // taps arrive ~2/s and recompute BPM each time, so per-tap writes would be churn.
        // The capture is the bank's SYNCHRONOUS hook, not a changes-flow collector — a
        // collector can be cancelled at close() with an emission still buffered, losing the
        // very last tap; the hook lands in the pending map before setBpm/tap even returns,
        // so the final flush in [close] can't miss it.
        speedMasterBank.onChangeSync = { change ->
            change.uuid?.let { uuid ->
                pendingSpeedMasterWrites[uuid] = change
                speedMasterFlushSignal.trySend(Unit)
            }
        }
        speedMasterFlushJob = GlobalScope.launch {
            for (signal in speedMasterFlushSignal) {
                delay(SPEED_MASTER_WRITE_DEBOUNCE_MS)
                flushSpeedMasterWrites()
            }
        }

        // Load user-created FX definitions from the database into the registry
        loadUserFxDefinitions()

        // Pre-compile scripts used by cue triggers so the first live activation of a cue doesn't
        // pay the Kotlin-compiler cold start. This runs off the server-accept path (server-first
        // boot) and, after the first boot, loads from the on-disk compiled-script cache, so it is
        // cheap enough to always run — there is no longer a toggle for it.
        prewarmCueScripts()

        // Mark the show usable last: fixtures are loaded and the FX engine is running. Readers of
        // [isStarted] (the readiness gate) now see a fully-initialised show.
        isStarted = true
    }

    fun close() {
        globalScalerState.detach()
        // Detach the sync hook first so nothing new lands mid-teardown, then flush what's
        // pending — a tempo tapped in the last debounce window must not be lost to a
        // project switch, and the hook (unlike a flow collector) guarantees every change
        // is already in the pending map by the time this runs.
        speedMasterBank.onChangeSync = null
        speedMasterFlushJob?.cancel()
        speedMasterFlushJob = null
        flushSpeedMasterWrites()
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
                Script(this@Show, scriptName, literalScript, scriptType)
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

            job = CoroutineScope(show.runnerPool).launch {
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
