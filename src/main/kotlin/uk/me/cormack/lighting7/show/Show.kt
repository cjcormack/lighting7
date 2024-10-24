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
import kotlin.script.experimental.api.*
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost
import kotlin.script.experimental.jvmhost.createJvmCompilationConfigurationFromTemplate
import kotlin.text.toByteArray

@OptIn(DelicateCoroutinesApi::class, ObsoleteCoroutinesApi::class)
class Show(
    val state: State,
    val projectName: String,
    val loadFixturesScriptName: String,
    val defaultStateScriptName: String,
    val initialSceneName: String,
    val runLoopScriptName: String?,
    val runLoopDelay: Long,
) {
    val fixtures = Fixtures()
    private val scripts = ConcurrentHashMap<String, Script>()
    private val _trackStateFlow = MutableSharedFlow<TrackState>()
    val trackStateFlow = _trackStateFlow.asSharedFlow()

    val project = transaction(state.database) {
        DaoProject.find {
            DaoProjects.name eq projectName
        }.first()
    }

    suspend fun start() {
        try {
            evalScriptByName(loadFixturesScriptName)
            evalScriptByName(defaultStateScriptName)
            runScene(initialSceneName)
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

    suspend fun script(scriptName: String, literalScript: String, settings: List<ScriptSetting<*>>): Script {
        val scriptKey = "$scriptName-${literalScript.cacheKey()}"

        return scripts.getOrPut(scriptKey) {
            Script(this, scriptName, literalScript, settings)
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

    suspend fun runScene(id: Int): ScriptResult {
        val (sceneName, scriptName, scriptBody, scriptSettings, sceneSettingsValues) = transaction(state.database) {
            val scene = DaoScene.findById(id) ?: throw Error("Scene not found")

            ScriptData(scene.name, scene.script.name, scene.script.script, scene.script.settings?.list.orEmpty(), scene.settingsValues.orEmpty())
        }

        val sceneResult = script(scriptName, scriptBody, scriptSettings).run(sceneName = scriptName, sceneIsActive = fixtures.isSceneActive(sceneName), settingsValues = sceneSettingsValues)
        if (sceneResult.channelChanges != null) {
            fixtures.recordScene(sceneName, sceneResult.channelChanges)
        }

        return sceneResult
    }

    suspend fun runScene(sceneName: String): ScriptResult {
        val (_, scriptName, scriptBody, scriptSettings, sceneSettingsValues) = transaction(state.database) {
            val scene = DaoScene.find {
                (DaoScenes.name eq sceneName) and
                (DaoScenes.project eq project.id)
            }.first()

            println("Running scene '${scene.name}'")

            ScriptData(scene.name, scene.script.name, scene.script.script, scene.script.settings?.list.orEmpty(), scene.settingsValues.orEmpty())
        }

        val sceneResult = script(scriptName, scriptBody, scriptSettings).run(sceneName = scriptName, sceneIsActive = fixtures.isSceneActive(sceneName), settingsValues = sceneSettingsValues)

        if (sceneResult.channelChanges != null) {
            fixtures.recordScene(sceneName, sceneResult.channelChanges)
        }

        return sceneResult
    }

    suspend fun evalScriptByName(scriptName: String, step: Int = 0): ScriptResult {
        val script = transaction(state.database) {
            DaoScript.find {
                (DaoScripts.name eq scriptName) and
                (DaoScripts.project eq project.id)
            }.first()
        }

        println("Running ${script.name}")

        return script(scriptName, script.script, script.settings?.list.orEmpty()).run()
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
                val compilationConfiguration = createJvmCompilationConfigurationFromTemplate<LightingScript>()
                val compiledResult = BasicJvmScriptingHost().compiler(literalScript.toScriptSource(), compilationConfiguration)

                return Script(show, scriptName, literalScript, compiledResult, settings)
            }
        }

        suspend fun run(step: Int = 0, sceneName: String = "", sceneIsActive: Boolean = false, settingsValues: Map<String, ScriptSettingValue> = emptyMap()): ScriptResult {
            val compiledScript = compiledResult.valueOrNull() ?: return ScriptResult(compiledResult)

            val transaction = ControllerTransaction(show.fixtures.controllers)
            val fixturesWithTransaction = show.fixtures.withTransaction(transaction)

            val settings = this.settings.associate {
                (it.defaultValue as IntValue).int.toUInt()
                it.name to (settingsValues[it.name] ?: it.defaultValue)
            }

            val runResult = BasicJvmScriptingHost().evaluator(compiledScript, ScriptEvaluationConfiguration {
                providedProperties(Pair("fixtures", fixturesWithTransaction))
                providedProperties(Pair("scriptName", scriptName))
                providedProperties(Pair("step", step))
                providedProperties(Pair("sceneName", sceneName))
                providedProperties(Pair("sceneIsActive", sceneIsActive))
                providedProperties(Pair("settings", settings))
            })

            val actualChannelChanges = transaction.apply()

            val channelChanges = if (fixturesWithTransaction.customChangedChannels != null) {
                fixturesWithTransaction.customChangedChannels
            } else {
                actualChannelChanges
            }

            return ScriptResult(compiledResult, runResult, channelChanges)
        }
    }
}

data class ScriptResult(
    val compileResult: ResultWithDiagnostics<CompiledScript>,
    val runResult: ResultWithDiagnostics<EvaluationResult>? = null,
    val channelChanges: Map<Universe, Map<Int, UByte>>? = null,
)
