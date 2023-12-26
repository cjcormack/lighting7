package uk.me.cormack.lighting7.show

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ticker
import kotlinx.coroutines.selects.select
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import uk.me.cormack.lighting7.models.*
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
    val initialSceneName: String,
    val runLoopScriptName: String?,
    val runLoopDelay: Long,
) {
    val fixtures = Fixtures()
    private val scripts = ConcurrentHashMap<String, Script>()

    val project = transaction(state.database) {
        DaoProject.find {
            DaoProjects.name eq projectName
        }.first()
    }

    suspend fun start() {
        try {
            evalScriptByName(loadFixturesScriptName)
            runScene(initialSceneName)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (runLoopScriptName != null) {
            GlobalScope.launch {
                runShow(runLoopScriptName, runLoopDelay)
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

    suspend fun script(scriptName: String, literalScript: String): Script {
        val scriptKey = "$scriptName-${literalScript.cacheKey()}"

        return scripts.getOrPut(scriptKey) {
            Script(this, scriptName, literalScript)
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun String.cacheKey(): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(this.toByteArray()).toHexString()
    }

    suspend fun runScene(id: Int): ScriptResult {
        val (scriptName, scriptBody) = transaction(state.database) {
            val scene = DaoScene.findById(id) ?: throw Error("Scene not found")

            println("Running scene '${scene.name}'")

            Pair(scene.script.name, scene.script.script)
        }

        return script(scriptName, scriptBody).run()
    }

    suspend fun runScene(sceneName: String): ScriptResult {
        val (scriptName, scriptBody) = transaction(state.database) {
            val scene = DaoScene.find {
                (DaoScenes.name eq sceneName) and
                (DaoScenes.project eq project.id)
            }.first()

            println("Running scene '${scene.name}'")

            Pair(scene.script.name, scene.script.script)
        }

        return script(scriptName, scriptBody).run()
    }

    suspend fun evalScriptByName(scriptName: String, step: Int = 0): ScriptResult {
        val script = transaction(state.database) {
            DaoScript.find {
                (DaoScripts.name eq scriptName) and
                (DaoScripts.project eq project.id)
            }.first()
        }

        println("Running ${script.name}")

        return script(scriptName, script.script).run()
    }

    suspend fun compileLiteralScript(literalScript: String): ScriptResult {
        return script("", literalScript).compileStatus
    }

    suspend fun runLiteralScript(literalScript: String, scriptName: String = "", step: Int = 0): ScriptResult {
        return script(scriptName, literalScript).run(step)
    }

    class Script private constructor(
        val show: Show,
        val scriptName: String,
        val literalScript: String,
        val compiledResult: ResultWithDiagnostics<CompiledScript>,
    ) {
        val compileStatus: ScriptResult = ScriptResult(compiledResult)

        companion object {
            internal suspend operator fun invoke(show: Show, scriptName: String, literalScript: String): Script {
                val compilationConfiguration = createJvmCompilationConfigurationFromTemplate<LightingScript>()
                val compiledResult = BasicJvmScriptingHost().compiler(literalScript.toScriptSource(), compilationConfiguration)

                return Script(show, scriptName, literalScript, compiledResult)
            }
        }

        suspend fun run(step: Int = 0): ScriptResult {
            val compiledScript = compiledResult.valueOrNull() ?: return ScriptResult(compiledResult)

            val runResult = BasicJvmScriptingHost().evaluator(compiledScript, ScriptEvaluationConfiguration {
                providedProperties(Pair("fixtures", show.fixtures))
                providedProperties(Pair("scriptName", scriptName))
                providedProperties(Pair("step", step))
            })

            return ScriptResult(compiledResult, runResult)
        }
    }
}

data class ScriptResult(
    val compileResult: ResultWithDiagnostics<CompiledScript>,
    val runResult: ResultWithDiagnostics<EvaluationResult>? = null,
)
