package uk.me.cormack.lighting7.show

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ticker
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.selects.select
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.dmx.ParkManager
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fx.*
import uk.me.cormack.lighting7.grpc.*
import uk.me.cormack.lighting7.models.*
import uk.me.cormack.lighting7.scriptSettings.IntValue
import uk.me.cormack.lighting7.scriptSettings.ScriptSetting
import uk.me.cormack.lighting7.scriptSettings.ScriptSettingValue
import uk.me.cormack.lighting7.routes.registerUserEffect
import uk.me.cormack.lighting7.scripts.*
import uk.me.cormack.lighting7.state.State
import java.awt.Color
import java.security.MessageDigest
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.script.experimental.api.*
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost
import kotlin.script.experimental.jvmhost.createJvmCompilationConfigurationFromTemplate
import kotlin.time.measureTime

@OptIn(DelicateCoroutinesApi::class, ObsoleteCoroutinesApi::class, ExperimentalCoroutinesApi::class)
class Show(
    val state: State,
    val project: DaoProject,
    val loadFixturesScriptName: String,
    val initialSceneName: String?,
    val runLoopScriptName: String?,
    val trackChangedScriptName: String?,
    val runLoopDelay: Long,
) {
    val fixtures = Fixtures()
    val fxScriptCompiler = FxScriptCompiler()
    val fxRegistry = FxRegistry().apply {
        // Load built-in effects from .fx.kts resource files
        val fileLoader = FxFileLoader(fxScriptCompiler)
        fileLoader.loadBuiltInEffects(this)
    }
    val fxEngine = FxEngine(fixtures, MasterClock())
    val cueStackManager = CueStackManager(fxEngine)
    val parkManager = ParkManager(state.database, project.id.value)
    private val scripts: MutableMap<String, Script> = mutableMapOf()
    private val scriptsLock = ReentrantLock()
    private val scriptingHost = BasicJvmScriptingHost()

    private val _trackStateFlow = MutableSharedFlow<TrackState>()
    val trackStateFlow = _trackStateFlow.asSharedFlow()
    private val runnerPool = newFixedThreadPoolContext(1, "lighting-running-pool")
    private val compilerPool = newFixedThreadPoolContext(1, "lighting-compiler-pool")

    private val runningScenes: MutableMap<Int, ScriptRunner> = mutableMapOf()
    private val runningScenesLock = ReentrantReadWriteLock()

    private var currentTrack: TrackDetails? = null
    private val currentTrackLock = ReentrantReadWriteLock()

    fun start() {
        try {
            when (project.mode) {
                ProjectMode.SCRIPT_BASED -> evalScriptByName(loadFixturesScriptName)
                ProjectMode.DB_BASED -> DbFixtureLoader.loadFixtures(project.id.value, fixtures, state.database)
            }

            // Load and apply parked channels after fixtures/controllers are registered
            parkManager.loadFromDatabase()
            parkManager.applyToControllers(fixtures.controllers)

            if (initialSceneName != null) {
                runScene(initialSceneName)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Start the FX engine after fixtures are loaded
        fxEngine.start(GlobalScope)

        // Load user-created FX definitions from the database into the registry
        loadUserFxDefinitions()

        // Pre-compile scripts used by cue triggers to avoid cold-start latency
        prewarmCueScripts()

        if (runLoopScriptName != null && scriptExists(runLoopScriptName)) {
            GlobalScope.launch {
                runShow(runLoopScriptName, runLoopDelay)
            }
        } else if (runLoopScriptName != null) {
            println("Run-loop script '$runLoopScriptName' not found, skipping run loop")
        }

        val pingTicker = ticker(5_000)
        GlobalScope.launch {
            launch(newSingleThreadContext("TrackServerPing")) {
                while(coroutineContext.isActive) {
                    select<Unit> {
                        pingTicker.onReceiveCatching {
                            if (it.isClosed) {
                                return@onReceiveCatching
                            }

                            _trackStateFlow.emit(trackState {
                                playerState = PlayerState.PING
                            })
                        }
                    }
                }
            }
        }
    }

    /**
     * Stop all running scenes.
     */
    fun stopAllScenes() {
        runningScenesLock.write {
            runningScenes.values.forEach { runner ->
                runner.stop()
            }
            runningScenes.clear()
        }
    }

    fun close() {
        stopAllScenes()
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
                        script(name, body, emptyList(), ScriptType.FX_APPLICATION)
                    }
                }
                println("Pre-warmed ${scriptBodies.size} cue trigger script(s) in $elapsed")
            }
        } catch (e: Exception) {
            System.err.println("Failed to pre-warm cue scripts: ${e.message}")
        }
    }

    private fun CoroutineScope.runShow(runLoopScriptName: String, delay: Long) {
        var isClosed = false

        val ticker = ticker(delay)

        var consecutiveErrors = 0

        var step = 0

        launch(newSingleThreadContext("LightingShow")) {
            while(coroutineContext.isActive && !isClosed) {
                try {
                    select<Unit> {
                        ticker.onReceiveCatching {
                            if (it.isClosed) {
                                return@onReceiveCatching
                            }

                            evalScriptByName(runLoopScriptName, step)
                            step++
                        }
                    }
                    consecutiveErrors = 0
                } catch (e: Exception) {
                    if (consecutiveErrors == 0) {
                        e.printStackTrace()
                    }
                    consecutiveErrors++

//                    if (consecutiveErrors > 20) {
//                        // if too many errors, we'll bail out and let this thing stop. A restart of NK is needed.
//                        println("Too many consecutive errors")
//                        throw e
//                    }
                    delay(10_000)
                }
            }
        }
    }

    fun script(scriptName: String, literalScript: String, settings: List<ScriptSetting<*>>, scriptType: ScriptType = ScriptType.GENERAL): Script {
        val scriptKey = "$scriptName-$scriptType-${literalScript.cacheKey()}"

        return scriptsLock.run {
            scripts.getOrPut(scriptKey) {
                Script(this@Show, scriptName, literalScript, settings, scriptType)
            }
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun String.cacheKey(): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(this.toByteArray()).toHexString()
    }

    private data class ScriptData(
        val sceneName: String,
        val scriptName: String,
        val scriptBody: String,
        val scriptSettings: List<ScriptSetting<*>>,
        val sceneSettingsValues: Map<String, ScriptSettingValue>,
        val scriptType: ScriptType = ScriptType.GENERAL,
        val scriptId: Int? = null,
    )

    fun runScene(id: Int): ScriptResult {
        val sceneRunner = startScene(id)
        return sceneRunner.result()
    }

    fun runScene(sceneName: String): ScriptResult {
        val sceneId = transaction(state.database) {
            val scene = DaoScene.find {
                (DaoScenes.name eq sceneName) and
                    (DaoScenes.project eq project.id)
            }.first()

            scene.id.value
        }
        return runScene(sceneId)
    }

    fun startScene(id: Int): ScriptRunner {
        val (scene, scriptData) = transaction(state.database) {
            val scene = DaoScene.findById(id) ?: throw Error("Scene not found")

            val scriptData = ScriptData(
                scene.name,
                scene.script.name,
                scene.script.script,
                scene.script.settings?.list.orEmpty(),
                scene.settingsValues.orEmpty(),
                scene.script.scriptType,
                scene.script.id.value,
            )

            Pair(scene, scriptData)
        }

        val (_, scriptName, scriptBody, scriptSettings, sceneSettingsValues, scriptType, dbScriptId) = scriptData

        val script = script(scriptName, scriptBody, scriptSettings, scriptType)
        val scriptRunner = ScriptRunner(
            this,
            script,
            scene,
            sceneIsActive = fixtures.isSceneActive(id),
            settingsValues = sceneSettingsValues,
            scriptId = dbScriptId,
        )

        return scriptRunner
    }

    fun startScene(sceneName: String): ScriptRunner {
        val sceneId = transaction(state.database) {
            val scene = DaoScene.find {
                (DaoScenes.name eq sceneName) and
                    (DaoScenes.project eq project.id)
            }.first()

            scene.id.value
        }
        return startScene(sceneId)
    }

    fun stopScene(id: Int) {
        runningScenesLock.read {
            val scriptRunner = runningScenes[id] ?: return@read
            scriptRunner.stop()
        }
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

        val script = script(scriptName, scriptData.script, scriptData.settings?.list.orEmpty(), scriptData.scriptType)

        val scriptRunner = ScriptRunner(
            this,
            script,
            step = step,
            scriptId = scriptData.id.value,
        )

        return scriptRunner.result()
    }

    fun compileLiteralScript(literalScript: String, scriptSettings: List<ScriptSetting<*>>, scriptType: ScriptType = ScriptType.GENERAL): ScriptResult {
        return script("", literalScript, scriptSettings, scriptType).compileStatus
    }

    fun runLiteralScript(literalScript: String, scriptSettings: List<ScriptSetting<*>>, scriptName: String = "", step: Int = 0, scriptType: ScriptType = ScriptType.GENERAL, scriptId: Int? = null): ScriptResult {
        val script = script(scriptName, literalScript, scriptSettings, scriptType)
        val scriptRunner = ScriptRunner(this, script, step = step, scriptId = scriptId)

        return scriptRunner.result()
    }

    fun trackChanged(newTrackDetails: TrackDetails) {
        val trackHasChanged = currentTrackLock.write {
            val hasChanged = currentTrack?.artist != newTrackDetails.artist || currentTrack?.title != newTrackDetails.title

            currentTrack = newTrackDetails

            hasChanged
        }
        if (trackHasChanged && trackChangedScriptName?.isNotEmpty() == true) {
            evalScriptByName(trackChangedScriptName)
        }

        fixtures.trackChanged(newTrackDetails.playerState == PlayerState.PLAYING, newTrackDetails.artist, newTrackDetails.title)
    }

    suspend fun requestCurrentTrackDetails() {
        _trackStateFlow.emit(trackState {
            playerState = PlayerState.HANDSHAKE
        })
    }

    class Script(
        val show: Show,
        val scriptName: String,
        val literalScript: String,
        val settings: List<ScriptSetting<*>>,
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
        val scene: DaoScene? = null,
        step: Int = 0,
        sceneIsActive: Boolean = false,
        settingsValues: Map<String, ScriptSettingValue> = emptyMap(),
        scriptId: Int? = null,
    ) {
        var result: ScriptResult? = null
        val job: Job

        init {
            if (scene != null) {
                show.runningScenesLock.read {
                    val previousRunner = show.runningScenes[scene.id.value] ?: return@read

                    if (previousRunner.job.isActive) {
                        previousRunner.job.cancel()
                    }
                }
            }

            val compiledResult = script.compiledResult
            val compiledScript = compiledResult.valueOrThrow()

            val settings = script.settings.associate {
                (it.defaultValue as IntValue).int.toUInt()
                it.name to (settingsValues[it.name] ?: it.defaultValue)
            }

            val currentTrack = show.currentTrackLock.read {
                show.currentTrack
            }

            job = CoroutineScope(show.runnerPool).launch {
                when (script.scriptType) {
                    ScriptType.GENERAL -> {
                        // Full-power: DMX transaction, all properties, scene recording
                        val transaction = ControllerTransaction(show.fixtures.controllers)
                        val fixturesWithTransaction = show.fixtures.withTransaction(transaction)

                        if (scene != null && scene.mode == Mode.CHASE) {
                            show.fixtures.recordChaseStart(scene.id.value)
                        }

                        val runResult = show.scriptingHost.evaluator(compiledScript, ScriptEvaluationConfiguration {
                            providedProperties(Pair("show", show))
                            providedProperties(Pair("fixtures", fixturesWithTransaction))
                            providedProperties(Pair("fxEngine", show.fxEngine))
                            providedProperties(Pair("scriptName", script.scriptName))
                            providedProperties(Pair("step", step))
                            providedProperties(Pair("sceneName", scene?.name ?: ""))
                            providedProperties(Pair("sceneIsActive", sceneIsActive))
                            providedProperties(Pair("settings", settings))
                            providedProperties(Pair("coroutineScope", this@launch))
                            providedProperties(Pair("currentTrack", currentTrack))
                        })

                        val actualChannelChanges = transaction.apply()

                        val channelChanges = if (fixturesWithTransaction.customChangedChannels != null) {
                            fixturesWithTransaction.customChangedChannels
                        } else {
                            actualChannelChanges
                        }

                        if (scene != null) {
                            when (scene.mode) {
                                Mode.SCENE -> {
                                    if (channelChanges != null) {
                                        show.fixtures.recordScene(scene.id.value, channelChanges)
                                    }
                                }
                                Mode.CHASE -> {
                                    show.fixtures.recordChaseStop(scene.id.value)
                                }
                            }
                        }

                        result = ScriptResult(compiledResult, runResult, channelChanges)
                    }

                    ScriptType.FX_DEFINITION -> {
                        // Minimal: just show, scriptName, settings, scriptId
                        val runResult = show.scriptingHost.evaluator(compiledScript, ScriptEvaluationConfiguration {
                            providedProperties(Pair("show", show))
                            providedProperties(Pair("scriptName", script.scriptName))
                            providedProperties(Pair("settings", settings))
                            providedProperties(Pair("scriptId", scriptId))
                        })

                        result = ScriptResult(compiledResult, runResult, null)
                    }

                    ScriptType.FX_APPLICATION -> {
                        // FX engine + fixtures, no DMX transaction
                        val runResult = show.scriptingHost.evaluator(compiledScript, ScriptEvaluationConfiguration {
                            providedProperties(Pair("show", show))
                            providedProperties(Pair("fxEngine", show.fxEngine))
                            providedProperties(Pair("scriptName", script.scriptName))
                            providedProperties(Pair("step", step))
                            providedProperties(Pair("settings", settings))
                            providedProperties(Pair("currentTrack", currentTrack))
                        })

                        result = ScriptResult(compiledResult, runResult, null)
                    }

                    ScriptType.FX_CALC -> {
                        // Evaluate with dummy values to test the lambda extraction
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

            if (scene != null) {
                show.runningScenesLock.write {
                    show.runningScenes[scene.id.value] = this
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
