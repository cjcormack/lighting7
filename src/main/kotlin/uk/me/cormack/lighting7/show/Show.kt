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
import kotlin.script.experimental.jvmhost.createJvmCompilationConfigurationFromTemplate
import kotlin.time.measureTime

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
    val layer3Resolver = Layer3Resolver()
    val layerResolver = LayerResolver(layer3Resolver, programmerStore)
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
        masterClock = MasterClock(),
        programmerStore = programmerStore,
        layerResolver = layerResolver,
        parkManager = parkManager,
    )
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

    private val runnerPool = newFixedThreadPoolContext(1, "lighting-running-pool")
    private val compilerPool = newFixedThreadPoolContext(1, "lighting-compiler-pool")

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

        // Start the FX engine after fixtures are loaded
        fxEngine.start(GlobalScope)

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
        fxEngine.stop()
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

        init {
            // GENERAL scripts get wrapped in runBlocking for coroutine support.
            // FX scripts run as-is — they configure effects, not orchestrate coroutines.
            val expandedScript = when (scriptType) {
                ScriptType.GENERAL -> """
                    |runBlocking {
                    |${literalScript}
                    |}
                """.trimMargin("|")
                ScriptType.FX_DEFINITION, ScriptType.FX_APPLICATION -> literalScript
                // FX_CALC types: wrap in lambda (same as FxScriptCompiler)
                ScriptType.FX_CALC -> """
                    |val calculateFn: (Double, uk.me.cormack.lighting7.fx.EffectContext, uk.me.cormack.lighting7.fx.TypedParams) -> uk.me.cormack.lighting7.fx.FxOutput = { phase, context, params ->
                    |${literalScript}
                    |}
                    |calculateFn
                """.trimMargin("|")
                ScriptType.FX_CALC_STATEFUL -> """
                    |val calculateFn: (uk.me.cormack.lighting7.fx.MasterClock.ClockTick, Long, uk.me.cormack.lighting7.fx.EffectContext, uk.me.cormack.lighting7.fx.TypedParams, MutableMap<String, Any>) -> uk.me.cormack.lighting7.fx.FxOutput = { tick, deltaMs, context, params, state ->
                    |${literalScript}
                    |}
                    |calculateFn
                """.trimMargin("|")
                ScriptType.FX_CALC_COMPOSITE -> """
                    |val calculateFn: (Double, uk.me.cormack.lighting7.fx.EffectContext, uk.me.cormack.lighting7.fx.TypedParams) -> Map<uk.me.cormack.lighting7.fx.FxOutputType, uk.me.cormack.lighting7.fx.FxOutput> = { phase, context, params ->
                    |${literalScript}
                    |}
                    |calculateFn
                """.trimMargin("|")
            }

            val compilationConfiguration = when (scriptType) {
                ScriptType.GENERAL -> createJvmCompilationConfigurationFromTemplate<LightingScript>()
                ScriptType.FX_DEFINITION -> createJvmCompilationConfigurationFromTemplate<FxDefinitionScript>()
                ScriptType.FX_APPLICATION -> createJvmCompilationConfigurationFromTemplate<FxApplicationScript>()
                ScriptType.FX_CALC -> createJvmCompilationConfigurationFromTemplate<FxCalcScript>()
                ScriptType.FX_CALC_STATEFUL -> createJvmCompilationConfigurationFromTemplate<FxStatefulCalcScript>()
                ScriptType.FX_CALC_COMPOSITE -> createJvmCompilationConfigurationFromTemplate<FxCompositeCalcScript>()
            }

            val (compiledResult, compileStatus) = runBlocking {
                val compiledResult = show.scriptingHost.compiler(expandedScript.toScriptSource(), compilationConfiguration)
                val compileStatus = ScriptResult(compiledResult)
                Pair(compiledResult, compileStatus)
            }

            this.compiledResult = compiledResult
            this.compileStatus = compileStatus
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

                        result = ScriptResult(compiledResult, runResult, channelChanges)
                    }

                    ScriptType.FX_DEFINITION -> {
                        val runResult = show.scriptingHost.evaluator(compiledScript, ScriptEvaluationConfiguration {
                            providedProperties(Pair("show", show))
                            providedProperties(Pair("scriptName", script.scriptName))
                            providedProperties(Pair("scriptId", scriptId))
                        })

                        result = ScriptResult(compiledResult, runResult, null)
                    }

                    ScriptType.FX_APPLICATION -> {
                        val runResult = show.scriptingHost.evaluator(compiledScript, ScriptEvaluationConfiguration {
                            providedProperties(Pair("show", show))
                            providedProperties(Pair("fxEngine", show.fxEngine))
                            providedProperties(Pair("scriptName", script.scriptName))
                            providedProperties(Pair("step", step))
                        })

                        result = ScriptResult(compiledResult, runResult, null)
                    }

                    ScriptType.FX_CALC -> {
                        val runResult = show.scriptingHost.evaluator(compiledScript, ScriptEvaluationConfiguration {
                            providedProperties(Pair("phase", 0.5))
                            providedProperties(Pair("context", uk.me.cormack.lighting7.fx.EffectContext.SINGLE))
                            providedProperties(Pair("params", uk.me.cormack.lighting7.fx.TypedParams(emptyMap(), emptyList())))
                        })
                        result = ScriptResult(compiledResult, runResult, null)
                    }

                    ScriptType.FX_CALC_STATEFUL -> {
                        val runResult = show.scriptingHost.evaluator(compiledScript, ScriptEvaluationConfiguration {
                            providedProperties(Pair("tick", uk.me.cormack.lighting7.fx.MasterClock.ClockTick(0L, 0L, 0, 0.0, 0L)))
                            providedProperties(Pair("deltaMs", 0L))
                            providedProperties(Pair("context", uk.me.cormack.lighting7.fx.EffectContext.SINGLE))
                            providedProperties(Pair("params", uk.me.cormack.lighting7.fx.TypedParams(emptyMap(), emptyList())))
                            providedProperties(Pair("state", mutableMapOf<String, Any>()))
                        })
                        result = ScriptResult(compiledResult, runResult, null)
                    }

                    ScriptType.FX_CALC_COMPOSITE -> {
                        val runResult = show.scriptingHost.evaluator(compiledScript, ScriptEvaluationConfiguration {
                            providedProperties(Pair("phase", 0.5))
                            providedProperties(Pair("context", uk.me.cormack.lighting7.fx.EffectContext.SINGLE))
                            providedProperties(Pair("params", uk.me.cormack.lighting7.fx.TypedParams(emptyMap(), emptyList())))
                        })
                        result = ScriptResult(compiledResult, runResult, null)
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
)
