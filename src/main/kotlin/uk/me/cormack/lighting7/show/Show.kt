package uk.me.cormack.lighting7.show

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ticker
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.selects.select
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fx.FxEngine
import uk.me.cormack.lighting7.fx.MasterClock
import uk.me.cormack.lighting7.grpc.*
import uk.me.cormack.lighting7.models.*
import uk.me.cormack.lighting7.scriptSettings.IntValue
import uk.me.cormack.lighting7.scriptSettings.ScriptSetting
import uk.me.cormack.lighting7.scriptSettings.ScriptSettingValue
import uk.me.cormack.lighting7.scripts.LightingScript
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
    val fxEngine = FxEngine(fixtures, MasterClock())
    private val scripts: MutableMap<String, Script> = mutableMapOf()
    private val scriptsLock = ReentrantLock()

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
            evalScriptByName(loadFixturesScriptName)
            if (initialSceneName != null) {
                runScene(initialSceneName)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Start the FX engine after fixtures are loaded
        fxEngine.start(GlobalScope)

        if (runLoopScriptName != null) {
            GlobalScope.launch {
                runShow(runLoopScriptName, runLoopDelay)
            }
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

    fun script(scriptName: String, literalScript: String, settings: List<ScriptSetting<*>>): Script {
        val scriptKey = "$scriptName-${literalScript.cacheKey()}"

        return scriptsLock.run {
            scripts.getOrPut(scriptKey) {
                Script(this@Show, scriptName, literalScript, settings)
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
                scene.settingsValues.orEmpty()
            )

            Pair(scene, scriptData)
        }

        val (_, scriptName, scriptBody, scriptSettings, sceneSettingsValues) = scriptData

        val script = script(scriptName, scriptBody, scriptSettings)
        val scriptRunner = ScriptRunner(
            this,
            script,
            scene,
            sceneIsActive = fixtures.isSceneActive(id),
            settingsValues = sceneSettingsValues,
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

    fun evalScriptByName(scriptName: String, step: Int = 0): ScriptResult {
        val scriptData = transaction(state.database) {
            DaoScript.find {
                (DaoScripts.name eq scriptName) and
                (DaoScripts.project eq project.id)
            }.first()
        }

        val script = script(scriptName, scriptData.script, scriptData.settings?.list.orEmpty())

        val scriptRunner = ScriptRunner(
            this,
            script,
            step = step,
        )

        return scriptRunner.result()
    }

    fun compileLiteralScript(literalScript: String, scriptSettings: List<ScriptSetting<*>>): ScriptResult {
        return script("", literalScript, scriptSettings).compileStatus
    }

    fun runLiteralScript(literalScript: String, scriptSettings: List<ScriptSetting<*>>, scriptName: String = "", step: Int = 0): ScriptResult {
        val script = script(scriptName, literalScript, scriptSettings)
        val scriptRunner = ScriptRunner(this, script, step = step)

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
    ) {
        val compiledResult: ResultWithDiagnostics<CompiledScript>
        val compileStatus: ScriptResult

        init {
            val expandedScript = """
                |runBlocking {
                |${literalScript}
                |}
            """.trimMargin("|")

            val compilationConfiguration = createJvmCompilationConfigurationFromTemplate<LightingScript>()

            val (compiledResult, compileStatus) = runBlocking {
                val compiledResult = BasicJvmScriptingHost().compiler(expandedScript.toScriptSource(), compilationConfiguration)
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
        settingsValues: Map<String, ScriptSettingValue> = emptyMap()
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

            val transaction = ControllerTransaction(show.fixtures.controllers)
            val fixturesWithTransaction = show.fixtures.withTransaction(transaction)

            val settings = script.settings.associate {
                (it.defaultValue as IntValue).int.toUInt()
                it.name to (settingsValues[it.name] ?: it.defaultValue)
            }

            if (scene != null && scene.mode == Mode.CHASE) {
                show.fixtures.recordChaseStart(scene.id.value)
            }

            val currentTrack = show.currentTrackLock.read {
                show.currentTrack
            }

            job = CoroutineScope(show.runnerPool).launch {
                val runResult = BasicJvmScriptingHost().evaluator(compiledScript, ScriptEvaluationConfiguration {
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
