package uk.me.cormack.lighting7.show

import com.github.pireba.applescript.AppleScript
import com.github.pireba.applescript.AppleScriptException
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ticker
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.selects.select
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.grpc.PlayerState
import uk.me.cormack.lighting7.grpc.TrackDetails
import uk.me.cormack.lighting7.grpc.TrackState
import uk.me.cormack.lighting7.grpc.trackState
import uk.me.cormack.lighting7.models.*
import uk.me.cormack.lighting7.scriptSettings.IntValue
import uk.me.cormack.lighting7.scriptSettings.ScriptSetting
import uk.me.cormack.lighting7.scriptSettings.ScriptSettingValue
import uk.me.cormack.lighting7.scripts.LightingScript
import uk.me.cormack.lighting7.state.State
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.script.experimental.api.*
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost
import kotlin.script.experimental.jvmhost.createJvmCompilationConfigurationFromTemplate
import kotlin.text.toByteArray

@OptIn(DelicateCoroutinesApi::class, ObsoleteCoroutinesApi::class, ExperimentalCoroutinesApi::class)
class Show(
    val state: State,
    val projectName: String,
    val loadFixturesScriptName: String,
    val initialSceneName: String,
    val runLoopScriptName: String?,
    val runLoopDelay: Long,
) {
    val fixtures = Fixtures()
    private val scripts = ConcurrentHashMap<String, Script>()
    private val _trackStateFlow = MutableSharedFlow<TrackState>()
    val trackStateFlow = _trackStateFlow.asSharedFlow()
    private val runnerPool = newFixedThreadPoolContext(16, "lighting-running-pool")
    private val compilerPool = newFixedThreadPoolContext(4, "lighting-compiler-pool")

    private val runningScenes: MutableMap<Int, Job> = mutableMapOf()
    private val runningScenesLock = ReentrantReadWriteLock()

    val project = transaction(state.database) {
        DaoProject.find {
            DaoProjects.name eq projectName
        }.first()
    }

    suspend fun start() {
        try {
            evalScriptByName(loadFixturesScriptName)
//            runScene(initialSceneName)
        } catch (e: Exception) {
            e.printStackTrace()
        }

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

        val slideCheckTicker = ticker(250)
        GlobalScope.launch {
            launch(newSingleThreadContext("SlideChecker")) {
                val command = arrayOf(
                    "tell application \"Keynote\"",
                    "tell document 1",
                    "    get current slide",
                    "end tell",
                    "end tell",
                )
                val script = AppleScript(*command)

                var currentSlide: String? = null
                var currentDocument: String? = null
                var currentPlayerState: PlayerState = PlayerState.PAUSED

                val regex = "(slide \\d+) of document id \"([A-Z0-9\\-]+)\" of application \"Keynote\"".toPattern()

                while(coroutineContext.isActive) {
                    select<Unit> {
                        slideCheckTicker.onReceiveCatching {
                            if (it.isClosed) {
                                return@onReceiveCatching
                            }

                            var newSlide: String? = null
                            var newDocument: String? = null
                            var newPlayerState: PlayerState = PlayerState.PAUSED

                            try {
                                val result = script.executeAsString()
                                val matcher = regex.matcher(result)
                                if (matcher.matches()) {
                                    if (matcher.group(2) == "E9C8BA64-2188-4C31-8579-26BC786AF401") {
                                        newSlide = matcher.group(1)
                                        newDocument = "Lighting Talk"
                                        newPlayerState = PlayerState.PLAYING
                                    }
                                }
                            } catch (e: AppleScriptException) {
                                // ignored
                            }

                            if (newSlide != currentSlide || newDocument != currentDocument || newPlayerState != currentPlayerState) {
                                currentSlide = newSlide
                                currentDocument = newDocument
                                currentPlayerState = newPlayerState

                                trackChanged(trackDetails {
                                    artist = newDocument ?: ""
                                    title = newSlide ?: ""
                                    playerState = newPlayerState
                                })
                            }
                        }
                    }
                }
            }
        }

    }

    fun close() {
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

        return scripts.getOrPut(scriptKey) {
            runBlocking(compilerPool) {
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

    fun startScene(id: Int): SceneRunner {
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
        val sceneRunner = SceneRunner(
            this,
            script,
            scene,
            sceneIsActive = fixtures.isSceneActive(id),
            settingsValues = sceneSettingsValues,
        )

        return sceneRunner
    }

    fun startScene(sceneName: String): SceneRunner {
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
            val job = runningScenes[id] ?: return@read

            if (job.isActive) {
                job.cancel()
            }
        }
    }

    suspend fun evalScriptByName(scriptName: String, step: Int = 0): ScriptResult {
        val script = transaction(state.database) {
            DaoScript.find {
                (DaoScripts.name eq scriptName) and
                (DaoScripts.project eq project.id)
            }.first()
        }

        return script(scriptName, script.script, script.settings?.list.orEmpty()).run(step)
    }

    suspend fun compileLiteralScript(literalScript: String, scriptSettings: List<ScriptSetting<*>>): ScriptResult {
        return script("", literalScript, scriptSettings).compileStatus
    }

    suspend fun runLiteralScript(literalScript: String, scriptSettings: List<ScriptSetting<*>>, scriptName: String = "", step: Int = 0): ScriptResult {
        return script(scriptName, literalScript, scriptSettings).run(step)
    }

    fun trackChanged(request: TrackDetails) {
        fixtures.trackChanged(request.playerState == PlayerState.PLAYING, request.artist, request.title)
    }

    suspend fun requestCurrentTrackDetails() {
        _trackStateFlow.emit(trackState {
            playerState = PlayerState.HANDSHAKE
        })
    }

    class Script private constructor(
        val show: Show,
        val scriptName: String,
        val literalScript: String,
        val compiledResult: ResultWithDiagnostics<CompiledScript>,
        val settings: List<ScriptSetting<*>>,
    ) {
        val compileStatus: ScriptResult = ScriptResult(compiledResult)

        companion object {
            internal suspend operator fun invoke(show: Show, scriptName: String, literalScript: String, settings: List<ScriptSetting<*>>): Script {
                val expandedScript = """
                    |runBlocking {
                    |${literalScript}
                    |}
                """.trimMargin("|")

                val compilationConfiguration = createJvmCompilationConfigurationFromTemplate<LightingScript>()
                val compiledResult = BasicJvmScriptingHost().compiler(expandedScript.toScriptSource(), compilationConfiguration)

                return Script(show, scriptName, literalScript, compiledResult, settings)
            }
        }

        fun run(step: Int = 0, sceneName: String = "", sceneIsActive: Boolean = false, settingsValues: Map<String, ScriptSettingValue> = emptyMap()): ScriptResult {
            val compiledScript = compiledResult.valueOrNull() ?: return ScriptResult(compiledResult)

            val transaction = ControllerTransaction(show.fixtures.controllers)
            val fixturesWithTransaction = show.fixtures.withTransaction(transaction)

            val settings = this.settings.associate {
                (it.defaultValue as IntValue).int.toUInt()
                it.name to (settingsValues[it.name] ?: it.defaultValue)
            }

            val runResult = runBlocking(show.runnerPool) {
                val currentScope = CoroutineScope(show.runnerPool)

                BasicJvmScriptingHost().evaluator(compiledScript, ScriptEvaluationConfiguration {
                    providedProperties(Pair("show", show))
                    providedProperties(Pair("fixtures", fixturesWithTransaction))
                    providedProperties(Pair("scriptName", scriptName))
                    providedProperties(Pair("step", step))
                    providedProperties(Pair("sceneName", sceneName))
                    providedProperties(Pair("sceneIsActive", sceneIsActive))
                    providedProperties(Pair("settings", settings))
                    providedProperties(Pair("coroutineScope", currentScope))
                })
            }

            val actualChannelChanges = transaction.apply()

            val channelChanges = if (fixturesWithTransaction.customChangedChannels != null) {
                fixturesWithTransaction.customChangedChannels
            } else {
                actualChannelChanges
            }

            return ScriptResult(compiledResult, runResult, channelChanges)
        }
    }

    class SceneRunner(
        val show: Show,
        script: Script,
        val scene: DaoScene,
        step: Int = 0,
        sceneIsActive: Boolean = false,
        settingsValues: Map<String, ScriptSettingValue> = emptyMap()
    ) {
        val job: Job
        var result: ScriptResult? = null

        init {
            show.runningScenesLock.read {
                val job = show.runningScenes[scene.id.value] ?: return@read

                if (job.isActive) {
                    println("Previous run of scene still running. Stopping")
                    job.cancel()
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

            if (scene.mode == Mode.CHASE) {
                show.fixtures.recordChaseStart(scene.id.value)
            }

            job = CoroutineScope(show.runnerPool).launch {
                val runResult = BasicJvmScriptingHost().evaluator(compiledScript, ScriptEvaluationConfiguration {
                    providedProperties(Pair("show", show))
                    providedProperties(Pair("fixtures", fixturesWithTransaction))
                    providedProperties(Pair("scriptName", script.scriptName))
                    providedProperties(Pair("step", step))
                    providedProperties(Pair("sceneName", scene.name))
                    providedProperties(Pair("sceneIsActive", sceneIsActive))
                    providedProperties(Pair("settings", settings))
                    providedProperties(Pair("coroutineScope", this@launch))
                })

                val actualChannelChanges = transaction.apply()

                val channelChanges = if (fixturesWithTransaction.customChangedChannels != null) {
                    fixturesWithTransaction.customChangedChannels
                } else {
                    actualChannelChanges
                }

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

                result = ScriptResult(compiledResult, runResult, channelChanges)
            }

            show.runningScenesLock.write {
                show.runningScenes[scene.id.value] = job
            }
        }

        fun stop() {
            show.runningScenesLock.read {
                val job = show.runningScenes[scene.id.value] ?: return@read

                if (job.isActive) {
                    println("Previous run of scene still running. Stopping")
                    job.cancel()
                }
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
